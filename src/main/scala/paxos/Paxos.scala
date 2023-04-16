package paxos

import cats.effect._
import cats.effect.syntax.all._
import cats.kernel.{BoundedEnumerable, Order}
import cats.syntax.all._
import fs2.concurrent.SignallingRef
import paxos.NodeId.ordering
import paxos.PrepareId.ordering

import scala.collection.{SortedSet, immutable}
import scala.collection.immutable.SortedMap
import scala.util.chaining.scalaUtilChainingOps

// Simple VectorClock data type
case class VectorClock(prepareIdWaterMarks: SortedMap[NodeId, PrepareId]) {
  def increment(node: NodeId): VectorClock = {
    val highWaterMark: PrepareId = prepareIdWaterMarks
      .getOrElse(node, BoundedEnumerable[PrepareId].minBound)
      .pipe(BoundedEnumerable[PrepareId].cycleNext)
    VectorClock(prepareIdWaterMarks.updated(node, highWaterMark))
  }

  def merge(other: VectorClock): VectorClock = {
    val mergedHighWaterMarks: immutable.SortedSet[(NodeId, PrepareId)] = {
      prepareIdWaterMarks.keySet
        .union(other.prepareIdWaterMarks.keySet)
        .map { nodeId =>
          nodeId ->
            Order[PrepareId].max(
              prepareIdWaterMarks
                .getOrElse(nodeId, BoundedEnumerable[PrepareId].minBound),
              other.prepareIdWaterMarks
                .getOrElse(nodeId, BoundedEnumerable[PrepareId].minBound)
            )
        }
    }
    VectorClock(SortedMap.from(mergedHighWaterMarks))
  }
}

// Newtype wrappers for Node IDs
final case class NodeId(value: Int) extends AnyVal
object NodeId {
  implicit val ordering: Ordering[NodeId] = Ordering.by[NodeId, Int](_.value)
}
final case class ProposerId(value: NodeId)
final case class AcceptorId(value: NodeId)

// Newtype wrappers for message IDs
final case class PrepareId(value: Long) extends AnyVal
object PrepareId {
  implicit val evOrder: Order[PrepareId]     = Order.by[PrepareId, Long](_.value)
  implicit val ordering: Ordering[PrepareId] = evOrder.toOrdering
  implicit val evBounded: BoundedEnumerable[PrepareId] = new BoundedEnumerable[PrepareId] {
    override def order: Order[PrepareId] = evOrder
    override def partialPrevious(a: PrepareId): Option[PrepareId] =
      if (a.value == 0) None
      else PrepareId(a.value - 1).some

    override def partialNext(a: PrepareId): Option[PrepareId] =
      if (a.value == Long.MaxValue) None
      else PrepareId(a.value + 1).some
    override def minBound: PrepareId = PrepareId(0)
    override def maxBound: PrepareId = PrepareId(Long.MaxValue)
  }
}

sealed trait PaxosMessage

// Messages from Proposer to Acceptor
final case class Prepare(proposerId: ProposerId, prepareId: PrepareId, value: String)
    extends PaxosMessage
final case class Accept(proposerId: ProposerId, prepareId: PrepareId, value: String)
    extends PaxosMessage

// Messages from Acceptor to Proposer
final case class Promise(acceptorId: AcceptorId, prepareId: PrepareId, value: String)
    extends PaxosMessage
final case class Accepted(acceptorId: AcceptorId, prepareId: PrepareId, value: String)
    extends PaxosMessage

// Messages from Proposer to Learner
final case class Learn(proposerId: ProposerId, value: String) extends PaxosMessage

trait PaxosNode[F[_]] {
  val nodeId: NodeId
  val transportLayerBinding: MessageTransport[F]
  def receive(message: PaxosMessage): F[Unit]
}

// TODO use johnynek's approach for a Concurrent Ref Map https://gist.github.com/johnynek/a8b1a70ebbfaac2836068f8ba3087a72
// TODO   This will remove the redundancy of calling _.update(_.updated(...))
object Paxos {
  import cats.effect.unsafe.implicits.global

