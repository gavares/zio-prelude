/*
 * Copyright 2020-2021 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.prelude.fx

import com.github.ghik.silencer.silent
import zio.internal.{Stack, StackBool}
import zio.prelude._
import zio.{CanFail, Chunk, ChunkBuilder, NeedsEnv, NonEmptyChunk}

import scala.annotation.{implicitNotFound, switch}
import scala.reflect.ClassTag
import scala.util.Try

/**
 * `ZPure[W, S1, S2, R, E, A]` is a purely functional description of a
 * computation that requires an environment `R` and an initial state `S1` and
 * may either fail with an `E` or succeed with an updated state `S2` and an `A`
 * along with in either case a log with entries of type `W`. Because of its
 * polymorphism `ZPure` can be used to model a variety of effects including
 * context, state, failure, and logging.
 */
sealed abstract class ZPure[+W, -S1, +S2, -R, +E, +A] { self =>

  /**
   * Runs this computation if the provided environment is a `Left` or else
   * runs that computation if the provided environment is a `Right`, returning
   * the result in an `Either`.
   */
  final def +++[W1 >: W, S0 <: S1, S3 >: S2, R1, B, E1 >: E](
    that: ZPure[W1, S0, S3, R1, E1, B]
  ): ZPure[W1, S0, S3, Either[R, R1], E1, Either[A, B]] =
    ZPure.accessM(_.fold(self.provide(_).map(Left(_)), that.provide(_).map(Right(_))))

  /**
   * A symbolic alias for `orElseEither`.
   */
  final def <+>[W1 >: W, S0 <: S1, S3 >: S2, R1 <: R, E1, B](
    that: => ZPure[W1, S0, S3, R1, E1, B]
  )(implicit ev: CanFail[E]): ZPure[W1, S0, S3, R1, E1, Either[A, B]] =
    self.orElseEither(that)

  /**
   * A symbolic alias for `orElse`.
   */
  final def <>[W1 >: W, S0 <: S1, S3 >: S2, R1 <: R, E1, A1 >: A](
    that: => ZPure[W1, S0, S3, R1, E1, A1]
  )(implicit ev: CanFail[E]): ZPure[W1, S0, S3, R1, E1, A1] =
    self.orElse(that)

  /**
   * A symbolic alias for `log`.
   */
  final def ??[W1 >: W](w: W1): ZPure[W1, S1, S2, R, E, A] =
    self.log(w)

  /**
   * A symbolic alias for `join`.
   */
  final def |||[W1 >: W, S0 <: S1, S3 >: S2, R1, B, E1 >: E, A1 >: A](
    that: ZPure[W1, S0, S3, R1, E1, A1]
  ): ZPure[W1, S0, S3, Either[R, R1], E1, A1] =
    self.join(that)

  /**
   * Submerges the error case of an `Either` into the error type of this
   * computation.
   */
  final def absolve[E1 >: E, B](implicit ev: A <:< Either[E1, B]): ZPure[W, S1, S2, R, E1, B] =
    self.flatMap(ev(_).fold(ZPure.fail, ZPure.succeed))

  /**
   * Maps the success value of this computation to a constant value.
   */
  final def as[B](b: => B): ZPure[W, S1, S2, R, E, B] =
    self.map(_ => b)

  /**
   * Maps the success value of this computation to the optional value.
   */
  final def asSome: ZPure[W, S1, S2, R, E, Option[A]] =
    self.map(Some(_))

  /**
   * Maps the error value of this computation to the optional value.
   */
  final def asSomeError(implicit ev: CanFail[E]): ZPure[W, S1, S2, R, Option[E], A] =
    self.mapError(Some(_))

  /**
   * Maps the output state to a constant value
   */
  final def asState[S3](s: S3): ZPure[W, S1, S3, R, E, A] =
    self.mapState(_ => s)

