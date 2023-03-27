package com.example

import cats.effect.{ExitCode, IO, IOApp}
import com.avast.datadog4s.StatsDMetricFactory
import dev.kovstas.fs2throttler.Throttler
import fs2.io.file.Path
import influxdb.InfluxDBObserver

import scala.concurrent.duration.DurationInt

object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    import scala.util.chaining._
    import cats.syntax.monoid._
    import cats.syntax.foldable._

    def runMapReduceWithObservations: IO[Unit] = {

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
              .through(
                influx.observeStreamThroughput(_)("largeFileRead")
              )
              .unchunks
              .concurrently(influx.startPublishing)
              .through(fs2.text.utf8.decode)
              .through(fs2.text.lines)
              .through(s =>
                Resplit.splitInclusive(s)(thisLine => chapterDelimitingRegex.matches(thisLine))
              )
              .map { s =>
                s
                  // Could do this or .filter(_.isEmpty) ish
                  .map { line => line.split("\\s+").toVector.pipe(toOccurrenceMap) }
                  .toVector
                  .foldl(Map.empty[String, Long])((acc, i) => acc.combine(i))
              // How to combine all of these into 1 Map[String, Long]
              }
              .evalTap(m => IO(pprint.log(m.maxBy(_._2))))
              .compile
              .foldMonoid
              .*>(
                IO
                  .sleep(1.second)
                  .*>(IO(pprint.log("😴")))
              )
//              .replicateA(3)
              .map(_ => ())

          } yield ()
        }
    }
    runMapReduceWithObservations.map(_ => ExitCode.Success)
  }

  def toOccurrenceMap(wordsInLine: Vector[String]): Map[String, Long] =
    wordsInLine
      .groupBy(key => key)
      .view
      .mapValues(_.size.toLong)
      .toMap

}
