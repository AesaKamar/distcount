package com.example

import cats.effect.IO
import fs2.{Chunk, Pull, Stream}
import fs2.io.file.Path

import scala.util.matching.Regex

class Rsplit extends munit.CatsEffectSuite {
  import scala.util.chaining._
  import Rsplit._

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

object Rsplit {
  def splitInclusive[F[_], O](
      t: Stream[F, O]
  )(f: O => Boolean): Stream[F, Chunk[O]] = {
    def go(buffer: Chunk[O], s: Stream[F, O]): Pull[F, Chunk[O], Unit] =
      s.pull.uncons.flatMap {
        case Some((hd, tl)) =>
          hd.indexWhere(f) match {
            case None => go(buffer ++ hd, tl)
            case Some(idx) =>
              val pfx = hd.take(idx)
              val b2  = buffer ++ pfx

              Pull.output1(b2) >> go(Chunk(hd.apply(idx)), tl.cons(hd.drop(idx + 1)))
          }
        case None =>
          if (buffer.nonEmpty) Pull.output1(buffer)
          else Pull.done
      }

    go(Chunk.empty, t).stream
  }
}
