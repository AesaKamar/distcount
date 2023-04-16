//package paxos
//
//import cats.effect._
//import cats.implicits._
//import org.http4s._
//import org.http4s.dsl.io._
//import org.http4s.implicits._
//import org.http4s.server.blaze.BlazeServerBuilder
//import org.http4s.circe._
//import io.circe.generic.auto._
//
//import scala.collection.concurrent.TrieMap
//
//case class SetClusterSize(desiredSize: Int)
//
//object PaxosControlPlane {
//  val nodeIdCounter: Ref[IO, Int] = Ref.unsafe(0)
//  val cluster: TrieMap[String, PaxosNodeImpl[IO]] = TrieMap.empty
//
//  def createNode: IO[PaxosNodeImpl[IO]] =
//    nodeIdCounter.modify { counter =>
//      val nodeId = s"N${counter + 1}"
//      (counter + 1, new PaxosNodeImpl[IO](nodeId, Nil))
//    }
//
//  val setClusterSize: HttpRoutes[IO] = HttpRoutes.of[IO] {
//    case req @ POST -> Root / "set-cluster-size" =>
//      for {
//        setSize <- req.as[SetClusterSize]
//        currentSize <- nodeIdCounter.get
//        _ <-
//          if (setSize.desiredSize > currentSize)
//            List.fill(setSize.desiredSize - currentSize)(createNode)
//              .sequence
//              .map(newNodes => cluster ++= newNodes.map(node => node.nodeId -> node).toSeq)
//              .void
//          else
//            cluster.keys.toList
//              .take(currentSize - setSize.desiredSize)
//              .traverse(cluster.remove)
//              .void
//        response <- Ok()
//      } yield response
//  }
//
//  val apiService: HttpApp[IO] = setClusterSize.orNotFound
//
//  def main(args: Array[String]): Unit = {
//    val server = BlazeServerBuilder[IO]
//      .bindHttp(8080, "localhost")
//      .withHttpApp(apiService)
//      .resource
//      .use(_ => IO.never)
//      .as(ExitCode.Success)
//
//    server.unsafeRunSync()
//  }
//}
