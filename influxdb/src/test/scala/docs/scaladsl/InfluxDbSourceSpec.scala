/*
 * Copyright (C) since 2016 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.scaladsl

import akka.actor.ActorSystem
import akka.stream.alpakka.influxdb.InfluxDbReadSettings
import akka.stream.alpakka.influxdb.scaladsl.InfluxDbSource
import akka.stream.alpakka.testkit.scaladsl.LogCapturing
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import org.influxdb.{InfluxDB, InfluxDBException}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import docs.javadsl.TestUtils.{cleanDatabase, populateDatabase}
import org.influxdb.dto.Query
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class InfluxDbSourceSpec
    extends TestKit(ActorSystem("InfluxDbSourceSpec"))
    with AnyWordSpecLike
    with InfluxDbTest
    with Matchers
    with BeforeAndAfterEach
    with ScalaFutures
    with LogCapturing {

  final val DatabaseName = "InfluxDbSourceSpec"

  implicit var influxDB: InfluxDB = _

  override def afterStart(): Unit = {
    influxDB = setupConnection(DatabaseName)
    super.afterStart()
  }

  override def beforeEach(): Unit =
    populateDatabase(influxDB, classOf[InfluxDbSourceCpu])

  override def afterEach(): Unit =
    cleanDatabase(influxDB, DatabaseName)

  "support source" in assertAllStagesStopped {
    // #run-typed
    val query = new Query("SELECT * FROM cpu", DatabaseName);

    val influxDBResult = InfluxDbSource(influxDB, query).runWith(Sink.seq)
    val resultToAssert = influxDBResult.futureValue.head

    val values = resultToAssert.getResults.get(0).getSeries().get(0).getValues

    values.size() mustBe 2
  }

  "exception on source" in assertAllStagesStopped {
    val query = new Query("SELECT man() FROM invalid", DatabaseName);

    val result = InfluxDbSource(influxDB, query) //.runWith(Sink.seq)
      .recover {
        case e: InfluxDBException => e.getMessage
      }
      .runWith(Sink.seq)
      .futureValue

    result mustBe Seq("undefined function man()")
  }

  "partial error in query" in assertAllStagesStopped {
    val query = new Query("SELECT*FROM cpu; SELECT man() FROM invalid", DatabaseName);

    val influxDBResult = InfluxDbSource(influxDB, query).runWith(Sink.seq)
    val resultToAssert = influxDBResult.futureValue.head

    val valuesFetched = resultToAssert.getResults.get(0).getSeries().get(0).getValues
    valuesFetched.size() mustBe 2

    val error = resultToAssert.getResults.get(1).getError
    error mustBe "undefined function man()"
  }

  "exception on typed source" in assertAllStagesStopped {
    val query = new Query("SELECT man() FROM invalid", DatabaseName);

    val result = InfluxDbSource
      .typed(classOf[InfluxDbSourceCpu], InfluxDbReadSettings.Default, influxDB, query) //.runWith(Sink.seq)
      .recover {
        case e: InfluxDBException => e.getMessage
      }
      .runWith(Sink.seq)
      .futureValue

    result mustBe Seq("undefined function man()")
  }

  "mixed exception on typed source" in assertAllStagesStopped {
    val query = new Query("SELECT*FROM cpu;SELECT man() FROM invalid; SELECT*FROM cpu;", DatabaseName);

    val result = InfluxDbSource
      .typed(classOf[InfluxDbSourceCpu], InfluxDbReadSettings.Default, influxDB, query) //.runWith(Sink.seq)
      .recover {
        case e: InfluxDBException => e.getMessage
      }
      .runWith(Sink.seq)
      .futureValue

    val firstResult = result(0).asInstanceOf[InfluxDbSourceCpu]
    firstResult.getHostname mustBe "local_1"

    val error = result(1).asInstanceOf[String]
    error mustBe "not executed"
  }

}
