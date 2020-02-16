package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.Recovery
import cats.Monad
import cats.effect.Resource
import cats.implicits._

/**
  * EventSourced describes lifecycle entity with regards to event sourcing
  * Lifecycle phases:
  *
  * 1. Started: we have id in place and can decide whether we should continue with recovery
  * 2. Recovering: reading snapshot and replaying events
  * 3. Receiving: receiving commands and potentially storing events & snapshots
  * 4. Terminating: triggers all release hooks of allocated resources within previous phases
  *
  * @tparam S snapshot
  * @tparam C command
  * @tparam E event
  * @tparam R reply
  */
trait EventSourced[F[_], S, C, E, R] {

  def id: String

  def recovery: Recovery = Recovery()

  def pluginIds: PluginIds = PluginIds.default

  // TODO onPreStart phase is missing

  // TODO describe resource release scope
  def start: Resource[F, Option[Started[F, S, C, E, R]]]
}


object EventSourced {

  implicit class EventSourcedOps[F[_], S, C, E, R](
    val self: EventSourced[F, S, C, E, R]
  ) extends AnyVal {

    def convert[S1, C1, E1, R1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      cf: C1 => F[C],
      ef: E => F[E1],
      e1f: E1 => F[E],
      rf: R => F[R1])(implicit
      F: Monad[F],
    ): EventSourced[F, S1, C1, E1, R1] = new EventSourced[F, S1, C1, E1, R1] {

      def id = self.id

      val start = self.start.map { _.map { _.convert(sf, s1f, cf, ef, e1f, rf) } }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E, R1 >: R](
      sf: S1 => F[S],
      cf: C1 => F[C],
      ef: E1 => F[E])(implicit
      F: Monad[F],
    ): EventSourced[F, S1, C1, E1, R1] = new EventSourced[F, S1, C1, E1, R1] {

      def id = self.id

      val start = self.start.map { _.map { _.widen(sf, cf, ef) } }
    }


    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F],
    ): EventSourced[F, Any, Any, Any, Any] = widen[Any, Any, Any, Any](sf, cf, ef)
  }
}