  /**
   * Returns a computation whose error and success channels have been mapped
   * by the specified functions, `f` and `g`.
   */
  final def bimap[E1, B](f: E => E1, g: A => B)(implicit ev: CanFail[E]): ZPure[W, S1, S2, R, E1, B] =
    self.foldM(
      e => ZPure.fail(f(e)).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)),
      a => ZPure.succeed(g(a))
    )

  /**
   * Modifies the behavior of the inner computation regarding logs, so that
   * logs written in a failed computation will be cleared.
   */
  final def clearLogOnError: ZPure[W, S1, S2, R, E, A] =
    ZPure.Flag(ZPure.FlagType.ClearLogOnError, value = true, self)

  /**
   * Transforms the result of this computation with the specified partial
   * function, failing with the `e` value if the partial function is not
   * defined for the given input.
   */
  final def collect[E1 >: E, B](e: => E1)(pf: PartialFunction[A, B]): ZPure[W, S1, S2, R, E1, B] =
    self.collectM(e)(pf.andThen(ZPure.succeed(_)))

  /**
   * Transforms the initial state of this computation with the specified
   * function.
   */
  final def contramapState[S0](f: S0 => S1): ZPure[W, S0, S2, R, E, A] =
    ZPure.update(f) *> self

  /**
   * Returns a computation whose failure and success have been lifted into an
   * `Either`. The resulting computation cannot fail, because the failure case
   * has been exposed as part of the `Either` success case.
   */
  final def either[S3 >: S2 <: S1](implicit ev: CanFail[E]): ZPure[W, S3, S3, R, Nothing, Either[E, A]] =
    fold(Left(_), Right(_))

  /**
   * Fails with the specified error if the predicate fails.
   */
  final def filterOrFail[E1 >: E](p: A => Boolean)(e: => E1): ZPure[W, S1, S2, R, E1, A] =
    self.filterOrElse_[W, S2, S2, R, E1, A](p)(ZPure.get[S2] *> ZPure.fail(e))

  /**
   * Swaps the error and success types of this computation.
   */
  final def flip[S3 >: S2 <: S1]: ZPure[W, S3, S3, R, A, E] =
    (self: ZPure[W, S3, S3, R, E, A]).foldM(e => ZPure.get[S3] *> ZPure.succeed(e), ZPure.fail)

  final def fold[S3 >: S2 <: S1, B](failure: E => B, success: A => B)(implicit
    ev: CanFail[E]
  ): ZPure[W, S3, S3, R, Nothing, B] =
    (self: ZPure[W, S3, S3, R, E, A])
      .foldM(e => ZPure.get[S3] *> ZPure.succeed(failure(e)), a => ZPure.succeed(success(a)))

  /**
   * Exposes the output state into the value channel.
   */
  final def getState: ZPure[W, S1, S2, R, E, (S2, A)] =
    self.flatMap(a => ZPure.get[S2].map(s => (s, a)))

  /**
   * Returns a successful computation with the head of the list if the list is
   * non-empty or fails with the error `None` if the list is empty.
   */
  final def head[B](implicit ev: A <:< List[B]): ZPure[W, S1, S2, R, Option[E], B] =
    self.foldM(
      e => ZPure.fail(Some(e)).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)),
      a =>
        ev(a).headOption match {
          case Some(b) => ZPure.succeed(b)
          case None    => ZPure.fail(None)
        }
    )

  /**
   * Runs this computation if the provided environment is a `Left` or else
   * runs that computation if the provided environment is a `Right`, unifying
   * the result to a common supertype.
   */
  final def join[W1 >: W, S0 <: S1, S3 >: S2, R1, B, E1 >: E, A1 >: A](
    that: ZPure[W1, S0, S3, R1, E1, A1]
  ): ZPure[W1, S0, S3, Either[R, R1], E1, A1] =
    ZPure.accessM(_.fold(self.provide, that.provide))

  /**
   * Modifies the behavior of the inner computation regarding logs, so that
   * logs written in a failed computation will be kept (this is the default behavior).
   */
  final def keepLogOnError: ZPure[W, S1, S2, R, E, A] =
    ZPure.Flag(ZPure.FlagType.ClearLogOnError, value = false, self)

  /**
   * Returns a successful computation if the value is `Left`, or fails with error `None`.
   */
  final def left[B, C](implicit ev: A <:< Either[B, C]): ZPure[W, S1, S2, R, Option[E], B] =
    self.foldM(
      e => ZPure.fail(Some(e)).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)),
      a => ev(a).fold(ZPure.succeed, _ => ZPure.fail(None))
    )

  /**
   * Returns a successful computation if the value is `Left`, or fails with error `e`.
   */
  final def leftOrFail[B, C, E1 >: E](e: => E1)(implicit ev: A <:< Either[B, C]): ZPure[W, S1, S2, R, E1, B] =
    self.flatMap(ev(_) match {
      case Right(_)    => ZPure.fail(e)
      case Left(value) => ZPure.succeed(value)
    })

  /**
   * Returns a successful computation if the value is `Left`, or fails with the given error function `e`.
   */
  final def leftOrFailWith[B, C, E1 >: E](e: C => E1)(implicit ev: A <:< Either[B, C]): ZPure[W, S1, S2, R, E1, B] =
    self.flatMap(ev(_) match {
      case Right(err)  => ZPure.fail(e(err))
      case Left(value) => ZPure.succeed(value)
    })

  /**
   * Returns a successful computation if the value is `Left`, or fails with a [[java.util.NoSuchElementException]].
   */
  final def leftOrFailWithException[B, C, E1 >: NoSuchElementException](implicit
    ev: A <:< Either[B, C],
    ev2: E <:< E1
  ): ZPure[W, S1, S2, R, E1, B] =
    self.foldM(
      e => ZPure.fail(ev2(e)).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)),
      a => ev(a).fold(ZPure.succeed, _ => ZPure.fail(new NoSuchElementException("Either.left.get on Right")))
    )

  final def log[W1 >: W](w: W1): ZPure[W1, S1, S2, R, E, A] =
    self <* ZPure.log(w)

  /**
   * Transforms the result of this computation with the specified function.
   */
  final def map[B](f: A => B): ZPure[W, S1, S2, R, E, B] =
    self.flatMap(a => ZPure.succeed(f(a)))

  /**
   * Transforms the error type of this computation with the specified
   * function.
   */
  final def mapError[E1](f: E => E1)(implicit ev: CanFail[E]): ZPure[W, S1, S2, R, E1, A] =
    self.catchAll(e => ZPure.fail(f(e)).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)))

  /**
   * Returns a computation with its full cause of failure mapped using the
   * specified function. This can be users to transform errors while
   * preserving the original structure of the `Cause`.
   */
  final def mapErrorCause[E2](f: Cause[E] => Cause[E2]): ZPure[W, S1, S2, R, E2, A] =
    self.foldCauseM(
      cause => ZPure.halt(f(cause)).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)),
      ZPure.succeed
    )

  /**
   * Transforms the updated state of this computation with the specified
   * function.
   */
  final def mapState[S3](f: S2 => S3): ZPure[W, S1, S3, R, E, A] =
    self <* ZPure.update(f)

  /**
   * Negates the boolean value of this computation.
   */
  final def negate(implicit ev: A <:< Boolean): ZPure[W, S1, S2, R, E, Boolean] =
    self.map(!_)

  /**
   * Requires the value of this computation to be `None`, otherwise fails with `None`.
   */
  final def none[B](implicit ev: A <:< Option[B]): ZPure[W, S1, S2, R, Option[E], Unit] =
    self.foldM(
      e => ZPure.fail(Some(e)).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)),
      a => a.fold[ZPure[W, Unit, Unit, R, Option[E], Unit]](ZPure.succeed(()))(_ => ZPure.fail(None))
    )

  /**
   * Executes this computation and returns its value, if it succeeds, but
   * otherwise executes the specified computation.
   */
  final def orElse[W1 >: W, S0 <: S1, S3 >: S2, R1 <: R, E1, A1 >: A](
    that: => ZPure[W1, S0, S3, R1, E1, A1]
  )(implicit ev: CanFail[E]): ZPure[W1, S0, S3, R1, E1, A1] =
    (self: ZPure[W1, S0, S3, R1, E, A]).foldM(_ => that, ZPure.succeed)

  /**
   * Executes this computation and returns its value, if it succeeds, but
   * otherwise executes the specified computation.
   */
  final def orElseEither[W1 >: W, S0 <: S1, S3 >: S2, R1 <: R, E1, B](
    that: => ZPure[W1, S0, S3, R1, E1, B]
  )(implicit ev: CanFail[E]): ZPure[W1, S0, S3, R1, E1, Either[A, B]] =
    (self: ZPure[W1, S0, S3, R1, E, A]).foldM(_ => that.map(Right(_)), a => ZPure.succeed(Left(a)))

  /**
   * Executes this computation and returns its value, if it succeeds, but
   * otherwise fails with the specified error.
   */
  final def orElseFail[E1](e1: => E1)(implicit ev: CanFail[E]): ZPure[W, S1, S2, R, E1, A] =
    self.orElse(ZPure.fail0(e1))

  /**
   * Returns an computation that will produce the value of this computation, unless it
   * fails with the `None` value, in which case it will produce the value of
   * the specified computation.
   */
  final def orElseOptional[W1 >: W, S0 <: S1, S3 >: S2, R1 <: R, E1, A1 >: A](
    that: => ZPure[W1, S0, S3, R1, Option[E1], A1]
  )(implicit ev: E <:< Option[E1]): ZPure[W1, S0, S3, R1, Option[E1], A1] =
    (self: ZPure[W1, S0, S3, R1, E, A]).catchAll(ev(_).fold(that)(e => ZPure.fail0(Some(e))))

  /**
   * Executes this computation and returns its value, if it succeeds, but
   * otherwise succeeds with the specified value.
   */
  final def orElseSucceed[A1 >: A](a1: => A1)(implicit ev: CanFail[E]): ZPure[W, S1, Any, R, Nothing, A1] =
    orElse(ZPure.get[S1] *> ZPure.succeed(a1))

  /**
   * Executes this computation and returns its value, if it succeeds, but
   * otherwise fallbacks to the new state with the specified value.
   */
  final def orElseFallback[A1 >: A, S3 >: S2](a1: => A1, s3: => S3)(implicit
    ev: CanFail[E]
  ): ZPure[W, S1, S3, R, Nothing, A1] =
    (self: ZPure[W, S1, S3, R, E, A1]).foldM(_ => ZPure.update[S1, S3](_ => s3) *> ZPure.succeed(a1), ZPure.succeed)

  /**
   * Provides this computation with its required environment.
   */
  final def provide(r: R)(implicit ev: NeedsEnv[R]): ZPure[W, S1, S2, Any, E, A] =
    ZPure.Provide(r, self)

  /**
   * Provides this computation with part of its required environment, leaving
   * the remainder.
   */
  final def provideSome[R0](f: R0 => R): ZPure[W, S1, S2, R0, E, A] =
    ZPure.accessM(r0 => self.provide(f(r0)))

  /**
   * Provides this computation with its initial state.
   */
  final def provideState(s: S1): ZPure[W, Any, S2, R, E, A] =
    ZPure.set(s) *> self

  /**
   * Keeps some of the errors, and `throw` the rest
   */
  final def refineOrDie[E1](
    pf: PartialFunction[E, E1]
  )(implicit ev1: E <:< Throwable, ev2: CanFail[E]): ZPure[W, S1, S2, R, E1, A] =
    refineOrDieWith(pf)(ev1)

  /**
   * Keeps some of the errors, and `throw` the rest, using
   * the specified function to convert the `E` into a `Throwable`.
   */
  final def refineOrDieWith[E1](pf: PartialFunction[E, E1])(f: E => Throwable)(implicit
    ev: CanFail[E]
  ): ZPure[W, S1, S2, R, E1, A] =
    self catchAll (err =>
      (pf lift err).fold(throw f(err))(e => ZPure.fail(e).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)))
    )

  /**
   * Fail with the returned value if the `PartialFunction` matches, otherwise
   * continue with our held value.
   */
  final def reject[S0 <: S1, S3 >: S2, R1 <: R, E1 >: E](pf: PartialFunction[A, E1]): ZPure[W, S0, S3, R1, E1, A] =
    self.rejectM(pf.andThen(ZPure.fail0(_)))

  /**
   * Repeats this computation the specified number of times (or until the first failure)
   * passing the updated state to each successive repetition.
   */
  final def repeatN(n: Int)(implicit ev: S2 <:< S1): ZPure[W, S1, S2, R, E, A] =
    self.flatMap(a => if (n <= 0) ZPure.get[S2] *> ZPure.succeed(a) else repeatN(n - 1).contramapState(ev))

  /**
   * Repeats this computation until its value satisfies the specified predicate
   * (or until the first failure) passing the updated state to each successive repetition.
   */
  final def repeatUntil(f: A => Boolean)(implicit ev: S2 <:< S1): ZPure[W, S1, S2, R, E, A] =
    self.flatMap(a => if (f(a)) ZPure.get[S2] *> ZPure.succeed(a) else repeatUntil(f).contramapState(ev))

  /**
   * Repeats this computation until its value is equal to the specified value
   * (or until the first failure) passing the updated state to each successive repetition.
   */
  final def repeatUntilEquals[A1 >: A](a: => A1)(implicit ev: S2 <:< S1): ZPure[W, S1, S2, R, E, A1] =
    repeatUntil(_ == a)

  /**
   * Repeats this computation until the updated state satisfies the specified predicate
   * (or until the first failure) passing the updated state to each successive repetition.
   */
  final def repeatUntilState(f: S2 => Boolean)(implicit ev: S2 <:< S1): ZPure[W, S1, S2, R, E, A] =
    self.zip(ZPure.get[S2]).flatMap { case (a, s) =>
      if (f(s)) ZPure.get[S2] *> ZPure.succeed(a)
      else repeatUntilState(f).contramapState(ev)
    }

  /**
   * Repeats this computation until the updated state is equal to the specified value
   * (or until the first failure) passing the updated state to each successive repetition.
   */
  final def repeatUntilStateEquals[S3 >: S2](s: => S3)(implicit ev: S2 <:< S1): ZPure[W, S1, S3, R, E, A] =
    repeatUntilState(_ == s)

  /**
   * Repeats this computation for as long as its value satisfies the specified predicate
   * (or until the first failure) passing the updated state to each successive repetition.
   */
  final def repeatWhile(f: A => Boolean)(implicit ev: S2 <:< S1): ZPure[W, S1, S2, R, E, A] =
    repeatUntil(!f(_))

  /**
   * Repeats this computation for as long as its value is equal to the specified value
   * (or until the first failure) passing the updated state to each successive repetition.
   */
  final def repeatWhileEquals[A1 >: A](a: => A1)(implicit ev: S2 <:< S1): ZPure[W, S1, S2, R, E, A1] =
    repeatWhile(_ == a)

  /**
   * Repeats this computation for as long as the updated state satisfies the specified predicate
   * (or until the first failure) passing the updated state to each successive repetition.
   */
  final def repeatWhileState(f: S2 => Boolean)(implicit ev: S2 <:< S1): ZPure[W, S1, S2, R, E, A] =
    repeatUntilState(!f(_))

  /**
   * Returns a successful computation if the value is `Right`, or fails with error `None`.
   */
  final def right[B, C](implicit ev: A <:< Either[B, C]): ZPure[W, S1, S2, R, Option[E], C] =
    self.foldM(
      e => ZPure.fail(Some(e)).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)),
      a => ev(a).fold(_ => ZPure.fail(None), ZPure.succeed)
    )

  /*
     Returns a successful computation if the value is `Right`, or fails with error `e`.
   */
  final def rightOrFail[B, C, E1 >: E](e: => E1)(implicit ev: A <:< Either[B, C]): ZPure[W, S1, S2, R, E1, C] =
    self.flatMap(ev(_) match {
      case Right(value) => ZPure.succeed(value)
      case Left(_)      => ZPure.fail(e)
    })

  /*
     Returns a successful computation if the value is `Right`, or fails with error function `e`.
   */
  final def rightOrFailWith[B, C, E1 >: E](e: B => E1)(implicit ev: A <:< Either[B, C]): ZPure[W, S1, S2, R, E1, C] =
    self.flatMap(ev(_) match {
      case Right(value) => ZPure.succeed(value)
      case Left(err)    => ZPure.fail(e(err))
    })

  /*
     Returns a successful computation if the value is `Right`, or fails with a [[java.util.NoSuchElementException]].
   */
  final def rightOrFailWithException[B, C, E1 >: NoSuchElementException](implicit
    ev: A <:< Either[B, C],
    ev2: E <:< E1
  ): ZPure[W, S1, S2, R, E1, C] =
    self.foldM(
      e => ZPure.fail(ev2(e)).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)),
      a => ev(a).fold(_ => ZPure.fail(new NoSuchElementException("Either.right.get on Left")), ZPure.succeed)
    )

  /**
   * Runs this computation to produce its result.
   */
  final def run(implicit ev1: Unit <:< S1, ev2: Any <:< R, ev3: E <:< Nothing): A =
    runResult(())

  /**
   * Runs this computation with the specified initial state, returning both
   * the updated state and the result.
   */
  final def run(s: S1)(implicit ev1: Any <:< R, ev2: E <:< Nothing): (S2, A) =
    runAll(s)._2.fold(cause => ev2(cause.first), identity)

  /**
   * Runs this computation with the specified initial state, returning both the
   * log and either all the failures that occurred or the updated state and the
   * result.
   */
  final def runAll(s: S1)(implicit ev: Any <:< R): (Chunk[W], Either[Cause[E], (S2, A)]) = {
    val _                                                        = ev
    val stack: Stack[Any => ZPure[Any, Any, Any, Any, Any, Any]] = Stack()
    val environments: Stack[AnyRef]                              = Stack()
    val logs: Stack[ChunkBuilder[Any]]                           = Stack(ChunkBuilder.make())
    val clearLogOnError: StackBool                               = StackBool()
    var s0: Any                                                  = s
    var a: Any                                                   = null
    var failed                                                   = false
    var curZPure: ZPure[Any, Any, Any, Any, Any, Any]            = self.asInstanceOf[ZPure[Any, Any, Any, Any, Any, Any]]

    def findNextErrorHandler(): Unit = {
      var unwinding = true
      while (unwinding)
        stack.pop() match {
          case value: ZPure.Fold[_, _, _, _, _, _, _, _, _, _, _, _] =>
            val continuation = value.failure
            stack.push(continuation.asInstanceOf[Any => ZPure[Any, Any, Any, Any, Any, Any]])
            unwinding = false
          case null                                                  =>
            unwinding = false
          case _                                                     =>
        }
    }

    while (curZPure ne null) {
      val tag = curZPure.tag
      (tag: @switch) match {
        case ZPure.Tags.FlatMap =>
          val zPure        = curZPure.asInstanceOf[ZPure.FlatMap[Any, Any, Any, Any, Any, Any, Any, Any, Any]]
          val nested       = zPure.value
          val continuation = zPure.continue

          nested.tag match {
            case ZPure.Tags.Succeed =>
              val zPure2 = nested.asInstanceOf[ZPure.Succeed[Any]]
              curZPure = continuation(zPure2.value)

            case ZPure.Tags.Modify =>
              val zPure2 = nested.asInstanceOf[ZPure.Modify[Any, Any, Any]]

              val updated = zPure2.run0(s0)
              s0 = updated._1
              a = updated._2
              curZPure = continuation(a)

            case _ =>
              curZPure = nested
              stack.push(continuation)
          }

        case ZPure.Tags.Succeed =>
          val zPure     = curZPure.asInstanceOf[ZPure.Succeed[Any]]
          a = zPure.value
          val nextInstr = stack.pop()
          if (nextInstr eq null) curZPure = null else curZPure = nextInstr(a)
        case ZPure.Tags.Fail    =>
          val zPure     = curZPure.asInstanceOf[ZPure.Fail[Any]]
          findNextErrorHandler()
          val nextInstr = stack.pop()
          if (nextInstr eq null) {
            failed = true
            a = zPure.error
            curZPure = null
          } else
            curZPure = nextInstr(zPure.error)

        case ZPure.Tags.Fold    =>
          val zPure = curZPure.asInstanceOf[ZPure.Fold[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
          val state = s0
          val fold  =
            ZPure.Fold(
              zPure.value,
              (cause: Cause[Any]) =>
                ZPure.suspend(ZPure.Succeed({
                  val clear   = clearLogOnError.peekOrElse(false)
                  val builder = logs.pop()
                  if (!clear) logs.peek() ++= builder.result()
                })) *> ZPure.set(state) *> zPure.failure(cause),
              (a: Any) =>
                ZPure.suspend(ZPure.Succeed({
                  val builder = logs.pop()
                  logs.peek() ++= builder.result()
                })) *> zPure.success(a),
              zPure.fold
            )
          stack.push(fold)
          logs.push(ChunkBuilder.make())
          curZPure = zPure.value
        case ZPure.Tags.Access  =>
          val zPure = curZPure.asInstanceOf[ZPure.Access[Any, Any, Any, Any, Any, Any]]
          curZPure = zPure.access(environments.peek())
        case ZPure.Tags.Provide =>
          val zPure = curZPure.asInstanceOf[ZPure.Provide[Any, Any, Any, Any, Any, Any]]
          environments.push(zPure.r.asInstanceOf[AnyRef])
          curZPure = zPure.continue.foldCauseM(
            e => (ZPure.succeed(environments.pop()) *> ZPure.halt(e)).asInstanceOf[ZPure[Any, Any, Any, Any, Any, Any]],
            a =>
              (ZPure.succeed(environments.pop()) *> ZPure.succeed(a)).asInstanceOf[ZPure[Any, Any, Any, Any, Any, Any]]
          )(implicitly, FoldState.compose)
        case ZPure.Tags.Modify  =>
          val zPure     = curZPure.asInstanceOf[ZPure.Modify[Any, Any, Any]]
          val updated   = zPure.run0(s0)
          s0 = updated._1
          a = updated._2
          val nextInstr = stack.pop()
          if (nextInstr eq null) curZPure = null else curZPure = nextInstr(a)
        case ZPure.Tags.Log     =>
          val zPure     = curZPure.asInstanceOf[ZPure.Log[Any, Any]]
          logs.peek() += zPure.log
          val nextInstr = stack.pop()
          a = ()
          if (nextInstr eq null) curZPure = null else curZPure = nextInstr(a)
        case ZPure.Tags.Flag    =>
          val zPure = curZPure.asInstanceOf[ZPure.Flag[Any, Any, Any, Any, Any, Any]]
          zPure.flag match {
            case ZPure.FlagType.ClearLogOnError =>
              clearLogOnError.push(zPure.value)
              curZPure = zPure.continue.bimap(
                e => {
                  if (zPure.value) logs.peek().clear()
                  clearLogOnError.popOrElse(false)
                  e
                },
                a => { clearLogOnError.popOrElse(false); a }
              )
          }
      }
    }
    val log = logs.peek().result().asInstanceOf[Chunk[W]]
    if (failed) (log, Left(a.asInstanceOf[Cause[E]]))
    else (log, Right((s0.asInstanceOf[S2], a.asInstanceOf[A])))
  }

  /**
   * Runs this computation to produce its result or the first failure to
   * occur.
   */
  final def runEither(implicit ev1: Unit <:< S1, ev2: Any <:< R): Either[E, A] =
    runAll(())._2.fold(cause => Left(cause.first), { case (_, a) => Right(a) })

  /**
   * Runs this computation to produce its result and the log.
   */
  final def runLog(implicit ev1: Unit <:< S1, ev2: Any <:< R, ev3: E <:< Nothing): (Chunk[W], A) = {
    val (log, either) = runAll(())
    (log, either.fold(cause => ev3(cause.first), { case (_, a) => a }))
  }

  /**
   * Runs this computation with the specified initial state, returning the
   * result and discarding the updated state.
   */
  final def runResult(s: S1)(implicit ev1: Any <:< R, ev2: E <:< Nothing): A =
    run(s)._2

  /**
   * Runs this computation with the specified initial state, returning the
   * updated state and discarding the result.
   */
  final def runState(s: S1)(implicit ev1: Any <:< R, ev2: E <:< Nothing): S2 =
    run(s)._1

  /**
   * Runs this computation to a `ZValidation` value.
   */
  final def runValidation(implicit ev1: Unit <:< S1, ev2: Any <:< R): ZValidation[W, E, A] =
    runAll(()) match {
      case (log, Left(cause))   => ZValidation.Failure(log, NonEmptyChunk.fromChunk(cause.toChunk).get)
      case (log, Right((_, a))) => ZValidation.Success(log, a)
    }

  /**
   * Exposes the full cause of failures of this computation.
   */
  final def sandbox: ZPure[W, S1, S2, R, Cause[E], A] =
    self.foldCauseM(e => ZPure.fail(e).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)), ZPure.succeed)

  /**
   * Converts an option on values into an option on errors leaving the state unchanged.
   */
  final def some[B](implicit ev: A <:< Option[B]): ZPure[W, S1, S2, R, Option[E], B] =
    self.foldM(
      e => ZPure.fail(Some(e)).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)),
      a => a.fold[ZPure[W, Unit, Unit, R, Option[E], B]](ZPure.fail(Option.empty))(ZPure.succeed)
    )

  /**
   * Extracts the optional value or returns the given 'default' leaving the state unchanged.
   */
  final def someOrElse[B](default: => B)(implicit ev: A <:< Option[B]): ZPure[W, S1, S2, R, E, B] =
    map(_.getOrElse(default))

  /**
   * Extracts the optional value or fails with the given error 'e'.
   */
  final def someOrFail[B, E1 >: E](e: => E1)(implicit ev: A <:< Option[B]): ZPure[W, S1, S2, R, E1, B] =
    self.flatMap(ev(_) match {
      case Some(value) => ZPure.succeed(value)
      case None        => ZPure.fail(e)
    })

  /**
   * Extracts the optional value or fails with a [[java.util.NoSuchElementException]] leaving the state unchanged.
   */
  final def someOrFailException[B, E1 >: E](implicit
    ev: A <:< Option[B],
    ev2: NoSuchElementException <:< E1
  ): ZPure[W, S1, S2, R, E1, B] =
    self.flatMap(ev(_) match {
      case Some(value) => ZPure.succeed(value)
      case None        => ZPure.fail(ev2(new NoSuchElementException("None.get")))
    })

  protected def tag: Int

  /**
   * Transforms ZPure to ZIO that either succeeds with `A` or fails with error(s) `E`.
   * The original state is supposed to be `()`.
   */
  def toZIO(implicit ev: Unit <:< S1): zio.ZIO[R, E, A] = zio.ZIO.accessM[R] { r =>
    self.provide(r).runAll(())._2 match {
      case Left(cause)   => zio.ZIO.halt(cause.toCause)
      case Right((_, a)) => zio.ZIO.succeedNow(a)
    }
  }

  /**
   * Transforms ZPure to ZIO that either succeeds with `A` or fails with error(s) `E`.
   */
  def toZIOWith(s1: S1): zio.ZIO[R, E, A] =
    zio.ZIO.accessM[R] { r =>
      val result = provide(r).runAll(s1)
      result._2 match {
        case Left(cause)   => zio.ZIO.halt(cause.toCause)
        case Right((_, a)) => zio.ZIO.succeedNow(a)
      }
    }

  /**
   * Transforms ZPure to ZIO that either succeeds with `S2` and `A` or fails with error(s) `E`.
   */
  def toZIOWithState(s1: S1): zio.ZIO[R, E, (S2, A)] =
    zio.ZIO.accessM[R] { r =>
      val result = provide(r).runAll(s1)
      result._2 match {
        case Left(cause)   => zio.ZIO.halt(cause.toCause)
        case Right(result) => zio.ZIO.succeedNow(result)
      }
    }

  /**
   * Transforms ZPure to ZIO that either succeeds with `Chunk[W]`, `S2` and `A` or fails with error(s) `E`.
   */
  def toZIOWithAll(s1: S1): zio.ZIO[R, E, (Chunk[W], S2, A)] =
    zio.ZIO.accessM[R] { r =>
      val (log, result) = provide(r).runAll(s1)
      result match {
        case Left(cause)    => zio.ZIO.halt(cause.toCause)
        case Right((s2, a)) => zio.ZIO.succeedNow((log, s2, a))
      }
    }

  /**
   * Maps the value of this computation to unit.
   */
  final def unit: ZPure[W, S1, S2, R, E, Unit] =
    self.as(())

  /**
   * Submerges the full cause of failures of this computation.
   */
  def unsandbox[E1](implicit ev: E <:< Cause[E1]): ZPure[W, S1, S2, R, E1, A] =
    self.foldM(
      e => ZPure.halt(ev(e)).flatMap(e => ZPure.update[S1, S2](_ => e) *> ZPure.fail(e)),
      a => ZPure.succeed(a)
    )
}

