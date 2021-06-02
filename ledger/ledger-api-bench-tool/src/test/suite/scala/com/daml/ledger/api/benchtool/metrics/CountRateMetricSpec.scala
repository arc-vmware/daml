// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.benchtool

import com.daml.ledger.api.benchtool.metrics.CountRateMetric
import com.daml.ledger.api.benchtool.metrics.CountRateMetric.Value
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Duration

class CountRateMetricSpec extends AnyWordSpec with Matchers {
  CountRateMetric.getClass.getSimpleName should {
    "correctly handle initial state" in {
      val periodDuration: Duration = Duration.ofMillis(100)
      val totalDuration: Duration = Duration.ofSeconds(1)
      val metric: CountRateMetric[String] = anEmptyStringMetric()

      val (_, periodicValue) = metric.periodicValue(periodDuration)
      val finalValue = metric.finalValue(totalDuration)

      periodicValue shouldBe Value(0.0)
      finalValue shouldBe Value(0.0)
    }

    "compute values after processing elements" in {
      val periodDuration: Duration = Duration.ofMillis(100)
      val totalDuration: Duration = Duration.ofSeconds(5)
      val metric: CountRateMetric[String] = anEmptyStringMetric()
      val elem1: String = "abc"
      val elem2: String = "defg"

      val (newMetric, periodicValue) = metric
        .onNext(elem1)
        .onNext(elem2)
        .periodicValue(periodDuration)
      val finalValue = newMetric.finalValue(totalDuration)

      val totalCount: Int = stringLength(elem1) + stringLength(elem2)
      periodicValue shouldBe Value(
        ratePerSecond = totalCount * 1000.0 / periodDuration.toMillis
      )
      finalValue shouldBe Value(
        ratePerSecond = totalCount / totalDuration.getSeconds.toDouble
      )
    }

    "correctly handle periods with no elements" in {
      val periodDuration: Duration = Duration.ofMillis(100)
      val totalDuration: Duration = Duration.ofSeconds(5)
      val metric: CountRateMetric[String] = anEmptyStringMetric()
      val elem1: String = "abc"
      val elem2: String = "defg"

      val (newMetric, periodicValue) = metric
        .onNext(elem1)
        .onNext(elem2)
        .periodicValue(periodDuration)
        ._1
        .periodicValue(periodDuration)
      val finalValue = newMetric.finalValue(totalDuration)

      val totalCount: Int = stringLength(elem1) + stringLength(elem2)
      periodicValue shouldBe Value(
        ratePerSecond = 0.0
      )
      finalValue shouldBe Value(
        ratePerSecond = totalCount / totalDuration.getSeconds.toDouble
      )
    }

    "correctly handle multiple periods with elements" in {
      val periodDuration: Duration = Duration.ofMillis(100)
      val totalDuration: Duration = Duration.ofSeconds(5)
      val metric: CountRateMetric[String] = anEmptyStringMetric()
      val elem1: String = "abc"
      val elem2: String = "defg"
      val elem3: String = "hij"

      val (newMetric, periodicValue) = metric
        .onNext(elem1)
        .onNext(elem2)
        .periodicValue(periodDuration)
        ._1
        .onNext(elem3)
        .periodicValue(periodDuration)
      val finalValue = newMetric.finalValue(totalDuration)

      val totalCount: Int = stringLength(elem1) + stringLength(elem2) + stringLength(elem3)
      periodicValue shouldBe Value(
        ratePerSecond = stringLength(elem3) * 1000.0 / periodDuration.toMillis
      )
      finalValue shouldBe Value(
        ratePerSecond = totalCount / totalDuration.getSeconds.toDouble
      )
    }
  }

  private def stringLength(value: String): Int = value.length
  private def anEmptyStringMetric(): CountRateMetric[String] =
    CountRateMetric.empty[String](countingFunction = stringLength)
}
