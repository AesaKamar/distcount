package com.example

import cats.effect.IO
import com.example.Resplit.splitInclusive
import fs2.{Chunk, Pull, Stream}
import fs2.io.file.Path

import scala.util.matching.Regex

class Rsplit extends munit.CatsEffectSuite {
  import scala.util.chaining._

  test("I should be able to read files") {
    for {
      _ <- IO.unit
      _ <- IO(pprint.log("hey there!"))
      regex: Regex = "dog\\d".r
      s <- fs2.io.file
        .Files[IO]
        .readAll(Path("testfile"))
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .pipe(splitInclusive(_)(regex.matches))
        .evalTap(s => IO(pprint.log(s)))
        .compile
        .drain

    } yield {
      assert(true)
    }

  }
}


