package com.evolutiongaming.akkaeffect.persistence

import akka.persistence._
import cats.Monad
import cats.effect.concurrent.Ref
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.util.PromiseEffect
import com.evolutiongaming.akkaeffect.{Act, Fail}
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, MonadThrowable, ToTry}

import scala.collection.immutable.Queue


/**
  * @see [[akka.persistence.PersistentActor.persistAllAsync]]
  */
trait Append[F[_], -A] {

  /**
    * @param events to be saved, inner Nel[A] will be persisted atomically, outer Nel[_] is for batching
    * @return SeqNr of last event
    */
  def apply(events: Events[A]): F[F[SeqNr]]
}

object Append {

  private[akkaeffect] def adapter[F[_] : Sync : FromFuture : ToTry, A](
    act: Act[F],
    actor: PersistentActor,
    stopped: F[Throwable]
  ): Resource[F, Adapter[F, A]] = {
    adapter(act, Eventsourced(actor), stopped)
  }

  private[akkaeffect] def adapter[F[_] : Sync : FromFuture : ToTry, A](
    act: Act[F],
    eventsourced: Eventsourced,
    stopped: F[Throwable]
  ): Resource[F, Adapter[F, A]] = {

    def fail(ref: Ref[F, Queue[PromiseEffect[F, SeqNr]]], error: F[Throwable]) = {
      for {
        queue  <- ref.getAndSet(Queue.empty)
        result <- queue
          .toList
          .toNel
          .foldMapM { queue =>
            for {
              error  <- error
              result <- queue.foldMapM { _.fail(error) }
            } yield result
          }
      } yield result
    }

    Resource
      .make {
        Ref[F].of(Queue.empty[PromiseEffect[F, SeqNr]])
      } { ref =>
        fail(ref, stopped)
      }
      .map { ref =>

        new Adapter[F, A] {

          val value: Append[F, A] = {
            events => {

              val size = events.size

              def persist(promise: PromiseEffect[F, SeqNr]) = {

                act {
                  ref
                    .update { _.enqueue(promise) }
                    .toTry
                    .get

                  var left = size
                  events.values.toList.foreach { events =>
                    eventsourced.persistAllAsync(events.toList) { _ =>
                      left = left - 1
                      if (left <= 0) {
                        val seqNr = eventsourced.lastSequenceNr
                        ref
                          .modify { queue =>
                            queue
                              .dequeueOption
                              .fold {
                                (Queue.empty[PromiseEffect[F, SeqNr]], none[PromiseEffect[F, SeqNr]])
                              } { case (promise, queue) =>
                                (queue, promise.some)
                              }
                          }
                          .flatMap { _.foldMapM { _.success(seqNr) } }
                          .toTry
                          .get
                      }
                    }
                  }
                }
              }

              for {
                promise <- PromiseEffect[F, SeqNr]
                _       <- persist(promise)
              } yield {
                promise.get
              }
            }
          }

          val onError: OnError[A] = {
            (error: Throwable, _: A, _: SeqNr) =>
              fail(ref, error.pure[F])
                .toTry
                .get
          }
        }
      }
  }


  implicit class AppendOps[F[_], A](val self: Append[F, A]) extends AnyVal {

    def convert[B](f: B => F[A])(implicit F: Monad[F]): Append[F, B] = {
      events => {
        for {
          events <- events.traverse(f)
          seqNr  <- self(events)
        } yield seqNr
      }
    }


    def narrow[B <: A]: Append[F, B] = events => self(events)


    def withFail(fail: Fail[F])(implicit F: MonadThrowable[F]): Append[F, A] = {
      events => fail.adapt(s"failed to append $events") { self(events) }
    }
  }


  private[akkaeffect] trait Eventsourced {

    def lastSequenceNr: SeqNr

    def persistAllAsync[A](events: List[A])(handler: A => Unit): Unit
  }

  private[akkaeffect] object Eventsourced {

    def apply(actor: PersistentActor): Eventsourced = new Eventsourced {

      def lastSequenceNr = actor.lastSequenceNr

      def persistAllAsync[A](events: List[A])(f: A => Unit) = actor.persistAllAsync(events)(f)
    }
  }


  private[akkaeffect] trait OnError[A] {

    def apply(cause: Throwable, event: A, seqNr: SeqNr): Unit
  }


  private[akkaeffect] trait Adapter[F[_], A] {

    def value: Append[F, A]

    def onError: OnError[A]
  }

  object Adapter {

    implicit class AdapterOps[F[_], A](val self: Adapter[F, A]) extends AnyVal {

      def withFail(fail: Fail[F])(implicit F: MonadThrowable[F]): Adapter[F, A] = new Adapter[F, A] {

        val value = self.value.withFail(fail)

        def onError = self.onError
      }
    }
  }
}
