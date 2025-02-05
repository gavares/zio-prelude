package zio.prelude

import zio.NonEmptyChunk

trait NewtypeCompanionVersionSpecific {
  def assert[A](assertion: Assertion[A]): QuotedAssertion[A] = macro zio.prelude.Macros.refine_impl[A]

  /**
   * Converts an instance of the underlying type to an instance of the
   * newtype, ignoring any [[Assertion]].
   */
  def unsafeWrap[A, T <: NewtypeModule#Newtype[A]](newtype: T, value: A): T#Type = {
    val _ = newtype
    value.asInstanceOf[T#Type]
  }

  /**
   * Converts an instance of a type parameterized on the underlying type
   * to an instance of a type parameterized on the newtype, ignoring any
   * [[Assertion]]. For example, this could be used to convert a list of
   * instances of the underlying type to a list of instances of the newtype.
   */
  def unsafeWrapAll[F[_], A, T <: NewtypeModule#Newtype[A]](newtype: T, value: F[A]): F[T#Type] = {
    val _ = newtype
    value.asInstanceOf[F[T#Type]]
  }
}

trait NewtypeVersionSpecific[A] { self: NewtypeModule#Newtype[A] =>

  /**
   * Converts an instance of the underlying type to an instance of the newtype.
   *
   * If there is a `def assertion` (see [[assert]]), the value will be checked
   * at compile-time.
   */
  def apply(value: A): Type = macro zio.prelude.Macros.wrap_impl[A, Type]

  /**
   * Converts multiple instances of the underlying type to [[NonEmptyChunk]] of
   * instances of the newtype.
   *
   * If there is a `def assertion` (see [[assert]]), each value will be checked
   * at compile-time.
   */
  def apply(value: A, values: A*): NonEmptyChunk[Type] = macro zio.prelude.Macros.applyMany_impl[A, Type]

  def make(value: A): Validation[String, Type] = macro zio.prelude.Macros.make_impl[A, Type]

  def makeAll[F[+_]: ForEach](value: F[A]): Validation[String, F[Type]] =
    macro zio.prelude.Macros.makeAll_impl[F, A, Type]

  /**
   * This method is used to generate Newtype that can be validated at
   * compile-time. This must wrap a [[Assertion]] and be assigned to
   * `def assertion`.
   *
   * For example, here is a refined Newtype for Natural numbers. Natural
   * numbers are whole numbers greater than or equal to 0.
   *
   * {{{
   * import zio.prelude.Subtype
   * import zio.prelude.Assertion._
   *
   * type Natural = Natural.Type
   * object Natural extends Subtype[Int] {
   *   def assertion = assert(greaterThanOrEqualTo(0))
   * }
   * }}}
   *
   * With this `assertion` defined, `Natural.apply` will check literal values
   * at compile-time, failing with an error message if the Assertion is not
   * satisfied.
   *
   * `Natural(-10)` would render "`-10 failed to satisfy greaterThanOrEqualTo(10)`"
   *
   * IMPORTANT: Due to the macro machinery powering this feature, you must be
   * sure to NOT ANNOTATE `def assertion` with a type (`QuotedAssertion`). If
   * you do so, the macro will not be able to run the provided assertion at
   * compile-time and will fail with a message containing this very same
   * information.
   */
  def assert(assertion: Assertion[A]): QuotedAssertion[A] = macro zio.prelude.Macros.refine_impl[A]

  /**
   * Converts an instance of a type parameterized on the underlying type
   * to an instance of a type parameterized on the newtype. For example,
   * this could be used to convert a list of instances of the underlying
   * type to a list of instances of the newtype.
   *
   * Due to macro limitations, this method cannot with refined newtype and
   * will thus issue a compiler error if you attempt to do so.
   */
  def wrapAll[F[_]](value: F[A]): F[Type] = macro zio.prelude.Macros.wrapAll_impl[F, A, Type]

}
