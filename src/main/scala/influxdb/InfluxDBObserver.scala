package influxdb


import cats.effect.{Async, Clock, IO, Resource}
import fs2.Chunk
import fs2.concurrent.Channel
import org.http4s.Uri.{Authority, RegName}
import org.http4s.headers.MediaRangeAndQValue
import org.http4s._
import org.http4s.client.Client
import org.typelevel.ci.CIStringSyntax
import scodec.bits.ByteVector

import scala.collection.immutable.SortedMap

final case class InfluxDBConfig(
)

trait InfluxDBObserver[F[_]] {
  def startPublishing: fs2.Stream[F, Unit]

  def observeStreamThroughput(
      s: fs2.Stream[F, Chunk[Byte]]
  )(name: String): fs2.Stream[F, Chunk[Byte]]

}

object InfluxDBObserver {
  import cats.syntax.all._
  import org.http4s.implicits

  def build(
      influxDBUri: String,
      influxDBAPIToken: String
  ): Resource[IO, InfluxDBObserver[IO]] = {
    val httpClient: Resource[IO, Client[IO]] = org.http4s.ember.client.EmberClientBuilder
      .default[IO]
      .build
    val channel: Resource[IO, Channel[IO, LineProtocolMessage]] =
      Resource.eval(fs2.concurrent.Channel.unbounded[IO, LineProtocolMessage])

    (httpClient, channel).mapN { case (client, channel) =>
      new DefaultInfluxDBObserver(client, channel, influxDBUri, influxDBAPIToken, Clock[IO])
    }
  }

}

class DefaultInfluxDBObserver(
    httpClient: org.http4s.client.Client[IO],
    channel: Channel[IO, LineProtocolMessage],
    influxDBUri: String,
    influxDBAPIToken: String,
    clock: Clock[IO]
) extends InfluxDBObserver[IO] {
  import scala.util.chaining._

  def startPublishing: fs2.Stream[IO, Unit] = {
    channel.stream
      .chunkN(100)
      .evalMap { c =>
        sendLineProtocolMessageToInfluxDb(c)
      }
  }

  def observeStreamThroughput(
      s: fs2.Stream[IO, Chunk[Byte]]
  )(name: String): fs2.Stream[IO, Chunk[Byte]] =
    s.evalTapChunk { ch =>
      clock.realTimeInstant
        .map(ts =>
          LineProtocolMessage(
            measurement = name,
            tags = SortedMap.empty,
            fields = Map("throughputBytes" -> ch.size.toDouble),
            timestamp = ts
          )
        )
        .flatMap(channel.send)
    }

  // curl --request POST \
  // "http://localhost:8086/api/v2/write?org=YOUR_ORG&bucket=YOUR_BUCKET&precision=ns" \
  //  --header "Authorization: Token YOUR_API_TOKEN" \
  //  --header "Content-Type: text/plain; charset=utf-8" \
  //  --header "Accept: application/json" \
  //  --data-binary '
  //    airSensors,sensor_id=TLM0201 temperature=73.97038159354763,humidity=35.23103248356096,co=0.48445310567793615 1630424257000000000
  //    airSensors,sensor_id=TLM0202 temperature=75.30007505999716,humidity=35.651929918691714,co=0.5141876544505826 1630424257000000000
  //    '
  def sendLineProtocolMessageToInfluxDb(
      lineProtocolMessages: Chunk[LineProtocolMessage]
  ): IO[Unit] = {

    val uriDataStruct = Uri(
      scheme = Some(value = Uri.Scheme.http),
      authority = Some(
        value = Authority(
          userInfo = None,
          host = RegName(host = ci"localhost"),
          port = Some(value = 8086)
        )
      ),
      path =
        Uri.Path(Vector(Uri.Path.Segment("api"), Uri.Path.Segment("v2"), Uri.Path.Segment("api"))),
      query =
        Query("org" -> Some("aesakamar"), "bucket" -> Some("distcount"), "precision" -> Some("ns")),
      fragment = None
    )

    val uri = Uri.unsafeFromString(
      influxDBUri.appendedAll("/api/v2/write?org=aesakamar&bucket=distcount&precision=ns")
    )

    val request: Request[IO] = Request[IO](
      method = Method.POST,
      // TODO Refactor this to use InfluxDBConfig
      uri = uri,
      headers = Headers.apply(
        org.http4s.headers
          .Authorization(Credentials.Token(AuthScheme.Bearer, influxDBAPIToken)),
        org.http4s.headers.`Content-Type`(mediaType = MediaType.text.plain, Charset.`UTF-8`),
        org.http4s.headers.Accept(MediaRangeAndQValue.withDefaultQValue(MediaRange.`application/*`))
      ),
      entity = lineProtocolMessages
        .map(_.encode)
        .toVector
        .mkString("\n")
        .getBytes
        .pipe(ByteVector.apply)
        .pipe(Entity.Strict)
    )
    for {
      res <- httpClient
        .status(request)
//        TODO Figure out why this is necessary with the IORuntime from CatsEffectSuite
//        .startOn(IORuntime.global.compute)

    } yield {
      ()
    }

  }

}
