package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorRef
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import cats.effect.{Ref, Resource, Sync}
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.*

import java.time.Instant

object InstrumentEventSourced {

  def apply[F[_]: Sync, S, E, C](
    actions: Ref[F, List[Action[S, C, E]]],
    eventSourcedOf: EventSourcedOf[F, Resource[F, RecoveryStarted[F, S, E, Receive[F, Envelope[C], Boolean]]]],
  ): EventSourcedOf[F, Resource[F, RecoveryStarted[F, S, E, Receive[F, Envelope[C], Boolean]]]] = {

    class Instrument

    def record(action: Action[S, C, E]) = actions.update(action :: _)

    def resource[A](allocate: Action[S, C, E], release: Action[S, C, E]) =
      Resource.make {
        record(allocate)
      } { _ =>
        record(release)
      }

    EventSourcedOf[F] { actorCtx =>
      for {
        eventSourced <- eventSourcedOf(actorCtx)
        _ <- record(Action.Created(eventSourced.eventSourcedId, eventSourced.recovery, eventSourced.pluginIds))
      } yield eventSourced.map { recoveryStarted =>
        for {
          recoveryStarted <- recoveryStarted
          _               <- resource(Action.Started, Action.Released)
        } yield RecoveryStarted[S] { (seqNr, snapshotOffer) =>
          val snapshotOffer1 = snapshotOffer.map { snapshotOffer =>
            val metadata = snapshotOffer.metadata.copy(timestamp = Instant.ofEpochMilli(0))
            snapshotOffer.copy(metadata = metadata)
          }

          for {
            recovering <- recoveryStarted(seqNr, snapshotOffer)
            _          <- resource(Action.RecoveryAllocated(seqNr, snapshotOffer1), Action.RecoveryReleased)
          } yield Recovering[S].apply1 {
            for {
              _      <- resource(Action.ReplayAllocated, Action.ReplayReleased)
              replay <- recovering.replay
            } yield Replay[E] { (event, seqNr) =>
              for {
                _ <- replay(event, seqNr)
                _ <- record(Action.Replayed(event, seqNr))
              } yield {}
            }
          } { recoveringCtx =>
            import recoveringCtx.*

            val journaller1 = new Instrument with Journaller[F, E] {

              def append = events =>
                for {
                  _     <- record(Action.AppendEvents(events))
                  seqNr <- journaller.append(events)
                  _     <- record(Action.AppendEventsOuter)
                } yield for {
                  seqNr <- seqNr
                  _     <- record(Action.AppendEventsInner(seqNr))
                } yield seqNr

              def deleteTo = (seqNr: SeqNr) =>
                for {
                  _ <- record(Action.DeleteEventsTo(seqNr))
                  a <- journaller.deleteTo(seqNr)
                  _ <- record(Action.DeleteEventsToOuter)
                } yield for {
                  a <- a
                  _ <- record(Action.DeleteEventsToInner)
                } yield a
            }

            val snapshotter1 = new Instrument with Snapshotter[F, S] {

              def save(seqNr: SeqNr, snapshot: S) =
                for {
                  _ <- record(Action.SaveSnapshot(seqNr, snapshot))
                  a <- snapshotter.save(seqNr, snapshot)
                  _ <- record(Action.SaveSnapshotOuter)
                } yield for {
                  a <- a
                  _ <- record(Action.SaveSnapshotInner)
                } yield a

              def delete(seqNr: SeqNr) =
                for {
                  _ <- record(Action.DeleteSnapshot(seqNr))
                  a <- snapshotter.delete(seqNr)
                  _ <- record(Action.DeleteSnapshotOuter)
                } yield for {
                  a <- a
                  _ <- record(Action.DeleteSnapshotInner)
                } yield a

              def delete(criteria: SnapshotSelectionCriteria) =
                for {
                  _ <- record(Action.DeleteSnapshots(criteria))
                  a <- snapshotter.delete(criteria)
                  _ <- record(Action.DeleteSnapshotsOuter)
                } yield for {
                  a <- a
                  _ <- record(Action.DeleteSnapshotsInner)
                } yield a
            }

            for {
              context <- Recovering
                .RecoveryContext(recoveringCtx.seqNr, journaller1, snapshotter1, recoveredFromPersistence)
                .pure[Resource[F, *]]
              receive <- recovering.completed(context)
              _       <- resource(Action.ReceiveAllocated(recoveringCtx.seqNr), Action.ReceiveReleased)
            } yield Receive[Envelope[C]] { envelope =>
              for {
                stop <- receive(envelope)
                _    <- record(Action.Received(envelope.msg, envelope.from, stop))
              } yield stop
            } {
              for {
                stop <- receive.timeout
                _    <- record(Action.ReceiveTimeout)
              } yield stop
            }
          }
        }
      }
    }
  }

  sealed trait Action[+S, +C, +E]

  object Action {

    final case class Created(
      eventSourcedId: EventSourcedId,
      recovery: Recovery,
      pluginIds: PluginIds,
    ) extends Action[Nothing, Nothing, Nothing]

    case object Started extends Action[Nothing, Nothing, Nothing]

    case object Released extends Action[Nothing, Nothing, Nothing]

    final case class RecoveryAllocated[S](
      seqNr: SeqNr,
      snapshotOffer: Option[SnapshotOffer[S]],
    ) extends Action[S, Nothing, Nothing]

    case object RecoveryReleased extends Action[Nothing, Nothing, Nothing]

    case object ReplayAllocated extends Action[Nothing, Nothing, Nothing]

    case object ReplayReleased extends Action[Nothing, Nothing, Nothing]

    final case class Replayed[S, E](event: E, seqNr: SeqNr) extends Action[S, Nothing, E]

    final case class AppendEvents[E](events: Events[E]) extends Action[Nothing, Nothing, E]

    case object AppendEventsOuter extends Action[Nothing, Nothing, Nothing]

    final case class AppendEventsInner(seqNr: SeqNr) extends Action[Nothing, Nothing, Nothing]

    final case class DeleteEventsTo(seqNr: SeqNr) extends Action[Nothing, Nothing, Nothing]

    case object DeleteEventsToOuter extends Action[Nothing, Nothing, Nothing]

    case object DeleteEventsToInner extends Action[Nothing, Nothing, Nothing]

    final case class SaveSnapshot[S](seqNr: SeqNr, snapshot: S) extends Action[S, Nothing, Nothing]

    case object SaveSnapshotOuter extends Action[Nothing, Nothing, Nothing]

    case object SaveSnapshotInner extends Action[Nothing, Nothing, Nothing]

    final case class DeleteSnapshot(seqNr: SeqNr) extends Action[Nothing, Nothing, Nothing]

    case object DeleteSnapshotOuter extends Action[Nothing, Nothing, Nothing]

    case object DeleteSnapshotInner extends Action[Nothing, Nothing, Nothing]

    final case class DeleteSnapshots(criteria: SnapshotSelectionCriteria) extends Action[Nothing, Nothing, Nothing]

    case object DeleteSnapshotsOuter extends Action[Nothing, Nothing, Nothing]

    case object DeleteSnapshotsInner extends Action[Nothing, Nothing, Nothing]

    final case class ReceiveAllocated[S](seqNr: SeqNr) extends Action[S, Nothing, Nothing]

    case object ReceiveReleased extends Action[Nothing, Nothing, Nothing]

    final case class Received[C](
      cmd: C,
      sender: ActorRef,
      stop: Boolean,
    ) extends Action[Nothing, C, Nothing]

    case object ReceiveTimeout extends Action[Nothing, Nothing, Nothing]
  }
}
