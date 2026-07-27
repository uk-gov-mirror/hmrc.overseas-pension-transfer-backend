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

package uk.gov.hmrc.overseaspensiontransferbackend.services

import play.api.http.Status.{CREATED, INTERNAL_SERVER_ERROR}
import play.api.libs.json.Json
import uk.gov.hmrc.auth.core.AffinityGroup.Individual
import uk.gov.hmrc.http.{HeaderCarrier, RequestId}
import uk.gov.hmrc.overseaspensiontransferbackend.base.{BaseISpec, UserAnswersTestData}
import uk.gov.hmrc.overseaspensiontransferbackend.models.authentication.{PsaId, PsaUser, PspId}
import uk.gov.hmrc.overseaspensiontransferbackend.models.dtos.{PsaSubmissionDTO, PspSubmissionDTO, SubmissionDTO}
import uk.gov.hmrc.overseaspensiontransferbackend.models.transfer.*
import uk.gov.hmrc.overseaspensiontransferbackend.models.{AnswersData, PstrNumber, SavedUserAnswers}
import uk.gov.hmrc.overseaspensiontransferbackend.repositories.SaveForLaterRepository

class TransferServiceISpec extends BaseISpec {

  implicit override val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("id")))

  private lazy val repository: SaveForLaterRepository = app.injector.instanceOf[SaveForLaterRepository]
  private lazy val service: TransferService           = app.injector.instanceOf[TransferService]

  private val psaId: PsaId     = PsaId("A123456")
  private val psaUser: PsaUser = PsaUser(psaId, internalId = "id", affinityGroup = Individual)

  "TransferService" - {

    "returns a SubmissionResponse with QT number when PSA submits and data is valid" in {
      val id  = freshId()
      val now = frozenNow()

      val saved = SavedUserAnswers(
        transferId  = id,
        pstr        = PstrNumber("12345678AB"),
        data        = UserAnswersTestData.fullUserAnswersInternalJson.as[AnswersData],
        lastUpdated = now
      )
      await(repository.set(saved)) mustBe true

      val dto: SubmissionDTO = PsaSubmissionDTO(
        referenceId = id,
        userId      = PsaId("A1234567"),
        lastUpdated = now
      )

      val normalised: NormalisedSubmission = dto.normalise(withReferenceId = id, authenticatedUser = psaUser)

      val downstreamPayload = Json.obj(
        "success" -> Json.obj(
          "qtReference"      -> "QT123456",
          "processingDate"   -> now.toString,
          "formBundleNumber" -> "123"
        )
      ).toString()

      stubPost("/etmp/RESTAdapter/pods/reports/qrops-transfer", downstreamPayload, CREATED)

      val result = await(service.submitTransfer(normalised))

      result mustBe Right(SubmissionResponse(QtNumber("QT123456"), now))
    }

    "returns a SubmissionResponse with QT number when PSP submits and data is valid" in {
      val id  = freshId()
      val now = frozenNow()

      val saved = SavedUserAnswers(
        transferId  = id,
        pstr        = PstrNumber("12345678AB"),
        data        = UserAnswersTestData.fullUserAnswersInternalJson.as[AnswersData],
        lastUpdated = now
      )
      await(repository.set(saved)) mustBe true

      val dto: SubmissionDTO = PspSubmissionDTO(
        referenceId = id,
        userId      = PspId("01234567"),
        psaId       = PsaId("A1234567"),
        lastUpdated = now
      )

      val normalised: NormalisedSubmission = dto.normalise(withReferenceId = id, authenticatedUser = psaUser)

      val downstreamPayload = Json.obj(
        "success" -> Json.obj(
          "qtReference"      -> "QT123456",
          "processingDate"   -> now.toString,
          "formBundleNumber" -> "123"
        )
      ).toString()

      stubPost("/etmp/RESTAdapter/pods/reports/qrops-transfer", downstreamPayload, CREATED)

      val result = await(service.submitTransfer(normalised))

      result mustBe Right(SubmissionResponse(QtNumber("QT123456"), now))
    }

    "returns SubmissionTransformationError when there is no prepared submission saved for ref" in {
      val id  = freshId()
      val now = frozenNow()

      val saved = SavedUserAnswers(
        transferId  = id,
        pstr        = PstrNumber("12345678AB"),
        data        = UserAnswersTestData.fullUserAnswersInternalJson.as[AnswersData],
        lastUpdated = now
      )
      await(repository.set(saved)) mustBe true

      val dto: SubmissionDTO = PsaSubmissionDTO(
        referenceId = id,
        userId      = PsaId("A1234567"),
        lastUpdated = now
      )

      val normalised: NormalisedSubmission = dto.normalise(withReferenceId = id, authenticatedUser = psaUser)

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

      val result = await(service.submitTransfer(normalised))
      result mustBe Left(SubmissionTransformationError("Submission failed validation"))
    }

    "returns SubmissionFailed when there is an downstream error" in {
      val id  = freshId()
      val now = frozenNow()

      val saved = SavedUserAnswers(
        transferId  = id,
        pstr        = PstrNumber("12345678AB"),
        data        = UserAnswersTestData.fullUserAnswersInternalJson.as[AnswersData],
        lastUpdated = now
      )
      await(repository.set(saved)) mustBe true

      val dto: SubmissionDTO = PsaSubmissionDTO(
        referenceId = id,
        userId      = PsaId("A1234567"),
        lastUpdated = now
      )

      val normalised: NormalisedSubmission = dto.normalise(withReferenceId = id, authenticatedUser = psaUser)

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

      stubPost("/etmp/RESTAdapter/pods/reports/qrops-transfer", downstreamPayload, CREATED)

      val result = await(service.submitTransfer(normalised))

      result mustBe Left(SubmissionFailed)
    }
  }
}
