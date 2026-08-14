/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.overseaspensiontransferbackend.repositories

import org.apache.pekko.Done
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.*
import play.api.libs.functional.syntax.*
import play.api.libs.json.*
import uk.gov.hmrc.mdc.Mdc
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.overseaspensiontransferbackend.config.AppConfig
import uk.gov.hmrc.overseaspensiontransferbackend.models.*
import uk.gov.hmrc.overseaspensiontransferbackend.models.transfer.{AllTransfersItem, TransferId}
import uk.gov.hmrc.overseaspensiontransferbackend.repositories.SaveForLaterRepository.{encryptedFormat, unencryptedFormat}
import uk.gov.hmrc.overseaspensiontransferbackend.services.EncryptionService

import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SaveForLaterRepository @Inject() (
    mongoComponent: MongoComponent,
    encryptionService: EncryptionService,
    appConfig: AppConfig,
    clock: Clock
  )(implicit ec: ExecutionContext
  ) extends PlayMongoRepository[SavedUserAnswers](
      collectionName = "saved-user-answers",
      mongoComponent = mongoComponent,
      domainFormat   = if (appConfig.mongoDBEncryption) { encryptedFormat(encryptionService) }
      else { unencryptedFormat },
      indexes        = Seq(
        IndexModel(
          Indexes.ascending("lastUpdated"),
          IndexOptions()
            .name("lastUpdatedIdx")
            .expireAfter(appConfig.cacheTtl, TimeUnit.DAYS)
        )
      )
    ) {

  implicit val instantFormat: Format[Instant] = MongoJavatimeFormats.instantFormat

  private def byId(id: String): Bson         = Filters.equal("_id", id)
  private def byPstr(pstr: PstrNumber): Bson = Filters.equal("pstr", pstr.value)

  def get(referenceId: String): Future[Option[SavedUserAnswers]] = Mdc.preservingMdc {
    collection
      .find(byId(referenceId))
      .headOption()
  }

  def getRecords(pstr: PstrNumber): Future[Seq[AllTransfersItem]] = Mdc.preservingMdc {
    collection
      .find(byPstr(pstr))
      .toFuture()
      .map(
        _.map(_.toAllTransfersItem)
      )
  }

  def set(answers: SavedUserAnswers): Future[Boolean] = Mdc.preservingMdc {
    val updatedAnswers = answers `copy` (lastUpdated = Instant.now(clock))
    collection
      .replaceOne(
        filter      = byId(updatedAnswers.transferId.value),
        replacement = updatedAnswers,
        options     = ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_.wasAcknowledged())
  }

  def clear(referenceId: String): Future[Boolean] = Mdc.preservingMdc {
    collection
      .deleteOne(byId(referenceId))
      .toFuture()
      .map(_ => true)
  }

  def clear: Future[Done] = Mdc.preservingMdc {
    collection.drop().toFuture().map(_ => Done)
  }

  def testOnlyDeleteByPstr(pstr: PstrNumber): Future[Unit] = Mdc.preservingMdc {
    collection.deleteMany(Filters.equal("pstr", pstr.value)).toFuture().map(_ => ())
  }
}

object SaveForLaterRepository {

  def encryptedFormat(encryptionService: EncryptionService): OFormat[SavedUserAnswers] = {
    val reads: Reads[SavedUserAnswers] = (
      (__ \ "transferId").read[TransferId] and
        (__ \ "pstr").read[PstrNumber] and
        (__ \ "data").read[String].map { enc =>
          EncryptedAnswersData(enc).decrypt(encryptionService) match {
            case Right(decrypted) => decrypted.data
            case Left(err)        => throw new RuntimeException(s"Decryption failed: ${err.getMessage}")
          }
        } and
        (__ \ "lastUpdated").read(MongoJavatimeFormats.instantFormat)
    )(SavedUserAnswers.apply)

    val writes: OWrites[SavedUserAnswers] = OWrites { ua =>
      val encrypted: EncryptedAnswersData =
        DecryptedAnswersData(ua.data).encrypt(encryptionService)

      Json.obj(
        "transferId"  -> ua.transferId,
        "pstr"        -> ua.pstr,
        "data"        -> encrypted.encryptedString,
        "lastUpdated" -> MongoJavatimeFormats.instantFormat.writes(ua.lastUpdated)
      )
    }

    OFormat(reads, writes)
  }

  val unencryptedFormat: OFormat[SavedUserAnswers] = Json.format
}