object ZPure extends ZPureLowPriorityImplicits with ZPureArities {

  def access[R]: AccessPartiallyApplied[R] =
    new AccessPartiallyApplied

  def accessM[R]: AccessMPartiallyApplied[R] =
    new AccessMPartiallyApplied

  /**
   * Constructs a computation, catching any `Throwable` that is thrown.
   */
  def attempt[A](a: => A): ZPure[Nothing, Unit, Unit, Any, Throwable, A] =
    suspend {
      try ZPure.succeed(a)
      catch {
        case e: VirtualMachineError => throw e
        case e: Throwable           => ZPure.fail(e)
      }
    }

  /**
   * Combines a collection of computations into a single computation that
   * passes the updated state from each computation to the next and collects
   * the results.
   */
  def collectAll[F[+_]: ForEach, W, S, R, E, A](fa: F[ZPure[W, S, S, R, E, A]]): ZPure[W, S, S, R, E, F[A]] =
    ForEach[F].flip[({ type lambda[+A] = ZPure[W, S, S, R, E, A] })#lambda, A](fa)

  /**
   * Constructs a computation from a state transition function that returns
   * the original state unchanged.
   */
  def const[S]: ZPure[Nothing, S, S, Any, Nothing, Unit]                                                    =
    update(identity)

  def environment[R]: ZPure[Nothing, Unit, Unit, R, Nothing, R] =
    access(r => r)

  def fail[E](e: E): ZPure[Nothing, Unit, Unit, Any, E, Nothing] =
    halt(Cause(e))

  def fail0[E](e: E): ZPure[Nothing, Any, Nothing, Any, E, Nothing] =
    halt0(Cause(e))

  /**
   * Constructs a computation that extracts the first element of a tuple.
   */
  def first[A]: ZPure[Nothing, Unit, Unit, (A, Any), Nothing, A] =
    fromFunction(_._1)

  /**
   * Maps each element of a collection to a computation and combines them all
   * into a single computation that passes the updated state from each
   * computation to the next and collects the results.
   */
  def forEach[F[+_]: ForEach, W, S, R, E, A, B](fa: F[A])(
    f: A => ZPure[W, S, S, R, E, B]
  ): ZPure[W, S, S, R, E, F[B]]                                                     =
    ForEach[F].forEach[({ type lambda[+A] = ZPure[W, S, S, R, E, A] })#lambda, A, B](fa)(f)

  /**
   * Constructs a computation from an `Either`.
   */
  def fromEither[L, R](either: Either[L, R]): ZPure[Nothing, Unit, Unit, Any, L, R] =
    either.fold(l => ZPure.fail(l), r => ZPure.succeed(r))

  /**
   * Constructs a computation from a function.
   */
  def fromFunction[R, A](f: R => A): ZPure[Nothing, Unit, Unit, R, Nothing, A] =
    access(f)

  /**
   * Constructs a computation from an `Option`.
   */
  def fromOption[A](option: Option[A]): ZPure[Nothing, Unit, Unit, Any, Unit, A] =
    option match {
      case Some(a) => ZPure.succeed(a)
      case None    => ZPure.fail(())
    }

  /**
   * Constructs a `Validation` from a predicate, failing with None.
   */
  def fromPredicate[A](value: A)(f: A => Boolean): Validation[None.type, A] =
    fromPredicateWith(None)(value)(f)

  /**
   * Constructs a `Validation` from a predicate, failing with the error provided.
   */
  def fromPredicateWith[E, A](error: => E)(value: A)(f: A => Boolean): Validation[E, A] =
    if (f(value)) Validation.succeed(value)
    else Validation.fail(error)

  /**
   * Constructs a computation from a `scala.util.Try`.
   */
  def fromTry[A](t: Try[A]): ZPure[Nothing, Unit, Unit, Any, Throwable, A] =
    attempt(t).flatMap {
      case scala.util.Success(v) => ZPure.succeed(v)
      case scala.util.Failure(t) => ZPure.fail(t)
    }

  /**
   * Constructs a computation that returns the initial state unchanged.
   */
  def get[S]: ZPure[Nothing, S, S, Any, Nothing, S] =
    modify(s => (s, s))

  def halt[E](cause: Cause[E]): ZPure[Nothing, Unit, Unit, Any, E, Nothing] =
    ZPure.Fail(cause)

  def halt0[E](cause: Cause[E]): ZPure[Nothing, Any, Nothing, Any, E, Nothing] =
    ZPure.Fail(cause)

  def log[W](w: W): ZPure[W, Unit, Unit, Any, Nothing, Unit] =
    ZPure.Log(w)

  /**
   * Combines the results of the specified `ZPure` values using the function
   * `f`, failing with the accumulation of all errors if any fail.
   */
  def mapParN[W, S, R, E, A, B, C](left: ZPure[W, S, S, R, E, A], right: ZPure[W, S, S, R, E, B])(
    f: (A, B) => C
  ): ZPure[W, S, S, R, E, C] =
    left.foldCauseM(
      c1 =>
        right.foldCauseM(
          c2 => ZPure.get[S] *> ZPure.halt(c1 && c2),
          _ => ZPure.get[S] *> ZPure.halt(c1)
        ),
      a => right.map(b => f(a, b))
    )

  /**
   * Combines the results of the specified `ZPure` values using the function
   * `f`, failing with the first error if any fail.
   */
  def mapN[W, S, R, E, A, B, C](left: ZPure[W, S, S, R, E, A], right: ZPure[W, S, S, R, E, B])(
    f: (A, B) => C
  ): ZPure[W, S, S, R, E, C] =
    left.zipWith(right)(f)

  /**
   * Constructs a computation from the specified modify function.
   */
  def modify[S1, S2, A](f: S1 => (S2, A)): ZPure[Nothing, S1, S2, Any, Nothing, A] =
    new ZPure.Modify(f)

  /**
   * Constructs a computation that may fail from the specified modify function.
   */
  def modifyEither[S1, S2, E, A](f: S1 => Either[E, (S2, A)]): ZPure[Nothing, S1, S2, Any, E, A] =
    for {
      s      <- ZPure.get[S1]
      tuple  <- ZPure.fromEither(f(s))
      (s2, a) = tuple
      _      <- ZPure.set(s2)
    } yield a

  /**
   * Constructs a computation that extracts the second element of a tuple.
   */
  def second[A]: ZPure[Nothing, Unit, Unit, (Any, A), Nothing, A] =
    fromFunction(_._2)

  /**
   * Constructs a computation that sets the state to the specified value.
   */
  def set[S](s: S): ZPure[Nothing, Any, S, Any, Nothing, Unit] =
    modify(_ => (s, ()))

  /**
   * Constructs a computation that always succeeds with the specified value,
   * passing the state through unchanged.
   */
  def succeed[A](a: A): ZPure[Nothing, Unit, Unit, Any, Nothing, A] =
    new ZPure.Succeed(a)

  /**
   * Returns a lazily constructed computation.
   */
  def suspend[W, S1, S2, R, E, A](pure: => ZPure[W, S1, S2, R, E, A]): ZPure[W, S1, S2, R, E, A] =
    ZPure.unit.flatMap(_ => pure)

  /**
   * Combines the results of the specified `ZPure` values into a tuple, failing
   * with the first error if any fail.
   */
  def tupled[W, S, R, E, A, B](
    left: ZPure[W, S, S, R, E, A],
    right: ZPure[W, S, S, R, E, B]
  ): ZPure[W, S, S, R, E, (A, B)] =
    mapN(left, right)((_, _))

  /**
   * Combines the results of the specified `ZPure` values into a tuple, failing
   * with the accumulation of all errors if any fail.
   */
  def tupledPar[W, S, R, E, A0, A1](
    zPure1: ZPure[W, S, S, R, E, A0],
    zPure2: ZPure[W, S, S, R, E, A1]
  ): ZPure[W, S, S, R, E, (A0, A1)] =
    mapParN(zPure1, zPure2)((_, _))

  /**
   * A computation that always returns the `Unit` value, passing the
   * state through unchanged.
   */
  val unit: ZPure[Nothing, Unit, Unit, Any, Nothing, Unit] =
    succeed(())

  /**
   * Constructs a computation from the specified update function.
   */
  def update[S1, S2](f: S1 => S2): ZPure[Nothing, S1, S2, Any, Nothing, Unit] =
    modify(s => (f(s), ()))

  implicit final class ZPureSyntax[+W, S1, S2, -R, +E, +A](private val self: ZPure[W, S1, S2, R, E, A]) extends AnyVal {

    /**
     * A symbolic alias for `zip`.
     */
    final def &&&[W1 >: W, S3, S4, R1 <: R, E1 >: E, B](
      that: ZPure[W1, S3, S4, R1, E1, B]
    )(implicit compose: ComposeState[S1, S2, S3, S4]): ZPure[W1, compose.In, compose.Out, R1, E1, (A, B)] =
      self.zip(that)

    /**
     * Splits the environment, providing the first part to this computaiton and
     * the second part to that computation.
     */
    final def ***[W1 >: W, S3, S4, R1, E1 >: E, B](that: ZPure[W1, S3, S4, R1, E1, B])(implicit
      compose: ComposeState[S1, S2, S3, S4]
    ): ZPure[W1, compose.In, compose.Out, (R, R1), E1, (A, B)] =
      (ZPure.first >>> self) &&& (ZPure.second >>> that)

    /**
     * A symbolic alias for `zipRight`.
     */
    final def *>[W1 >: W, S3, S4, R1 <: R, E1 >: E, B](that: ZPure[W1, S3, S4, R1, E1, B])(implicit
      compose: ComposeState[S1, S2, S3, S4]
    ): ZPure[W1, compose.In, compose.Out, R1, E1, B] =
      self.zipRight(that)

    /**
     * A symbolic alias for `zipLeft`.
     */
    final def <*[W1 >: W, S3, S4, R1 <: R, E1 >: E, B](that: ZPure[W1, S3, S4, R1, E1, B])(implicit
      compose: ComposeState[S1, S2, S3, S4]
    ): ZPure[W1, compose.In, compose.Out, R1, E1, A] =
      self.zipLeft(that)

    /**
     * A symbolic alias for `zip`.
     */
    final def <*>[W1 >: W, S3, S4, R1 <: R, E1 >: E, B](
      that: ZPure[W1, S3, S4, R1, E1, B]
    )(implicit compose: ComposeState[S1, S2, S3, S4]): ZPure[W1, compose.In, compose.Out, R1, E1, (A, B)] =
      self.zip(that)

    /**
     * A symbolic alias for `compose`.
     */
    final def <<<[W1 >: W, S3, S4, R0, E1 >: E](that: ZPure[W1, S3, S4, R0, E1, R])(implicit
      compose: ComposeState[S3, S4, S1, S2]
    ): ZPure[W1, compose.In, compose.Out, R0, E1, A] =
      self compose that

    /**
     * A symbolic alias for `flatMap`.
     */
    final def >>=[W1 >: W, S3, S4, R1 <: R, E1 >: E, B](f: A => ZPure[W1, S3, S4, R1, E1, B])(implicit
      compose: ComposeState[S1, S2, S3, S4]
    ): ZPure[W1, compose.In, compose.Out, R1, E1, B] =
      self flatMap f

    /**
     * A symbolic alias for `andThen`.
     */
    final def >>>[W1 >: W, S3, S4, E1 >: E, B](that: ZPure[W1, S3, S4, A, E1, B])(implicit
      compose: ComposeState[S1, S2, S3, S4]
    ): ZPure[W1, compose.In, compose.Out, R, E1, B] =
      self andThen that

    /**
     * Runs this computation and uses its result to provide the specified
     * computation with its required environment.
     */
    final def andThen[W1 >: W, S3, S4, E1 >: E, B](that: ZPure[W1, S3, S4, A, E1, B])(implicit
      compose: ComposeState[S1, S2, S3, S4]
    ): ZPure[W1, compose.In, compose.Out, R, E1, B] =
      self.flatMap(that.provide)

    /**
     * Recovers from all errors.
     */
    final def catchAll[W1 >: W, S3, S4, R1 <: R, E1, A1 >: A](
      f: E => ZPure[W1, S3, S4, R1, E1, A1]
    )(implicit
      ev: CanFail[E],
      compose: FoldState[S1, S2, S3, S4, Unit, Unit]
    ): ZPure[W1, compose.In, compose.Out, R1, E1, A1] =
      self.foldM(f, succeed)

    /**
     * Recovers from some or all of the error cases.
     */
    final def catchSome[W1 >: W, S3, S4, R1 <: R, E1 >: E, A1 >: A](
      pf: PartialFunction[E, ZPure[W1, S3, S4, R1, E1, A1]]
    )(implicit
      ev: CanFail[E],
      compose: FoldState[S1, S2, S3, S4, Unit, Unit]
    ): ZPure[W1, compose.In, compose.Out, R1, E1, A1] =
      self.catchAll(pf.applyOrElse[E, ZPure[W1, S3, S4, R1, E1, A1]](_, fail0))

    /**
     * Transforms the result of this computation with the specified partial
     * function which returns a new computation, failing with the `e` value if
     * the partial function is not defined for the given input.
     */
    final def collectM[W1 >: W, S3, S4, R1 <: R, E1 >: E, B](
      e: => E1
    )(pf: PartialFunction[A, ZPure[W1, S3, S4, R1, E1, B]])(implicit
      compose: ComposeState[S1, S2, S3, S4]
    ): ZPure[W1, compose.In, compose.Out, R1, E1, B] =
      self.flatMap(pf.applyOrElse[A, ZPure[W1, S3, S4, R1, E1, B]](_, _ => ZPure.fail0(e)))

    /**
     * Runs the specified computation and uses its result to provide this
     * computation with its required environment.
     */
    final def compose[W1 >: W, S3, S4, R2, E1 >: E](that: ZPure[W1, S3, S4, R2, E1, R])(implicit
      compose: ComposeState[S3, S4, S1, S2]
    ): ZPure[W1, compose.In, compose.Out, R2, E1, A] =
      that andThen self

    /**
     * Extends this computation with another computation that depends on the
     * result of this computation by running the first computation, using its
     * result to generate a second computation, and running that computation.
     */
    def flatMap[W1 >: W, S3, S4, R1 <: R, E1 >: E, B](f: A => ZPure[W1, S3, S4, R1, E1, B])(implicit
      compose: ComposeState[S1, S2, S3, S4]
    ): ZPure[W1, compose.In, compose.Out, R1, E1, B] =
      new ZPure.FlatMap(self, f, compose)

    /**
     * Flattens a nested computation to a single computation by running the outer
     * computation and then running the inner computation.
     */
    final def flatten[W1 >: W, S3, S4, R1 <: R, E1 >: E, B](implicit
      ev: A <:< ZPure[W1, S3, S4, R1, E1, B],
      compose: ComposeState[S1, S2, S3, S4]
    ): ZPure[W1, compose.In, compose.Out, R1, E1, B] =
      self.flatMap(ev)

    final def foldCauseM[W1 >: W, S3, S4, S5, S6, R1 <: R, E1, B](
      failure: Cause[E] => ZPure[W1, S3, S4, R1, E1, B],
      success: A => ZPure[W1, S5, S6, R1, E1, B]
    )(implicit
      ev: CanFail[E],
      compose: FoldState[S1, S2, S3, S4, S5, S6]
    ): ZPure[W1, compose.In, compose.Out, R1, E1, B] =
      Fold(self, failure, success, compose)

    /**
     * Recovers from errors by accepting one computation to execute for the case
     * of an error, and one computation to execute for the case of success.
     */
    final def foldM[W1 >: W, S3, S4, S5, S6, R1 <: R, E1, B](
      failure: E => ZPure[W1, S3, S4, R1, E1, B],
      success: A => ZPure[W1, S5, S6, R1, E1, B]
    )(implicit
      ev: CanFail[E],
      compose: FoldState[S1, S2, S3, S4, S5, S6]
    ): ZPure[W1, compose.In, compose.Out, R1, E1, B] =
      foldCauseM((cause: Cause[E]) => failure(cause.first), success)

    /**
     * Continue with the returned computation if the `PartialFunction` matches,
     * translating the successful match into a failure, otherwise continue with
     * our held value.
     */
    final def rejectM[W1 >: W, S0 <: S1, S3 >: S2, R1 <: R, E1 >: E](
      pf: PartialFunction[A, ZPure[W1, S2, S3, R1, E1, E1]]
    ): ZPure[W1, S0, S3, R1, E1, A] =
      self.flatMap { v =>
        if (pf.isDefinedAt(v)) {
          pf(v).flatMap(ZPure.fail)
        } else {
          ZPure.get[S2] *> ZPure.succeed(v)
        }
      }

    /**
     * Extracts the optional value or runs the specified computation passing the
     * updated state from this computation.
     */
    final def someOrElseM[W1 >: W, S3, S4 >: S3, R1 <: R, E1 >: E, B](
      that: ZPure[W1, S3, S4, R1, E1, B]
    )(implicit
      ev: A <:< Option[B],
      compose: ComposeState[S1, S2, S3, S4]
    ): ZPure[W1, compose.In, compose.Out, R1, E1, B] =
      self.flatMap(ev(_).fold(that)(a => ZPure.get[S4] *> ZPure.succeed(a)))

    /**
     * Applies the specified function if the predicate fails.
     */
    final def filterOrElse[W1 >: W, S3, S4 >: S3, R1 <: R, E1 >: E, A1 >: A](
      p: A => Boolean
    )(
      f: A => ZPure[W1, S3, S4, R1, E1, A1]
    )(implicit compose: ComposeState[S1, S2, S3, S4]): ZPure[W1, compose.In, compose.Out, R1, E1, A1] =
      self.flatMap {
        case v if !p(v) => f(v)
        case v          => ZPure.get[S4] *> ZPure.succeed(v)
      }

    /**
     * Similar to `filterOrElse`, but instead of a function it accepts the ZPure computation
     * to apply if the predicate fails.
     */
    final def filterOrElse_[W1 >: W, S3, S4 >: S3, R1 <: R, E1 >: E, A1 >: A](p: A => Boolean)(
      zPure: => ZPure[W1, S3, S4, R1, E1, A1]
    )(implicit compose: ComposeState[S1, S2, S3, S4]): ZPure[W1, compose.In, compose.Out, R1, E1, A1] =
      filterOrElse[W1, S3, S4, R1, E1, A1](p)(_ => zPure)

    /**
     * Combines this computation with the specified computation, passing the
     * updated state from this computation to that computation and combining the
     * results of both into a tuple.
     */
    final def zip[W1 >: W, S3, S4, R1 <: R, E1 >: E, B](
      that: ZPure[W1, S3, S4, R1, E1, B]
    )(implicit compose: ComposeState[S1, S2, S3, S4]): ZPure[W1, compose.In, compose.Out, R1, E1, (A, B)] =
      self.zipWith(that)((_, _))

    /**
     * Combines this computation with the specified computation, passing the
     * updated state from this computation to that computation and returning the
     * result of this computation.
     */
    final def zipLeft[W1 >: W, S3, S4, R1 <: R, E1 >: E, B](
      that: ZPure[W1, S3, S4, R1, E1, B]
    )(implicit compose: ComposeState[S1, S2, S3, S4]): ZPure[W1, compose.In, compose.Out, R1, E1, A] =
      self.zipWith(that)((a, _) => a)

    /**
     * Combines this computation with the specified computation, passing the
     * updated state from this computation to that computation and returning the
     * result of that computation.
     */
    final def zipRight[W1 >: W, S3, S4, R1 <: R, E1 >: E, B](that: ZPure[W1, S3, S4, R1, E1, B])(implicit
      compose: ComposeState[S1, S2, S3, S4]
    ): ZPure[W1, compose.In, compose.Out, R1, E1, B] =
      self.zipWith(that)((_, b) => b)

    /**
     * Combines this computation with the specified computation, passing the
     * updated state from this computation to that computation and combining the
     * results of both using the specified function.
     */
    final def zipWith[W1 >: W, S3, S4, R1 <: R, E1 >: E, B, C](
      that: ZPure[W1, S3, S4, R1, E1, B]
    )(f: (A, B) => C)(implicit compose: ComposeState[S1, S2, S3, S4]): ZPure[W1, compose.In, compose.Out, R1, E1, C] =
      self.flatMap(a => that.map(b => f(a, b)))
  }

  final class AccessPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[A](f: R => A): ZPure[Nothing, Unit, Unit, R, Nothing, A] =
      Access(r => succeed(f(r)))
  }

  final class AccessMPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[W, S1, S2, E, A](f: R => ZPure[W, S1, S2, Any, E, A]): ZPure[W, S1, S2, R, E, A] =
      Access(f)
  }

