package com.example

import cats.effect.{ExitCode, IO, IOApp}
import com.avast.datadog4s.StatsDMetricFactory
import dev.kovstas.fs2throttler.Throttler
import fs2.io.file.Path
import influxdb.InfluxDBObserver

import scala.concurrent.duration.DurationInt

object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {

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
    runMapReduceWithObservations.map(_ => ExitCode.Success)
  }

}
