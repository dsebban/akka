/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.internal

import scala.concurrent.duration._

import akka.annotation.InternalApi
import akka.cluster.{ ddata => dd }
import akka.pattern.ask
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import akka.util.JavaDurationConverters._
import akka.cluster.ddata.ReplicatedData
import akka.actor.typed.Terminated

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ReplicatorBehavior {
  import akka.cluster.ddata.typed.javadsl.{ Replicator => JReplicator }
  import akka.cluster.ddata.typed.scaladsl.{ Replicator => SReplicator }

  private case class InternalChanged[A <: ReplicatedData](
      chg: dd.Replicator.Changed[A],
      subscriber: ActorRef[JReplicator.Changed[A]])
      extends JReplicator.Command

  val localAskTimeout = 60.seconds // ReadLocal, WriteLocal shouldn't timeout
  val additionalAskTimeout = 1.second

  def behavior(
      settings: dd.ReplicatorSettings,
      underlyingReplicator: Option[akka.actor.ActorRef]): Behavior[SReplicator.Command] = {

    Behaviors.setup { ctx =>
      val classicReplicator = underlyingReplicator match {
        case Some(ref) => ref
        case None      =>
          // FIXME perhaps add supervisor for restarting, see PR https://github.com/akka/akka/pull/25988
          val classicReplicatorProps = dd.Replicator.props(settings)
          ctx.actorOf(classicReplicatorProps, name = "underlying")
      }

      def withState(
          subscribeAdapters: Map[
            ActorRef[JReplicator.Changed[ReplicatedData]],
            ActorRef[dd.Replicator.Changed[ReplicatedData]]]): Behavior[SReplicator.Command] = {

        def stopSubscribeAdapter(
            subscriber: ActorRef[JReplicator.Changed[ReplicatedData]]): Behavior[SReplicator.Command] = {
          subscribeAdapters.get(subscriber) match {
            case Some(adapter) =>
              // will be unsubscribed from classicReplicator via Terminated
              ctx.stop(adapter)
              withState(subscribeAdapters - subscriber)
            case None => // already unsubscribed or terminated
              Behaviors.same
          }
        }

        Behaviors
          .receive[SReplicator.Command] { (ctx, msg) =>
            msg match {
              case cmd: SReplicator.Get[_] =>
                classicReplicator.tell(dd.Replicator.Get(cmd.key, cmd.consistency), sender = cmd.replyTo.toClassic)
                Behaviors.same

              case cmd: JReplicator.Get[d] =>
                implicit val timeout = Timeout(cmd.consistency.timeout match {
                  case java.time.Duration.ZERO => localAskTimeout
                  case t                       => t.asScala + additionalAskTimeout
                })
                import ctx.executionContext
                val reply =
                  (classicReplicator ? dd.Replicator.Get(cmd.key, cmd.consistency.toClassic))
                    .mapTo[dd.Replicator.GetResponse[d]]
                    .map {
                      case rsp: dd.Replicator.GetSuccess[d] =>
                        JReplicator.GetSuccess(rsp.key)(rsp.dataValue)
                      case rsp: dd.Replicator.NotFound[d]   => JReplicator.NotFound(rsp.key)
                      case rsp: dd.Replicator.GetFailure[d] => JReplicator.GetFailure(rsp.key)
                    }
                    .recover {
                      case _ => JReplicator.GetFailure(cmd.key)
                    }
                reply.foreach { cmd.replyTo ! _ }
                Behaviors.same

              case cmd: SReplicator.Update[_] =>
                classicReplicator.tell(
                  dd.Replicator.Update(cmd.key, cmd.writeConsistency, None)(cmd.modify),
                  sender = cmd.replyTo.toClassic)
                Behaviors.same

              case cmd: JReplicator.Update[d] =>
                implicit val timeout = Timeout(cmd.writeConsistency.timeout match {
                  case java.time.Duration.ZERO => localAskTimeout
                  case t                       => t.asScala + additionalAskTimeout
                })
                import ctx.executionContext
                val reply =
                  (classicReplicator ? dd.Replicator.Update(cmd.key, cmd.writeConsistency.toClassic, None)(cmd.modify))
                    .mapTo[dd.Replicator.UpdateResponse[d]]
                    .map {
                      case rsp: dd.Replicator.UpdateSuccess[d] => JReplicator.UpdateSuccess(rsp.key)
                      case rsp: dd.Replicator.UpdateTimeout[d] => JReplicator.UpdateTimeout(rsp.key)
                      case rsp: dd.Replicator.ModifyFailure[d] =>
                        JReplicator.ModifyFailure(rsp.key, rsp.errorMessage, rsp.cause)
                      case rsp: dd.Replicator.StoreFailure[d] => JReplicator.StoreFailure(rsp.key)
                    }
                    .recover {
                      case _ => JReplicator.UpdateTimeout(cmd.key)
                    }
                reply.foreach { cmd.replyTo ! _ }
                Behaviors.same

              case cmd: SReplicator.Subscribe[_] =>
                // For the Scala API the Changed messages can be sent directly to the subscriber
                classicReplicator.tell(
                  dd.Replicator.Subscribe(cmd.key, cmd.subscriber.toClassic),
                  sender = cmd.subscriber.toClassic)
                Behaviors.same

              case cmd: JReplicator.Subscribe[ReplicatedData] @unchecked =>
                // For the Java API the Changed messages must be mapped to the JReplicator.Changed class.
                // That is done with an adapter, and we have to keep track of the lifecycle of the original
                // subscriber and stop the adapter when the original subscriber is stopped.
                val adapter: ActorRef[dd.Replicator.Changed[ReplicatedData]] = ctx.spawnMessageAdapter { chg =>
                  InternalChanged(chg, cmd.subscriber)
                }

                classicReplicator.tell(
                  dd.Replicator.Subscribe(cmd.key, adapter.toClassic),
                  sender = akka.actor.ActorRef.noSender)

                ctx.watch(cmd.subscriber)

                withState(subscribeAdapters.updated(cmd.subscriber, adapter))

              case InternalChanged(chg, subscriber) =>
                subscriber ! JReplicator.Changed(chg.key)(chg.dataValue)
                Behaviors.same

              case cmd: JReplicator.Unsubscribe[ReplicatedData] @unchecked =>
                stopSubscribeAdapter(cmd.subscriber)

              case cmd: SReplicator.Delete[_] =>
                classicReplicator.tell(dd.Replicator.Delete(cmd.key, cmd.consistency), sender = cmd.replyTo.toClassic)
                Behaviors.same

              case cmd: JReplicator.Delete[d] =>
                implicit val timeout = Timeout(cmd.consistency.timeout match {
                  case java.time.Duration.ZERO => localAskTimeout
                  case t                       => t.asScala + additionalAskTimeout
                })
                import ctx.executionContext
                val reply =
                  (classicReplicator ? dd.Replicator.Delete(cmd.key, cmd.consistency.toClassic))
                    .mapTo[dd.Replicator.DeleteResponse[d]]
                    .map {
                      case rsp: dd.Replicator.DeleteSuccess[d] => JReplicator.DeleteSuccess(rsp.key)
                      case rsp: dd.Replicator.ReplicationDeleteFailure[d] =>
                        JReplicator.ReplicationDeleteFailure(rsp.key)
                      case rsp: dd.Replicator.DataDeleted[d]  => JReplicator.DataDeleted(rsp.key)
                      case rsp: dd.Replicator.StoreFailure[d] => JReplicator.StoreFailure(rsp.key)
                    }
                    .recover {
                      case _ => JReplicator.ReplicationDeleteFailure(cmd.key)
                    }
                reply.foreach { cmd.replyTo ! _ }
                Behaviors.same

              case SReplicator.GetReplicaCount(replyTo) =>
                classicReplicator.tell(dd.Replicator.GetReplicaCount, sender = replyTo.toClassic)
                Behaviors.same

              case JReplicator.GetReplicaCount(replyTo) =>
                implicit val timeout = Timeout(localAskTimeout)
                import ctx.executionContext
                val reply =
                  (classicReplicator ? dd.Replicator.GetReplicaCount)
                    .mapTo[dd.Replicator.ReplicaCount]
                    .map(rsp => JReplicator.ReplicaCount(rsp.n))
                reply.foreach { replyTo ! _ }
                Behaviors.same

              case SReplicator.FlushChanges | JReplicator.FlushChanges =>
                classicReplicator.tell(dd.Replicator.FlushChanges, sender = akka.actor.ActorRef.noSender)
                Behaviors.same

            }
          }
          .receiveSignal {
            case (_, Terminated(ref: ActorRef[JReplicator.Changed[ReplicatedData]] @unchecked)) =>
              stopSubscribeAdapter(ref)
          }
      }

      withState(Map.empty)

    }
  }
}
