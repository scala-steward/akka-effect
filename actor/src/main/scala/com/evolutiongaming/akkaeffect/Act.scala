package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect.{Async, Sync}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.util.Serial
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

/**
  * Act executes function in `receive` thread of an actor
  */
private[akkaeffect] trait Act[F[_]] {

  def apply[A](f: => A): F[A]
}


private[akkaeffect] object Act {

  def now[F[_]: Sync]: Act[F] = new Act[F] {
    def apply[A](f: => A) = Sync[F].delay { f }
  }


  def of[F[_]: Sync: ToFuture: FromFuture: ToTry]: F[Act[F]] = {
    Serial.of[F].map { serially => apply(serially) }
  }


  def apply[F[_]: Sync: ToTry](serial: Serial[F]): Act[F] = new Act[F] {
    def apply[A](f: => A) = {
      serial { Sync[F].delay { f } }
        .toTry
        .get
    }
  }

  sealed trait AdapterLike

  trait Adapter[F[_]] extends AdapterLike {

    def value: Act[F]

    def receive(receive: Actor.Receive): Actor.Receive

    def sync[A](f: => A): A
  }

  object Adapter {

    private val threadLocal: ThreadLocal[Option[AdapterLike]] = new ThreadLocal[Option[AdapterLike]] {
      override def initialValue() = none[AdapterLike]
    }

    def apply[F[_]: Async](actorRef: ActorRef): Adapter[F] = {
      val tell = actorRef.tell(_, ActorRef.noSender)
      apply(tell)
    }


    def apply[F[_]: Async](tell: Any => Unit): Adapter[F] = {

      case class Msg(f: () => Unit)

      new Adapter[F] { self =>

        def syncReceive(receive: Actor.Receive): Actor.Receive = new Actor.Receive {

          def isDefinedAt(a: Any) = receive.isDefinedAt(a)

          def apply(a: Any) = sync { receive(a) }
        }

        val value = new Act[F] {
          def apply[A](f: => A) = {
            if (threadLocal.get().contains(self: Adapter[F])) {
              Sync[F].delay { f }
            } else {
              Async[F].async[A] { callback =>
                val f1 = () => {
                  val a = Either.catchNonFatal(f)
                  callback(a)
                  a match {
                    case Right(_) =>
                    case Left(a) => throw a
                  }
                }
                tell(Msg(f1))
              }
            }
          }
        }

        def receive(receive: Actor.Receive) = {
          val receiveMsg: Actor.Receive = { case Msg(f) => f() }
          syncReceive(receiveMsg orElse receive)
        }

        def sync[A](f: => A) = {
          threadLocal.set(self.some)
          try f finally threadLocal.set(none)
        }
      }
    }
  }
}
