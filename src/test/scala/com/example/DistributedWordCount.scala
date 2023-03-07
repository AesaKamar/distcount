package com.example

import cats.effect.{Clock, IO, Resource, SyncIO}
import com.avast.datadog4s._
import com.avast.datadog4s.api._
import com.avast.datadog4s.api.metric.{Count, Gauge}
import dev.kovstas.fs2throttler.Throttler
import fs2.Chunk
import fs2.io.file.Path
import munit.CatsEffectSuite

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

    (for {

      g <- fs2.io.file
        .Files[IO]
        .readAll(Path("largefile.txt"))
        .chunkN(1024)
        .through(Throttler.throttle(elements = 100, duration = 1.second, mode = Throttler.Shaping))
        .evalTap { (chunk: Chunk[Byte]) =>
          Clock[IO].realTime.flatMap { t =>
            val logline = (Instant.ofEpochMilli(t.toMillis), chunk.size)
            IO(pprint.log(logline))
          } *>
            stats.modify(chunk.size)
        }
//        .through(fs2.text.utf8.decode)
//        .through(fs2.text.lines)
//        .pipe(Rsplit.splitInclusive(_)(chapterDelimitingRegex.matches))
//        .evalTap(ch => IO(ch.take(5).pipe(pprint.log(_))))
        .compile
        .drain

      _ <- IO
        .sleep(1.second)
        .*>(IO(pprint.log("😴")))

    } yield ())
      .replicateA(3)
      .map(_ => ())

  }

  test("test hello world says hi") {
    mkResource.use { r =>
      runMapReduceWithObservations(r)
    }
  }
}
