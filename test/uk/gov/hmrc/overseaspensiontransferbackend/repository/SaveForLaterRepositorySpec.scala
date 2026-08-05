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

package uk.gov.hmrc.overseaspensiontransferbackend.repository

import org.apache.pekko.Done
import org.mongodb.scala.bson.collection.immutable.Document
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.overseaspensiontransferbackend.base.TestAppConfig
import uk.gov.hmrc.overseaspensiontransferbackend.config.AppConfig
import uk.gov.hmrc.overseaspensiontransferbackend.models.*
import uk.gov.hmrc.overseaspensiontransferbackend.models.transfer.{TransferId, TransferNumber}
import uk.gov.hmrc.overseaspensiontransferbackend.repositories.SaveForLaterRepository

import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

class SaveForLaterRepositorySpec
    extends AnyFreeSpec
    with Matchers
    with OptionValues
    with CleanMongoCollectionSupport
    with ScalaFutures {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 5.seconds, interval = 50.millis)

  override val databaseName: String = "test-saveforlater"
  private val collectionName        = "saved-user-answers"

  private val now                  = Instant.parse("2025-01-01T00:00:00Z")
  private val mutableClock         = Clock.fixed(now, ZoneOffset.UTC)
  private val encryption           = TestAppConfig.encryptionService
  private val appConfig: AppConfig = TestAppConfig.appConfig()

  private def getRepository(config: AppConfig = appConfig) = new SaveForLaterRepository(
    mongoComponent    = mongoComponent,
    encryptionService = encryption,
    appConfig         = config,
    clock             = mutableClock
  )

  private val rawCollection =
    mongoComponent.database.getCollection[Document](collectionName)

  private def buildUserAnswer(referenceId: TransferId = TransferNumber("ref-useranswer-001"), pstr: PstrNumber = PstrNumber("12345678AB")): SavedUserAnswers =
    SavedUserAnswers(
      referenceId,
      pstr,
      AnswersData(
        transferringMember  = None,
        aboutReceivingQROPS = None,
        transferDetails     = Some(
          TransferDetails(
            recordVersion                  = None,
            transferAmount                 = Some(1000),
            allowanceBeforeTransfer        = Some(5000),
            dateMemberTransferred          = Some(java.time.LocalDate.parse("2025-01-01")),
            cashOnlyTransfer               = Some("No"),
            paymentTaxableOverseas         = Some("Yes"),
            reasonNoOverseasTransfer       = None,
            taxableOverseasTransferDetails = None,
            typeOfAssets                   = Some(
              TypeOfAssets(
                recordVersion       = None,
                cashAssets          = Some("Yes"),
                cashValue           = Some(1000),
                unquotedShareAssets = Some("Yes"),
                unquotedShares      = Some(List(UnquotedShares(None, Some(1000), Some(100), Some("Company A"), Some("A")))),
                quotedShareAssets   = Some("Yes"),
                quotedShares        = Some(List(QuotedShares(None, Some(2000), Some(200), Some("Company B"), Some("B")))),
                propertyAsset       = Some("Yes"),
                propertyAssets      = Some(List(PropertyAssets(
                  recordVersion   = None,
                  propertyAddress = Some(Address(
                    addressLine1 = Some("6 Test Address"),
                    addressLine2 = Some("Test Street"),
                    addressLine3 = None,
                    addressLine4 = None,
                    addressLine5 = None,
                    ukPostCode   = Some("XX89 6YY"),
                    country      = Some("GB")
                  )),
                  propValue       = Some(300),
                  propDescription = Some("Test Property")
                ))),
                otherAsset          = Some("Yes"),
                moreAsset           = None,
                moreProp            = None,
                moreQuoted          = None,
                moreUnquoted        = None,
                otherAssets         = Some(
                  List(OtherAssets(None, Some(400), Some("Other Asset")))
                )
              )
            )
          )
        ),
        None
      ),
      now
    )

  "SaveForLaterRepository when encryption is on" - {
    val repository = getRepository()
    "must save and retrieve simple record with encryption" in {
      val simpleSaved = SavedUserAnswers(TransferNumber("ref-simple"), PstrNumber("12345678AB"), AnswersData(None, None, None, None), now)
      repository.set(simpleSaved).futureValue mustBe true

      val retrieved = repository.get("ref-simple").futureValue.value
      retrieved.transferId  mustBe TransferNumber("ref-simple")
      retrieved.data        mustBe simpleSaved.data
      retrieved.lastUpdated mustBe now

      val raw = rawCollection.find().headOption().futureValue.value
      raw.get("data").get.asString().getValue must not include "AnswersData"
    }

    "must save and retrieve userAnswer JSON correctly" in {
      val saved = buildUserAnswer()
      repository.set(saved).futureValue mustBe true

      val retrieved = repository.get(saved.transferId.value).futureValue.value
      retrieved mustBe saved

      val propertyAsset = retrieved.data.transferDetails.value.typeOfAssets.value.propertyAssets.value.head.propertyAddress.get
      propertyAsset.addressLine1.value mustBe "6 Test Address"
      propertyAsset.addressLine2.value mustBe "Test Street"
      propertyAsset.ukPostCode.value   mustBe "XX89 6YY"
    }

    "must produce different ciphertexts for the same userAnswer input" in {
      val saved1 = buildUserAnswer(TransferNumber("ref1"))
      val saved2 = buildUserAnswer(TransferNumber("ref2"))

      repository.set(saved1).futureValue mustBe true
      repository.set(saved2).futureValue mustBe true

      val rawDocs = rawCollection.find().collect().head().futureValue
      val enc1    = rawDocs.find(_.get("transferId").get.asString().getValue == "ref1").get.get("data").get.asString().getValue
      val enc2    = rawDocs.find(_.get("transferId").get.asString().getValue == "ref2").get.get("data").get.asString().getValue

      enc1 must not be enc2
    }

    "must delete a record by referenceId" in {
      val saved = buildUserAnswer(TransferNumber("ref-delete"))
      repository.set(saved).futureValue          mustBe true
      repository.clear("ref-delete").futureValue mustBe true
      repository.get("ref-delete").futureValue   mustBe None
    }

    "must delete all records with clear" in {
      val saved = buildUserAnswer(TransferNumber("ref-clear"))
      repository.set(saved).futureValue       mustBe true
      repository.clear.futureValue            mustBe Done
      repository.get("ref-clear").futureValue mustBe None
    }

    "must throw when decryption fails for corrupted payload" in {
      val corruptDoc = Document(
        "_id"         -> "ref-corrupt",
        "pstr"        -> "12345678AB",
        "data"        -> "invalid-encryption",
        "lastUpdated" -> java.util.Date.from(now)
      )
      rawCollection.insertOne(corruptDoc).head().futureValue

      assertThrows[RuntimeException] {
        repository.get("ref-corrupt").futureValue
      }
    }

    "must handle empty referenceId gracefully" in {
      val saved = buildUserAnswer(TransferNumber(""))
      repository.set(saved).futureValue               mustBe true
      repository.get("").futureValue.value.transferId mustBe TransferNumber("")
      repository.clear("").futureValue                mustBe true
      repository.get("").futureValue                  mustBe None
    }
  }

  "SaveForLaterRepository when encryption is off" - {
    val appConfig: AppConfig = TestAppConfig.appConfigEncryptionOff()
    val repository           = getRepository(appConfig)
    "must save and retrieve simple record without encryption" in {
      val answersData = AnswersData(None, None, None, Some(true))
      val simpleSaved = SavedUserAnswers(TransferNumber("ref-simple"), PstrNumber("12345678AB"), answersData, now)
      repository.set(simpleSaved).futureValue mustBe true

      val retrieved = repository.get("ref-simple").futureValue.value
      retrieved.transferId  mustBe TransferNumber("ref-simple")
      retrieved.data        mustBe simpleSaved.data
      retrieved.lastUpdated mustBe now
      val raw           = rawCollection.find().headOption().futureValue.value
      val persistedData = raw.get("data").toString
      persistedData.contains(""""submitToHMRC": true""") mustBe true
    }

    "must save and retrieve userAnswer JSON correctly" in {
      val saved = buildUserAnswer()
      repository.set(saved).futureValue mustBe true

      val retrieved = repository.get(saved.transferId.value).futureValue.value
      retrieved mustBe saved

      val propertyAsset = retrieved.data.transferDetails.value.typeOfAssets.value.propertyAssets.value.head.propertyAddress.get
      propertyAsset.addressLine1.value mustBe "6 Test Address"
      propertyAsset.addressLine2.value mustBe "Test Street"
      propertyAsset.ukPostCode.value   mustBe "XX89 6YY"
    }

    "must delete a record by referenceId" in {
      val saved = buildUserAnswer(TransferNumber("ref-delete"))
      repository.set(saved).futureValue          mustBe true
      repository.clear("ref-delete").futureValue mustBe true
      repository.get("ref-delete").futureValue   mustBe None
    }

    "must delete all records with clear" in {
      val saved = buildUserAnswer(TransferNumber("ref-clear"))
      repository.set(saved).futureValue       mustBe true
      repository.clear.futureValue            mustBe Done
      repository.get("ref-clear").futureValue mustBe None
    }

    "must throw when decryption fails for corrupted payload" in {
      val corruptDoc = Document(
        "_id"         -> "ref-corrupt",
        "pstr"        -> "12345678AB",
        "data"        -> "invalid-encryption",
        "lastUpdated" -> java.util.Date.from(now)
      )
      rawCollection.insertOne(corruptDoc).head().futureValue

      assertThrows[RuntimeException] {
        repository.get("ref-corrupt").futureValue
      }
    }

    "must handle empty referenceId gracefully" in {
      val saved = buildUserAnswer(TransferNumber(""))
      repository.set(saved).futureValue               mustBe true
      repository.get("").futureValue.value.transferId mustBe TransferNumber("")
      repository.clear("").futureValue                mustBe true
      repository.get("").futureValue                  mustBe None
    }
  }
}