  def main(args: Array[String]): Unit = {
    val mkNodes: IO[List[PaxosNodeImpl[IO]]] = 1
      .to(3)
      .toList
      .traverse(nodeId => PaxosNodeImpl.apply[IO](NodeId(nodeId), Nil, MessageTransportLocal))

    for {
      nodes <- mkNodes
      _ <- nodes.traverse_ { node =>
        val otherAcceptors: Set[AcceptorId] = nodes
          .map(_.nodeId)
          .filterNot(_ == node.nodeId)
          .map(AcceptorId)
          .toSet
        node.knownAcceptors
          .set(otherAcceptors)
      }
      _ <- nodes.head.propose("42")
      // Simulate adding a new node to the cluster
      a <- SignallingRef.of[IO, List[PaxosNode[IO]]](List.empty[PaxosNodeImpl[IO]])
      // TODO have the control plane manage cluster membership
      newNode = new PaxosNodeImpl[IO](NodeId(4), a, MessageTransportLocal)
      _ <- newNode.otherNodesRef.set(nodes)
      _ <- nodes.traverse_(node => node.addNode(newNode))
      _ <- newNode.propose("43")
    } yield ()
  }.unsafeRunSync()
}

// TODO quorum fraction-based
// TODO use a Vault as a persistent version of Ref https://typelevel.org/vault/
trait Learner[F[_]] extends PaxosNode[F] {
  implicit val evAsync: Async[F]

  val learnerId: NodeId
  protected val acceptorsSize: Int
  protected val acceptedCount: Ref[F, Map[PrepareId, Int]]
  protected val learnedValue: Ref[F, Option[String]]

  override def receive(message: PaxosMessage): F[Unit] = message match {
    case Accepted(acceptorId, prepareId, value) =>
      for {
        _ <- acceptedCount.update(_.updatedWith(prepareId)(_.map(_ + 1).orElse(Some(1))))
        currentAcceptedCount <- acceptedCount.get
        currentValue         <- learnedValue.get
        _ <-
          if (currentValue.isEmpty && currentAcceptedCount(prepareId) > acceptorsSize / 2)
            learnedValue.set(Some(value)) *> Async[F].delay(
              println(s"Learner $learnerId has learned the value: $value")
            )
          else
            Sync[F].unit
      } yield ()

    case _ => Sync[F].unit
  }
}

// TODO use a Vault as a persistent version of Ref https://typelevel.org/vault/
trait Proposer[F[_]] extends PaxosNode[F] {

  implicit val evAsync: Async[F]

  val proposerId: ProposerId
  val initialAcceptors: List[PaxosNode[F]]

  protected val prepareIdCounter: Ref[F, PrepareId]
  protected val promisesReceivedByAcceptors: Ref[F, Map[PrepareId, Set[AcceptorId]]]
  protected val acceptedReceivedByAcceptors: Ref[F, Map[PrepareId, Set[AcceptorId]]]
  protected val knownAcceptors: Ref[F, Set[AcceptorId]]

  def propose(value: String): F[Unit] =
    for {
      currentPrepareId <- prepareIdCounter.getAndUpdate(BoundedEnumerable[PrepareId].cycleNext)
      currentAcceptors <- knownAcceptors.get
      _ <- currentAcceptors.toList.traverse(acceptor =>
        transportLayerBinding.send(acceptor.value, Prepare(proposerId, currentPrepareId, value))
      )
      _ <- promisesReceivedByAcceptors.update(_.updated(currentPrepareId, Set.empty))
    } yield ()

  override def receive(message: PaxosMessage): F[Unit] = message match {
    case Promise(acceptorId, prepareIdReceived, value) =>
      for {
        _ <- promisesReceivedByAcceptors.update(
          _.updatedWith(prepareIdReceived)(_.map(_.incl(acceptorId)))
        )
        currentPromisesReceived <- promisesReceivedByAcceptors.get
        currentAcceptedReceived <- acceptedReceivedByAcceptors.get
        currentAcceptors        <- knownAcceptors.get
        _ <-
          if (
            currentPromisesReceived(prepareIdReceived).size > currentAcceptors.size / 2 &&
            !currentAcceptedReceived.contains(prepareIdReceived)
          ) {
            currentAcceptors.toList.traverse(acceptor =>
              transportLayerBinding.send(
                acceptor.value,
                Accept(proposerId, prepareIdReceived, value)
              )
            )
          } else {
            Sync[F].unit
          }
      } yield ()

    case Accepted(acceptorId, prepareIdReceived, value) =>
      for {
        _ <- acceptedReceivedByAcceptors.update(
          _.updatedWith(prepareIdReceived)(_.map(_.incl(acceptorId)))
        )
        currentAcceptedReceived <- acceptedReceivedByAcceptors.get
        currentAcceptors        <- knownAcceptors.get
        _ <-
          if (currentAcceptedReceived(prepareIdReceived).size > currentAcceptors.size / 2)
            currentAcceptors.toList.traverse(acceptor =>
              transportLayerBinding.send(acceptor.value, Learn(proposerId, value))
            )
          else
            Sync[F].unit
      } yield ()

    case _ => Sync[F].unit
  }
}