  @implicitNotFound(
    "Pattern guards are only supported when the error type is a supertype of NoSuchElementException. However, your effect has ${E} for the error type."
  )
  abstract class CanFilter[+E] {
    def apply(t: NoSuchElementException): E
  }

  object CanFilter {
    implicit def canFilter[E >: NoSuchElementException]: CanFilter[E] =
      new CanFilter[E] {
        def apply(t: NoSuchElementException): E = t
      }
  }

  implicit def ZPureCustomCovariantSyntax[W, S1, S2, R, E, A]: CustomCovariantSyntax[ZPure[W, S1, S2, R, E, A]] =
    new CustomCovariantSyntax[ZPure[W, S1, S2, R, E, A]] {}

  implicit def ZPureCustomAssociativeFlattenSyntax[W, S1, S2, R, E, A]
    : CustomAssociativeFlattenSyntax[ZPure[W, S1, S2, R, E, A]]                                                 =
    new CustomAssociativeFlattenSyntax[ZPure[W, S1, S2, R, E, A]] {}

  implicit def ZPureCustomAssociativeBothSyntax[W, S1, S2, R, E, A]
    : CustomAssociativeBothSyntax[ZPure[W, S1, S2, R, E, A]]                                                    =
    new CustomAssociativeBothSyntax[ZPure[W, S1, S2, R, E, A]] {}

