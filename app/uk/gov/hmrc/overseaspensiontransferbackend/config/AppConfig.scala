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

package uk.gov.hmrc.overseaspensiontransferbackend.config

import play.api.Configuration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import javax.inject.{Inject, Singleton}

@Singleton
class AppConfig @Inject() (config: Configuration, servicesConfig: ServicesConfig) {

  val appName: String = config.get[String]("appName")

  val etmpBaseUrl: String          = config.get[Service]("microservice.services.hip").baseUrl
  val pensionSchemeService: String = servicesConfig.baseUrl("pensions-scheme")

  val cacheTtl: Long             = config.get[Int]("mongodb.timeToLiveInDays")
  val mongoDBEncryption: Boolean = config.get[Boolean]("mongodb.encryption")

  val getAllTransfersYearsOffset: Int = config.get[Int]("getAllTransfers.yearsOffset")

  val clientId: String     = config.get[String]("microservice.services.hip.clientId")
  val clientSecret: String = config.get[String]("microservice.services.hip.clientSecret")

  case class EnrolmentConfig(serviceName: String, identifierKey: String)

  private def loadEnrolmentConfig(role: String): EnrolmentConfig =
    EnrolmentConfig(
      config.get[String](s"enrolments.$role.serviceName"),
      config.get[String](s"enrolments.$role.identifierKey")
    )

  val psaEnrolment: EnrolmentConfig = loadEnrolmentConfig("psa")
  val pspEnrolment: EnrolmentConfig = loadEnrolmentConfig("psp")
}
