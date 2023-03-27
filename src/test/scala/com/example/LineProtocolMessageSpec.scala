package com.example

import cats.kernel.laws.discipline.{BoundedSemilatticeTests, SemilatticeTests}
import org.typelevel.discipline.Laws
import cats.syntax.all._
import cats.laws.discipline.FunctorTests
import influxdb.{LineProtocolMessage, MessageBucket}
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Gen}

import scala.collection.immutable.SortedMap

class LineProtocolMessageSpec extends munit.DisciplineSuite {

  implicit val thing : org.scalacheck.Arbitrary[influxdb.MessageBucket] = {
    val gen = for {
      lowCardinalityMeasurements <- Gen.oneOf("cpu", "mem", "disk", "net", "system")
      tags <- Gen.mapOfN(3, Gen.alphaNumStr).map(SortedMap.from )
      genLineProtocolMessage <-
      thing <- Gen.listOf()
    } yield (thing)
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
  checkAll("LineProtocolMessage",
    lawsForBoundedSemilattice.semigroup)
}