  /**
   * The `Covariant` instance for `ZPure`.
   */
  implicit def ZPureCovariant[W, S1, S2, R, E]: Covariant[({ type lambda[+A] = ZPure[W, S1, S2, R, E, A] })#lambda] =
    new Covariant[({ type lambda[+A] = ZPure[W, S1, S2, R, E, A] })#lambda] {
      def map[A, B](f: A => B): ZPure[W, S1, S2, R, E, A] => ZPure[W, S1, S2, R, E, B] =
        _.map(f)
    }

  /**
   * The `IdentityBoth` instance for `ZPure`.
   */
  implicit def ZPureIdentityBoth[W, S, R, E]: IdentityBoth[({ type lambda[+A] = ZPure[W, S, S, R, E, A] })#lambda] =
    new IdentityBoth[({ type lambda[+A] = ZPure[W, S, S, R, E, A] })#lambda] {
      def any: ZPure[W, S, S, Any, Nothing, Any]                                                                   =
        ZPure.const
      def both[A, B](fa: => ZPure[W, S, S, R, E, A], fb: => ZPure[W, S, S, R, E, B]): ZPure[W, S, S, R, E, (A, B)] =
        fa.zip(fb)
    }

  /**
   * The `IdentityFlatten` instance for `ZPure`.
   */
  implicit def ZPureIdentityFlatten[W, S, R, E]
    : IdentityFlatten[({ type lambda[+A] = ZPure[W, S, S, R, E, A] })#lambda] =
    new IdentityFlatten[({ type lambda[+A] = ZPure[W, S, S, R, E, A] })#lambda] {
      def any: ZPure[W, S, S, Any, Nothing, Any]                                                  =
        ZPure.const
      def flatten[A](ffa: ZPure[W, S, S, R, E, ZPure[W, S, S, R, E, A]]): ZPure[W, S, S, R, E, A] =
        ffa.flatMap(identity)
    }

  implicit final class ZPureRefineToOrDieOps[W, S1, S2, R, E <: Throwable, A](self: ZPure[W, S1, S2, R, E, A]) {

    /**
     * Keeps some of the errors, and `throw` the rest.
     */
    def refineToOrDie[E1 <: E: ClassTag](implicit ev: CanFail[E]): ZPure[W, S1, S2, R, E1, A] =
      self.refineOrDie { case e: E1 => e }
  }

  implicit final class ZPureWithFilterOps[W, S1, S2, R, E, A](private val self: ZPure[W, S1, S2, R, E, A])
      extends AnyVal {

    /**
     * Enables to check conditions in the value produced by ZPure
     * If the condition is not satisfied, it fails with NoSuchElementException
     * this provide the syntax sugar in for-comprehension:
     * for {
     *   (i, j) <- zpure1
     *   positive <- zpure2 if positive > 0
     *  } yield ()
     */
    def withFilter(predicate: A => Boolean)(implicit ev: CanFilter[E]): ZPure[W, S1, S2, R, E, A] =
      self.flatMap { a =>
        if (predicate(a)) ZPure.succeed(a)
        else ZPure.fail(ev(new NoSuchElementException("The value doesn't satisfy the predicate")))
      }
  }

  @silent("never used")
  private object Tags {
    final val FlatMap = 0
    final val Succeed = 1
    final val Fail    = 2
    final val Fold    = 3
    final val Access  = 4
    final val Provide = 5
    final val Modify  = 6
    final val Log     = 7
    final val Flag    = 8
  }

  private final case class Succeed[+A](value: A)                     extends ZPure[Nothing, Unit, Unit, Any, Nothing, A]   {
    override def tag: Int = Tags.Succeed
  }
  private final case class Fail[+E](error: Cause[E])                 extends ZPure[Nothing, Any, Nothing, Any, E, Nothing] {
    override def tag: Int = Tags.Fail
  }
  private final case class Modify[-S1, +S2, +A](run0: S1 => (S2, A)) extends ZPure[Nothing, S1, S2, Any, Nothing, A]       {
    override def tag: Int = Tags.Modify
  }
  private final case class FlatMap[+W, S1, S2, S3, S4, -R, +E, A, +B](
    value: ZPure[W, S1, S2, R, E, A],
    continue: A => ZPure[W, S3, S4, R, E, B],
    compose: ComposeState[S1, S2, S3, S4]
  )                                                                  extends ZPure[W, Any, Nothing, R, E, B]               {
    override def tag: Int = Tags.FlatMap
  }
  private final case class Fold[+W, S1, S2, S3, S4, S5, S6, -R, E1, +E2, A, +B](
    value: ZPure[W, S1, S2, R, E1, A],
    failure: Cause[E1] => ZPure[W, S3, S4, R, E2, B],
    success: A => ZPure[W, S5, S6, R, E2, B],
    fold: FoldState[S1, S2, S3, S4, S5, S6]
  )                                                                  extends ZPure[W, Any, Nothing, R, E2, B]
      with Function[A, ZPure[Any, Any, Any, Any, Any, Any]] {
    override def tag: Int                                         = Tags.Fold
    override def apply(a: A): ZPure[Any, Any, Any, Any, Any, Any] =
      success(a).asInstanceOf[ZPure[Any, Any, Any, Any, Any, Any]]
  }
  private final case class Access[W, S1, S2, R, E, A](access: R => ZPure[W, S1, S2, R, E, A])
      extends ZPure[W, S1, S2, R, E, A]                     {
    override def tag: Int = Tags.Access
  }
  private final case class Provide[W, S1, S2, R, E, A](r: R, continue: ZPure[W, S1, S2, R, E, A])
      extends ZPure[W, S1, S2, Any, E, A]                   {
    override def tag: Int = Tags.Provide
  }
  private final case class Log[S, +W](log: W) extends ZPure[W, S, S, Any, Nothing, Unit] {
    override def tag: Int = Tags.Log
  }
  private final case class Flag[W, S1, S2, R, E, A](
    flag: FlagType,
    value: Boolean,
    continue: ZPure[W, S1, S2, R, E, A]
  ) extends ZPure[W, S1, S2, R, E, A] {
    override def tag: Int = Tags.Flag
  }

  sealed trait FlagType
  object FlagType {
    case object ClearLogOnError extends FlagType
  }
}

trait ZPureLowPriorityImplicits {

  /**
   * The `CommutativeBoth` instance for `ZPure`.
   */
  implicit def ZPureCommutativeBoth[W, S, R, E]
    : CommutativeBoth[({ type lambda[+A] = ZPure[W, S, S, R, E, A] })#lambda] =
    new CommutativeBoth[({ type lambda[+A] = ZPure[W, S, S, R, E, A] })#lambda] {
      def both[A, B](fa: => ZPure[W, S, S, R, E, A], fb: => ZPure[W, S, S, R, E, B]): ZPure[W, S, S, R, E, (A, B)] =
        ZPure.tupledPar(fa, fb)
    }
}
