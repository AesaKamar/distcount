package com.example

import cats.Semigroup
import cats.kernel.laws.discipline.{BoundedSemilatticeTests, SemilatticeTests}
import org.typelevel.discipline.Laws
import cats.syntax.all._
import cats.laws.discipline.FunctorTests
import influxdb.{LineProtocolMessage, MessageBucket}
import munit.{DisciplineSuite, Location, ScalaCheckSuite, TestOptions}
import org.scalacheck.{Arbitrary, Gen}

import java.time.Instant
import scala.collection.immutable.SortedMap

class MessageBucketLawTests extends munit.DisciplineSuite {

  implicit val thing: org.scalacheck.Arbitrary[influxdb.MessageBucket] = {

    def genTupleE[A, B](a: Gen[A], b: Gen[B]): Gen[(A, B)] = for {
      a <- a
      b <- b
    } yield (a, b)

    val genLineProtocolMessage: Gen[LineProtocolMessage] = for {
      measurement <- Gen.alphaNumStr
      tags        <- Gen.mapOf(genTupleE(Gen.alphaNumStr, Gen.alphaNumStr)).map(SortedMap.from(_))
      fields      <- Gen.mapOf(genTupleE(Gen.alphaNumStr, Gen.choose(0.0, 100.0)))
      time <- genTupleE(Gen.choose(0L, 32503701600L), Gen.choose(0L, 999999999L))
        .map { case (s, n) => Instant.ofEpochSecond(s, n) }
    } yield LineProtocolMessage(measurement, tags, fields, time)

    val genMessageBucket: Gen[MessageBucket] = for {

      lineProtocolMessages <- Gen
        .listOf[LineProtocolMessage](genLineProtocolMessage)
        .map(_.toVector)

    } yield MessageBucket(lineProtocolMessages)

    Arbitrary(genMessageBucket)
  }

  /*
   * Implement Cats Laws checking from [[cats.discipline]]
   * -----------------------------------------------------
   */
  val lawsForBoundedSemilattice: BoundedSemilatticeTests[MessageBucket] =
    BoundedSemilatticeTests[MessageBucket]

//  val ruleset = new Laws {
//    def all = Seq(
//      catsLawsFunctorForLineProtocolMessage
//    )
//  }
//
  checkAll("MessageBucket", lawsForBoundedSemilattice.semigroup)

}

class MessageBucketManualTests extends munit.FunSuite {
  def isAcssociative[A](x: A, y: A, z: A)(implicit S: Semigroup[A]): Boolean =
    S.combine(S.combine(x, y), z) == S.combine(x, S.combine(y, z))

  test("associativity on Sampled Scalacheck value  ") {
    val thing =
      """
    |Expected: MessageBucket(Vector(LineProtocolMessage("lOF",TreeMap(),Map(),Instant.parse("2168-11-29T21:34:00.117808008Z")), LineProtocolMessage("Tu",TreeMap("" -> "ng9", zD -> ),Map(dhD4 -> 13.788176240685225),2537-08-06T04:44:44.060946974Z), LineProtocolMessage(II1,TreeMap(6I -> vVx, 975 -> vir, R8 -> ),Map(R5D -> 73.58447310159417, 4 -> 19.143819788510875),2558-10-15T04:48:06.863107607Z), LineProtocolMessage(lOF,TreeMap(),Map(),2168-11-29T21:34:00.117808008Z), LineProtocolMessage(Tu,TreeMap( -> ng9, zD -> ),Map(dhD4 -> 13.788176240685225),2537-08-06T04:44:44.060946974Z), LineProtocolMessage(II1,TreeMap(6I -> vVx, 975 -> vir, R8 -> ),Map(R5D -> 73.58447310159417, 4 -> 19.143819788510875),2558-10-15T04:48:06.863107607Z)))
    |Received: MessageBucket(Vector(LineProtocolMessage("II1",TreeMap(6I -> vVx, 975 -> vir, R8 -> ),Map(R5D -> 73.58447310159417, 4 -> 19.143819788510875),2558-10-15T04:48:06.863107607Z), LineProtocolMessage(Tu,TreeMap( -> ng9, zD -> ),Map(dhD4 -> 13.788176240685225),2537-08-06T04:44:44.060946974Z), LineProtocolMessage(lOF,TreeMap(),Map(),2168-11-29T21:34:00.117808008Z)))
    |>  ARG_0: MessageBucket(Vector(LineProtocolMessage("II1",TreeMap(6I -> vVx, 975 -> vir, R8 -> ),Map(R5D -> 73.58447310159417, 4 -> 19.143819788510875),2558-10-15T04:48:06.863107607Z), LineProtocolMessage(Tu,TreeMap( -> ng9, zD -> ),Map(dhD4 -> 13.788176240685225),2537-08-06T04:44:44.060946974Z), LineProtocolMessage(lOF,TreeMap(),Map(),2168-11-29T21:34:00.117808008Z)))
    |""".stripMargin
//    val thing =
      assert(
        isAcssociative(
          MessageBucket(Vector.empty),
          MessageBucket(Vector.empty),
          MessageBucket(Vector.empty)
        )
      )
  }
}

trait PrettyDisciplineSuite extends ScalaCheckSuite {

  def checkAll(name: String, ruleSet: Laws#RuleSet)(implicit
                                                    loc:             Location
  ): Unit = checkAll(new TestOptions(name, Set.empty, loc), ruleSet)

  def checkAll(options: TestOptions, ruleSet: Laws#RuleSet)(implicit
                                                            loc:                Location
  ): Unit =
    ruleSet.all.properties.toList.foreach { case (id, prop) =>
      property(options.withName(s"${options.name}: $id")) {
        prop
      }
    }

}
