/*
 * Copyright 2025 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.overseaspensiontransferbackend.connectors

import com.github.tomakehurst.wiremock.client.WireMock.*
import play.api.http.Status.*
import play.api.libs.json.Json
import uk.gov.hmrc.http.{HeaderCarrier, RequestId}
import uk.gov.hmrc.overseaspensiontransferbackend.base.BaseISpec
import uk.gov.hmrc.overseaspensiontransferbackend.models.*
import uk.gov.hmrc.overseaspensiontransferbackend.models.authentication.{Psa, PsaId}
import uk.gov.hmrc.overseaspensiontransferbackend.models.downstream.*
import uk.gov.hmrc.overseaspensiontransferbackend.models.downstream.HipOriginFailures.Failure
import uk.gov.hmrc.overseaspensiontransferbackend.models.transfer.QtNumber
import uk.gov.hmrc.overseaspensiontransferbackend.utils.DateTimeFormats.localDate
import uk.gov.hmrc.overseaspensiontransferbackend.validators.Submission

import java.time.{Instant, LocalDate}

class TransferConnectorISpec extends BaseISpec {

  private val now  = Instant.now(fixedClock)
  private val pstr = PstrNumber("12345678AB")

  private val submission = Submission(
    ReportDetails(pstr.value, Submitted, None, None),
    None,
    None,
    None,
    QtDeclaration(Psa, PsaId("A1234567"), None),
    Some(Declaration(true, declaration2 = true)),
    None
  )

  val correlationId = "e470d658-99f7-4292-a4a1-ed12c72f1337"

  private val connector = app.injector.instanceOf[TransferConnector]

  "submit" - {
    "send correlationId as header when RequestId is present" in {
      connector.submitTransfer(submission, correlationId)

      val correlationIdRegex = "^[0-9a-fA-F]{8}[-][0-9a-fA-F]{4}[-][0-9a-fA-F]{4}[-][0-9a-fA-F]{4}[-][0-9a-fA-F]{12}$".r

      verify(postRequestedFor(urlEqualTo("/etmp/RESTAdapter/pods/reports/qrops-transfer"))
        .withHeader("correlationid", matching(correlationIdRegex.toString())))
    }

    "return DownstreamSuccess when 201 and valid payload is returned from downstream" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))

      val downstreamPayload = Json.obj(
        "success" -> Json.obj(
          "qtReference"      -> "QT123456",
          "processingDate"   -> now.toString,
          "formBundleNumber" -> "123"
        )
      ).toString()

      stubPost("/etmp/RESTAdapter/pods/reports/qrops-transfer", downstreamPayload, CREATED)

      val result: Either[DownstreamError, DownstreamSuccess] = await(connector.submitTransfer(submission, correlationId))

      result mustBe Right(DownstreamSuccess(QtNumber("QT123456"), now, "123"))
    }

    "return HipBadRequest when 400 is returned with valid payload" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))

      val downstreamPayload = Json.obj(
        "origin"   -> "HIP",
        "response" -> Json.obj(
          "error" -> Json.obj(
            "code"    -> "code",
            "message" -> "There's been an error",
            "logID"   -> "logID"
          )
        )
      ).toString()

      stubPost("/etmp/RESTAdapter/pods/reports/qrops-transfer", downstreamPayload, BAD_REQUEST)

      val result: Either[DownstreamError, DownstreamSuccess] = await(connector.submitTransfer(submission, correlationId))

      result mustBe Left(HipBadRequest("HIP", "code", "There's been an error", Some("logID")))
    }

    "return EtmpValidationError when 422 is returned with valid payload" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))

      val downstreamPayload = Json.obj(
        "errors" -> Json.obj(
          "processingDate" -> now,
          "code"           -> "003",
          "text"           -> "Request could not be processed"
        )
      ).toString()

      stubPost("/etmp/RESTAdapter/pods/reports/qrops-transfer", downstreamPayload, UNPROCESSABLE_ENTITY)

      val result: Either[DownstreamError, DownstreamSuccess] = await(connector.submitTransfer(submission, correlationId))

      result mustBe Left(EtmpValidationError(now.toString, "003", "Request could not be processed"))
    }

    "return HipBadRequest when 500 is returned with valid payload" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))

      val downstreamPayload = Json.obj(
        "origin"   -> "HoD",
        "response" -> Json.obj(
          "error" -> Json.obj(
            "code"    -> "code",
            "message" -> "There's been an error",
            "logID"   -> "logID"
          )
        )
      ).toString()

      stubPost("/etmp/RESTAdapter/pods/reports/qrops-transfer", downstreamPayload, INTERNAL_SERVER_ERROR)

      val result: Either[DownstreamError, DownstreamSuccess] = await(connector.submitTransfer(submission, correlationId))

      result mustBe Left(HipBadRequest("HoD", "code", "There's been an error", Some("logID")))
    }

    "return HipOriginFailures when 503 is returned with valid payload" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))

      val downstreamPayload = Json.obj(
        "origin"   -> "HoD",
        "response" -> Json.obj(
          "failures" -> Seq(
            Json.obj(
              "type"   -> "type",
              "reason" -> "reason"
            )
          )
        )
      ).toString()

      stubPost("/etmp/RESTAdapter/pods/reports/qrops-transfer", downstreamPayload, SERVICE_UNAVAILABLE)

      val result: Either[DownstreamError, DownstreamSuccess] = await(connector.submitTransfer(submission, correlationId))

      result mustBe Left(HipOriginFailures("HoD", List(Failure("type", "reason"))))
    }
  }

  "getTransfer" - {
    "return Right DownstreamTransferData when 200 and valid data is returned from downstream" in {
      val downstreamPayload = Json.obj(
        "success" -> Json.obj(
          "pstr"      -> "12345678AB",
          "qtDetails" -> Json.obj(
            "qtVersion"   -> "001",
            "qtStatus"    -> "Submitted",
            "receiptDate" -> now,
            "qtReference" -> "QT123456"
          )
        )
      ).toString()

      stubGet("/etmp/RESTAdapter/pods/reports/qrops-transfer?pstr=12345678AB&qtNumber=QT123456&versionNumber=001", downstreamPayload)

      val result: Either[DownstreamError, DownstreamTransferData] = await(connector.getTransfer(PstrNumber("12345678AB"), QtNumber("QT123456"), "001"))

      result mustBe Right(
        DownstreamTransferData(
          PstrNumber("12345678AB"),
          QtDetails(
            "001",
            Submitted,
            now,
            QtNumber("QT123456"),
            None,
            None
          ),
          None,
          None,
          None
        )
      )
    }

    "return HipBadRequest when 400 is returned with valid payload" in {
      val downstreamPayload = Json.obj(
        "origin"   -> "HIP",
        "response" -> Json.obj(
          "error" -> Json.obj(
            "code"    -> "code",
            "message" -> "There's been an error",
            "logID"   -> "logID"
          )
        )
      ).toString()

      stubGet("/etmp/RESTAdapter/pods/reports/qrops-transfer?pstr=12345678AB&qtNumber=QT123456&versionNumber=001", downstreamPayload, BAD_REQUEST)

      val result: Either[DownstreamError, DownstreamTransferData] = await(connector.getTransfer(PstrNumber("12345678AB"), QtNumber("QT123456"), "001"))

      result mustBe Left(HipBadRequest("HIP", "code", "There's been an error", Some("logID")))
    }

    "return EtmpValidationError when 422 is returned with valid payload" in {
      val downstreamPayload = Json.obj(
        "errors" -> Json.obj(
          "processingDate" -> now,
          "code"           -> "003",
          "text"           -> "Request could not be processed"
        )
      ).toString()

      stubGet("/etmp/RESTAdapter/pods/reports/qrops-transfer?pstr=12345678AB&qtNumber=QT123456&versionNumber=001", downstreamPayload, UNPROCESSABLE_ENTITY)

      val result: Either[DownstreamError, DownstreamTransferData] = await(connector.getTransfer(PstrNumber("12345678AB"), QtNumber("QT123456"), "001"))

      result mustBe Left(EtmpValidationError(now.toString, "003", "Request could not be processed"))
    }

    "return HipBadRequest when 500 is returned with valid payload" in {
      val downstreamPayload = Json.obj(
        "origin"   -> "HoD",
        "response" -> Json.obj(
          "error" -> Json.obj(
            "code"    -> "code",
            "message" -> "There's been an error",
            "logID"   -> "logID"
          )
        )
      ).toString()

      stubGet("/etmp/RESTAdapter/pods/reports/qrops-transfer?pstr=12345678AB&qtNumber=QT123456&versionNumber=001", downstreamPayload, INTERNAL_SERVER_ERROR)

      val result: Either[DownstreamError, DownstreamTransferData] = await(connector.getTransfer(PstrNumber("12345678AB"), QtNumber("QT123456"), "001"))

      result mustBe Left(HipBadRequest("HoD", "code", "There's been an error", Some("logID")))
    }

    "return HipOriginFailures when 503 is returned with valid payload" in {
      val downstreamPayload = Json.obj(
        "origin"   -> "HoD",
        "response" -> Json.obj(
          "failures" -> Seq(
            Json.obj(
              "type"   -> "type",
              "reason" -> "reason"
            )
          )
        )
      ).toString()

      stubGet("/etmp/RESTAdapter/pods/reports/qrops-transfer?pstr=12345678AB&qtNumber=QT123456&versionNumber=001", downstreamPayload, SERVICE_UNAVAILABLE)

      val result: Either[DownstreamError, DownstreamTransferData] = await(connector.getTransfer(PstrNumber("12345678AB"), QtNumber("QT123456"), "001"))

      result mustBe Left(HipOriginFailures("HoD", List(Failure("type", "reason"))))
    }
  }

  "getAllTransfers" - {

    val pstr      = PstrNumber("12345678AB")
    val fromDate  = LocalDate.of(2025, 9, 22)
    val toDate    = fromDate.minusYears(10)
    val basePath  = "/etmp/RESTAdapter/pods/reports/qrops-transfer-overview"

    def successBody(now: Instant): String =
      Json.obj(
        "success" -> Json.obj(
          "qropsTransferOverview" -> Json.arr(
            Json.obj(
              "fbNumber"                  -> "123456000023",
              "qtReference"               -> "QT564321",
              "qtVersion"                 -> "001",
              "qtStatus"                  -> "Compiled",
              "qtDigitalStatus"           -> "Complied",
              "nino"                      -> generateNino(),
              "firstName"                 -> "David",
              "lastName"                  -> "Warne",
              "qtDate"                    -> "2025-03-14",
              "qropsReference"            -> "QROPS654321",
              "submissionCompilationDate" -> now.toString
            )
          )
        )
      ).toString()

    "send correlationId header and required query params (without qtRef)" in {
      stubFor(
        get(urlPathEqualTo(basePath))
          .withQueryParam("dateFrom", equalTo(fromDate.format(localDate)))
          .withQueryParam("dateTo", equalTo(toDate.format(localDate)))
          .withQueryParam("pstr", equalTo(pstr.normalised))
          .willReturn(aResponse().withStatus(OK).withBody(successBody(now)))
      )

      await(connector.getAllTransfers(pstr, fromDate, toDate, None))

      val correlationIdRegex = "^[0-9a-fA-F]{8}[-][0-9a-fA-F]{4}[-][0-9a-fA-F]{4}[-][0-9a-fA-F]{4}[-][0-9a-fA-F]{12}$".r

      verify(
        getRequestedFor(urlPathEqualTo(basePath))
          .withHeader("correlationid", matching(correlationIdRegex.toString()))
          .withQueryParam("dateFrom", equalTo(fromDate.format(localDate)))
          .withQueryParam("dateTo", equalTo(toDate.format(localDate)))
          .withQueryParam("pstr", equalTo(pstr.normalised))
      )
    }

    "include qtRef when provided" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))
      val qt                         = QtNumber("QT123456")

      stubFor(
        get(urlPathEqualTo(basePath))
          .withQueryParam("dateFrom", equalTo(fromDate.format(localDate)))
          .withQueryParam("dateTo", equalTo(toDate.format(localDate)))
          .withQueryParam("pstr", equalTo(pstr.normalised))
          .withQueryParam("qtRef", equalTo(qt.value))
          .willReturn(aResponse().withStatus(OK).withBody(successBody(now)))
      )

      await(connector.getAllTransfers(pstr, fromDate, toDate, Some(qt)))

      verify(
        getRequestedFor(urlPathEqualTo(basePath))
          .withQueryParam("qtRef", equalTo(qt.value))
      )
    }

    "return Right(DownstreamAllTransfersData) on 200 with valid payload" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))

      stubFor(
        get(urlPathEqualTo(basePath))
          .withQueryParam("dateFrom", equalTo(fromDate.format(localDate)))
          .withQueryParam("dateTo", equalTo(toDate.format(localDate)))
          .withQueryParam("pstr", equalTo(pstr.normalised))
          .willReturn(aResponse().withStatus(OK).withBody(successBody(now)))
      )

      val result = await(connector.getAllTransfers(pstr, fromDate, toDate, None))

      result.isRight mustBe true
      val data = result.toOption.get
      data.success.qropsTransferOverview.head.qtReference mustBe "QT564321"
    }

    "map 400 HIP error to HipBadRequest" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))
      val body                       = Json.obj(
        "origin"   -> "HIP",
        "response" -> Json.obj(
          "error" -> Json.obj(
            "code"    -> "code",
            "message" -> "There's been an error",
            "logID"   -> "logID"
          )
        )
      ).toString()

      stubFor(
        get(urlPathEqualTo(basePath))
          .withQueryParam("dateFrom", equalTo(fromDate.format(localDate)))
          .withQueryParam("dateTo", equalTo(toDate.format(localDate)))
          .withQueryParam("pstr", equalTo(pstr.normalised))
          .willReturn(aResponse().withStatus(BAD_REQUEST).withBody(body))
      )

      val result = await(connector.getAllTransfers(pstr, fromDate, toDate, None))
      result mustBe Left(HipBadRequest("HIP", "code", "There's been an error", Some("logID")))
    }

    "map 422 to EtmpValidationError" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))
      val body                       = Json.obj(
        "errors" -> Json.obj(
          "processingDate" -> now.toString,
          "code"           -> "003",
          "text"           -> "Request could not be processed"
        )
      ).toString()

      stubFor(
        get(urlPathEqualTo(basePath))
          .withQueryParam("dateFrom", equalTo(fromDate.format(localDate)))
          .withQueryParam("dateTo", equalTo(toDate.format(localDate)))
          .withQueryParam("pstr", equalTo(pstr.normalised))
          .willReturn(aResponse().withStatus(UNPROCESSABLE_ENTITY).withBody(body))
      )

      val result = await(connector.getAllTransfers(pstr, fromDate, toDate, None))
      result mustBe Left(EtmpValidationError(now.toString, "003", "Request could not be processed"))
    }

    "map 422 with subcode 183 to NoTransfersFound" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))
      val body                       = Json.obj(
        "errors" -> Json.obj(
          "processingDate" -> now.toString,
          "code"           -> "183",
          "text"           -> "No QT was found in ETMP for the requested details"
        )
      ).toString()

      stubFor(
        get(urlPathEqualTo(basePath))
          .withQueryParam("dateFrom", equalTo(fromDate.format(localDate)))
          .withQueryParam("dateTo", equalTo(toDate.format(localDate)))
          .withQueryParam("pstr", equalTo(pstr.normalised))
          .willReturn(aResponse().withStatus(UNPROCESSABLE_ENTITY).withBody(body))
      )

      val result = await(connector.getAllTransfers(pstr, fromDate, toDate, None))
      result mustBe Left(NoTransfersFound)
    }

    "map 500 to HipBadRequest (HoD origin example)" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))
      val body                       = Json.obj(
        "origin"   -> "HoD",
        "response" -> Json.obj(
          "error" -> Json.obj(
            "code"    -> "code",
            "message" -> "There's been an error",
            "logID"   -> "logID"
          )
        )
      ).toString()

      stubFor(
        get(urlPathEqualTo(basePath))
          .withQueryParam("dateFrom", equalTo(fromDate.format(localDate)))
          .withQueryParam("dateTo", equalTo(toDate.format(localDate)))
          .withQueryParam("pstr", equalTo(pstr.normalised))
          .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR).withBody(body))
      )

      val result = await(connector.getAllTransfers(pstr, fromDate, toDate, None))
      result mustBe Left(HipBadRequest("HoD", "code", "There's been an error", Some("logID")))
    }

    "map 503 failures to HipOriginFailures" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))
      val body                       = Json.obj(
        "origin"   -> "HoD",
        "response" -> Json.obj(
          "failures" -> Seq(
            Json.obj("type" -> "type", "reason" -> "reason")
          )
        )
      ).toString()

      stubFor(
        get(urlPathEqualTo(basePath))
          .withQueryParam("dateFrom", equalTo(fromDate.format(localDate)))
          .withQueryParam("dateTo", equalTo(toDate.format(localDate)))
          .withQueryParam("pstr", equalTo(pstr.normalised))
          .willReturn(aResponse().withStatus(SERVICE_UNAVAILABLE).withBody(body))
      )

      val result = await(connector.getAllTransfers(pstr, fromDate, toDate, None))
      result mustBe Left(HipOriginFailures("HoD", List(Failure("type", "reason"))))
    }
  }
}
