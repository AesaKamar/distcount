package com.example

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO, Resource, SyncIO}
import com.avast.datadog4s._
import com.avast.datadog4s.api._
import com.avast.datadog4s.api.metric.{Count, Gauge}
import dev.kovstas.fs2throttler.Throttler
import fs2.Chunk
import fs2.io.file.Path
import influxdb.{InfluxDBObserver, LineProtocolMessage}
import munit.CatsEffectSuite
import org.http4s.client.Client
import scodec.Codec.deriveCoproduct

import scala.concurrent.duration._
import java.net.InetSocketAddress
import java.time.Instant

class DistributedWordCount extends CatsEffectSuite {
  import scala.util.chaining._
  import cats.syntax.applicative._

  def mkResource: Resource[IO, MetricFactory[IO]] = {
    StatsDMetricFactory.make(
      StatsDMetricFactoryConfig(
        basePrefix = None,
        statsDServer = InetSocketAddress.createUnresolved("localhost", 8125)
      )
    )
  }

  def runMapReduceWithObservations(metricFactory: MetricFactory[IO]): IO[Unit] = {

    val stats: Count[IO] = metricFactory.count("distcount.kb_scanned_from_files", Some(1.0))

    val chapterDelimitingRegex = "^[MDCLXVI]+.*".r

    InfluxDBObserver
      .build(
        influxDBUri = "http://localhost:8086",
        influxDBAPIToken =
          "pRLrxxMc8viQP96vSSAJEcmwbdhUldtSijX9dGT2-IUfJLHdPa1IGbwrzkGdvgoWYLzWN-b0l7Yf3mCplESThQ=="
      )
      .use { influx =>
        for {

          g <- fs2.io.file
            .Files[IO]
            .readAll(Path("largefile.txt"))
            .chunkN(1024)
            .through(
              Throttler.throttle(elements = 100, duration = 1.second, mode = Throttler.Shaping)
            )
            .through(influx.observeStreamThroughput(_)("largeFileRead"))
            .concurrently(influx.startPublishing)
            .compile
            .drain
            .*>(
              IO
                .sleep(1.second)
                .*>(IO(pprint.log("😴")))
            )
            .replicateA(3)
            .map(_ => ())

        } yield ()
      }
  }

  test("test hello world says hi") {
    mkResource.use { r =>
      runMapReduceWithObservations(r)
    }
  }
}