// TODO use a Vault as a persistent version of Ref https://typelevel.org/vault/
trait Acceptor[F[_]] extends PaxosNode[F] {
  implicit val evAsync: Async[F]

  protected val acceptorId: AcceptorId

  protected val highestPrepareId: Ref[F, Option[PrepareId]]
  protected val knownAcceptedValues: Ref[F, Map[PrepareId, String]]

  override def receive(message: PaxosMessage): F[Unit] = message match {
    case Prepare(proposerId, prepareId, value) =>
      for {
        currentHighestPrepareId <- highestPrepareId.get
        response <-
          if (currentHighestPrepareId.forall(_.value < prepareId.value))
            highestPrepareId
              .set(Some(prepareId))
              .productR(Promise(acceptorId = acceptorId, prepareId, value).some.pure)
          else
            None.pure
        _ <- response.traverse(transportLayerBinding.send(proposerId.value, _))
      } yield ()

    case Accept(proposerId, prepareId, value) =>
      for {
        currentHighestPrepareId <- highestPrepareId.get
        response <-
          if (currentHighestPrepareId.contains(prepareId))
            knownAcceptedValues
              .update(_.updated(prepareId, value))
              .productR(
                Accepted(
                  acceptorId = acceptorId,
                  prepareId = prepareId,
                  value = value
                ).some.pure
              )
          else
            None.pure
        _ <- response.traverse(transportLayerBinding.send(proposerId.value, _))
      } yield ()

    case _ => Sync[F].unit
  }

}

trait MessageTransport[F[_]] {
  def send(receiverId: NodeId, message: PaxosMessage): F[Unit]
}
// TODO use a concurrent map to address all of the node IDs and dispatch the messages to their recieve functions
object MessageTransportLocal extends MessageTransport[IO] {
  def send(receiverId: NodeId, message: PaxosMessage): IO[Unit] = ???
}

object PaxosNodeImpl {
  def apply[F[_]: Async](
      nodeId: NodeId,
      initialOtherNodes: List[PaxosNode[F]],
      transportLayerBinding: MessageTransport[F]
  ): F[PaxosNodeImpl[F]] =
    SignallingRef.of(initialOtherNodes).map { otherNodesRef =>
      new PaxosNodeImpl[F](nodeId, otherNodesRef, transportLayerBinding)
    }
}

// TODO implement the transport layer
class PaxosNodeImpl[F[_]: Async](
    val nodeId: NodeId,
    val otherNodesRef: SignallingRef[F, List[PaxosNode[F]]],
    val transportLayerBinding: MessageTransport[F],
    initialSize: Int = 0
)(implicit override implicit val evAsync: Async[F])
    extends Proposer[F]
    with Acceptor[F]
    with Learner[F] {

  override protected val proposerId: ProposerId              = ProposerId(nodeId)
  override protected val prepareIdCounter: Ref[F, PrepareId] = ???

  // Update the proposer's acceptors list to include all nodes, except itself
  override protected val acceptorId: AcceptorId        = AcceptorId(nodeId)
  override val knownAcceptors: Ref[F, Set[AcceptorId]] = ???
  override protected val acceptorsSize: Int            = initialSize
  override protected val acceptedReceivedByAcceptors: Ref[F, Map[PrepareId, Set[AcceptorId]]] = ???
  override protected val highestPrepareId: Ref[F, Option[PrepareId]]                          = ???
  override protected val knownAcceptedValues: Ref[F, Map[PrepareId, String]]                  = ???
  override protected val acceptedCount: Ref[F, Map[PrepareId, Int]]                           = ???

  override protected val promisesReceivedByAcceptors: Ref[F, Map[PrepareId, Set[AcceptorId]]] = ???

  override protected val learnerId: NodeId                    = nodeId
  override protected val initialAcceptors: List[PaxosNode[F]] = ???
  override protected val learnedValue: Ref[F, Option[String]] = ???

  // When receiving messages, delegate to the appropriate role's receive method
  override def receive(message: PaxosMessage): F[Unit] = message match {
    case _: Prepare | _: Accept =>
      super[Acceptor].receive(message)
    case _: Promise | _: Accepted =>
      super[Proposer].receive(message)
    case _: Learn =>
      super[Learner].receive(message)
  }

  def addNode(node: PaxosNodeImpl[F]): F[Unit] =
    otherNodesRef.update(_.appended(node))

  def removeNode(node: PaxosNodeImpl[F]): F[Unit] =
    otherNodesRef.update(_.filterNot(_ == node))

}
