package com.example

import cats.effect.{Clock, IO, Resource, SyncIO}
import com.avast.datadog4s._
import com.avast.datadog4s.api._
import com.avast.datadog4s.api.metric.{Count, Gauge}
import com.influxdb.{InfluxDBObserver, LineProtocolMessage}
import dev.kovstas.fs2throttler.Throttler
import fs2.Chunk
import fs2.io.file.Path
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
      .build[IO](
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
            .take(2)
            .through(
              Throttler.throttle(elements = 100, duration = 1.second, mode = Throttler.Shaping)
            )
            .through(influx.observeStreamThroughput(_)("largeFileRead"))
            //        .evalTap { (chunk: Chunk[Byte]) =>
            //          Clock[IO].realTime.flatMap { t =>
            //            val logline = (Instant.ofEpochMilli(t.toMillis), chunk.size)
            //            IO(pprint.log(logline))
            //          } *>
            //            stats.modify(chunk.size)
            //        }
            //        .through(fs2.text.utf8.decode)
            //        .through(fs2.text.lines)
            //        .pipe(Rsplit.splitInclusive(_)(chapterDelimitingRegex.matches))
            //        .evalTap(ch => IO(ch.take(5).pipe(pprint.log(_))))
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
