/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.sparkta.plugin.dimension.datetime

import java.io.{Serializable => JSerializable}
import java.util.Date

import akka.event.slf4j.SLF4JLogging
import org.joda.time.DateTime

import DateTimeDimension._
import com.stratio.sparkta.sdk.TypeOp.TypeOp
import com.stratio.sparkta.sdk._

case class DateTimeDimension(props: Map[String, JSerializable]) extends Bucketer with JSerializable with SLF4JLogging {

  def this() {
    this(Map())
  }

  override val properties: Map[String, JSerializable] = props ++ {
    if (!props.contains(GranularityPropertyName)) Map(GranularityPropertyName -> DefaultGranularity) else Map()
  }

  override val bucketTypes: Seq[BucketType] = Seq(
    timestamp,
    getSeconds(getTypeOperation(SecondName), defaultTypeOperation),
    getMinutes(getTypeOperation(MinuteName), defaultTypeOperation),
    getHours(getTypeOperation(HourName), defaultTypeOperation),
    getDays(getTypeOperation(DayName), defaultTypeOperation),
    getMonths(getTypeOperation(MonthName), defaultTypeOperation),
    getYears(getTypeOperation(YearName), defaultTypeOperation))

  @throws(classOf[ClassCastException])
  override def bucket(value: JSerializable): Map[BucketType, JSerializable] =
    try {
      bucketTypes.map(bucketType =>
        bucketType -> DateTimeDimension.bucket(value.asInstanceOf[Date], bucketType, properties)).toMap
    }
    catch {
      case cce: ClassCastException => log.error("Error parsing " + value + " .") throw cce
    }

  override val defaultTypeOperation = TypeOp.Timestamp
}

object DateTimeDimension {

  private final val DefaultGranularity = "second"
  private final val GranularityPropertyName = "granularity"
  private final val SecondName = "second"
  private final val MinuteName = "minute"
  private final val HourName = "hour"
  private final val DayName = "day"
  private final val MonthName = "month"
  private final val YearName = "year"
  private final val timestamp = Bucketer.getTimestamp(Some(TypeOp.Timestamp))

  def getSeconds(typeOperation: Option[TypeOp], default: TypeOp): BucketType =
    new BucketType(SecondName, typeOperation.orElse(Some(default)))

  def getMinutes(typeOperation: Option[TypeOp], default: TypeOp): BucketType =
    new BucketType(MinuteName, typeOperation.orElse(Some(default)))

  def getHours(typeOperation: Option[TypeOp], default: TypeOp): BucketType =
    new BucketType(HourName, typeOperation.orElse(Some(default)))

  def getDays(typeOperation: Option[TypeOp], default: TypeOp): BucketType =
    new BucketType(DayName, typeOperation.orElse(Some(default)))

  def getMonths(typeOperation: Option[TypeOp], default: TypeOp): BucketType =
    new BucketType(MonthName, typeOperation.orElse(Some(default)))

  def getYears(typeOperation: Option[TypeOp], default: TypeOp): BucketType =
    new BucketType(YearName, typeOperation.orElse(Some(default)))

  private def bucket(value: Date, bucketType: BucketType, properties: Map[String, JSerializable]): JSerializable = {
    DateOperations.dateFromGranularity(new DateTime(value), bucketType match {
      case t if t == timestamp => if (properties.contains(GranularityPropertyName))
        properties.get(GranularityPropertyName).get.toString
      else DefaultGranularity
      case _ => bucketType.id
    }).asInstanceOf[JSerializable]
  }
}
