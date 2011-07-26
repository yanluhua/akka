/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.actor._
import akka.dispatch.Future
import akka.event.EventHandler
import akka.routing.{RouterType, RoutingException}
import RouterType._

import com.eaio.uuid.UUID

import annotation.tailrec

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Router {

  def newRouter(
                 routerType: RouterType,
                 inetSocketAddresses: Array[Tuple2[UUID, InetSocketAddress]],
                 actorAddress: String,
                 timeout: Long): ClusterActorRef = {
    routerType match {
      case Direct ⇒ new ClusterActorRef(inetSocketAddresses, actorAddress, timeout) with Direct
      case Random ⇒ new ClusterActorRef(inetSocketAddresses, actorAddress, timeout) with Random
      case RoundRobin ⇒ new ClusterActorRef(inetSocketAddresses, actorAddress, timeout) with RoundRobin
      case LeastCPU ⇒ sys.error("Router LeastCPU not supported yet")
      case LeastRAM ⇒ sys.error("Router LeastRAM not supported yet")
      case LeastMessages ⇒ sys.error("Router LeastMessages not supported yet")
    }
  }

  /**
   * The Router is responsible for sending a message to one (or more) of its connections.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait Router {

    /**
     * Returns a Map containing all ActorRefs this Router uses send messages to.
     */
    def connections: Map[InetSocketAddress, ActorRef]

    /**
     * A callback this Router uses to indicate that some actorRef was not usable.
     *
     * Implementations should make sure that this method can be called without the actorRef being part of the
     * current set of connections. The most logical way to deal with this situation, is just to ignore it.
     *
     * @param ref the dead
     */
    def signalDeadActor(ref: ActorRef): Unit

    /**
     *
     */
    def route(message: Any)(implicit sender: Option[ActorRef]): Unit

    /**
     *
     */
    def route[T](message: Any, timeout: Long)(implicit sender: Option[ActorRef]): Future[T]
  }

  /**
   * An Abstract Router implementation that already provides the basic infrastructure so that a concrete
   * Router only needs to implement the next method.
   *
   * This also is the location where a failover  is done in the future if an ActorRef fails and a different
   * one needs to be selected.
   */
  trait BasicRouter extends Router {

    def route(message: Any)(implicit sender: Option[ActorRef]): Unit = next match {
      case Some(actor) ⇒ {
        try {
          actor.!(message)(sender)
        } catch {
          case e: Exception =>
            signalDeadActor(actor)
            throw e
        }
      }
      case _ ⇒ throwNoConnectionsError()
    }

    def route[T](message: Any, timeout: Long)(implicit sender: Option[ActorRef]): Future[T] = next match {
      case Some(actor) ⇒ {
        try {
          actor.?(message, timeout)(sender).asInstanceOf[Future[T]]
        } catch {
          case e: Throwable =>
            signalDeadActor(actor)
            throw e
        }
      }
      case _ ⇒ throwNoConnectionsError()
    }

    protected def next: Option[ActorRef]

    private def throwNoConnectionsError() = {
      val error = new RoutingException("No replica connections for router")
      EventHandler.error(error, this, error.toString)
      throw error
    }
  }

  /**
   * A Router that is used when a durable actor is used. All requests are send to the node containing the actor.
   * As soon as that instance fails, a different instance is created and since the mailbox is durable, the internal
   * state can be restored using event sourcing, and once this instance is up and running, all request will be send
   * to this instance.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait Direct extends BasicRouter {

    lazy val next: Option[ActorRef] = {
      val connection = connections.values.headOption
      if (connection.isEmpty) EventHandler.warning(this, "Router has no replica connection")
      connection
    }
  }

  /**
   * A Router that randomly selects one of the target connections to send a message to.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait Random extends BasicRouter {

    private val random = new java.util.Random(System.currentTimeMillis)

    def next: Option[ActorRef] =
      if (connections.isEmpty) {
        EventHandler.warning(this, "Router has no replica connections")
        None
      } else {
        Some(connections.valuesIterator.drop(random.nextInt(connections.size)).next())
      }
  }

  /**
   * A Router that uses round-robin to select a connection.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait RoundRobin extends BasicRouter {
    private def items: List[ActorRef] = connections.values.toList

    private val current = new AtomicReference[List[ActorRef]](items)

    private def hasNext = connections.nonEmpty

    def next: Option[ActorRef] = {
      @tailrec
      def findNext: Option[ActorRef] = {
        val currentItems = current.get
        val newItems = currentItems match {
          case Nil ⇒ items
          case xs ⇒ xs
        }

        if (newItems.isEmpty) {
          EventHandler.warning(this, "Router has no replica connections")
          None
        } else {
          if (current.compareAndSet(currentItems, newItems.tail)) newItems.headOption
          else findNext
        }
      }

      findNext
    }
  }

}
