package influxdb

import cats.kernel.{BoundedSemilattice, Semilattice}

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant, OffsetTime}
import scala.collection.immutable
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

// https://influxdata.github.io/iot-dev-guide/key-concepts.html#line-protocol
// environment,devId=b47f6944 Temp=21.00,Lat=50.087325,Lon=14.407154 1603091412
// +---------+ +------------+ +------------------------------------+ +--------+
// measurement tags           fields                                 timestamp
final case class LineProtocolMessage(
    measurement: String,
    tags: SortedMap[String, String],
    fields: Map[String, Double],
    timestamp: Instant
) {
  private def getNanos(timestamp: Instant): Long =
    timestamp.getEpochSecond * 1000000000L + timestamp.getNano

  def encode: String = {
    val encodedTags: String =
      if (tags.isEmpty) " "
      else tags.map { case (k, v) => s"$k=$v" }.mkString(start = ",", sep = ",", end = "")
    val encodedFields: String = fields.map { case (k, v) => s"$k=$v" }.mkString(",")

    s"$measurement$encodedTags$encodedFields ${getNanos(timestamp)}"
  }
}

final case class MessageBucket(v: Vector[LineProtocolMessage])
object MessageBucket {
  // TODO Maybe consider making a [[cats.kernel.Order]] for this
  implicit val evEq: cats.Eq[MessageBucket] =
    cats.Eq.fromUniversalEquals

  implicit val evBoundedSemilattice: BoundedSemilattice[MessageBucket] =
    new BoundedSemilattice[MessageBucket] {
      def empty: MessageBucket =
        MessageBucket(Vector.empty)
      def combine(x: MessageBucket, y: MessageBucket): MessageBucket =
        MessageBucket(
          Vector.concat(
            x.v
              .sortBy(x => (x.timestamp, x.measurement))
//              .distinctBy(x => (x.timestamp, x.measurement))
            ,
            y.v
              .sortBy(x => (x.timestamp, x.measurement))
//              .distinctBy(x => (x.timestamp, x.measurement))
          ).sortBy(x => (x.timestamp, x.measurement))
        )

    }
}
