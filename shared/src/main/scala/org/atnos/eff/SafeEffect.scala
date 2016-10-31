package org.atnos.eff

import cats._
import cats.data._
import cats.implicits._
import eff._
import interpret._
import EitherEffect._

import scala.reflect.ClassTag

trait SafeEffect extends
  SafeCreation with
  SafeInterpretation

object SafeEffect extends SafeEffect

trait SafeTypes {

  type _Safe[R] = Safe <= R
  type _safe[R] = Safe |= R
}

trait SafeCreation extends SafeTypes {

  def protect[R :_safe, A](a: =>A): Eff[R, A] =
    send[Safe, R, A](EvaluateValue[A](Eval.later(a)))

  def eval[R :_safe , A](a: Eval[A]): Eff[R, A] =
    send[Safe, R, A](EvaluateValue[A](a))

  def exception[R :_safe, A](t: Throwable): Eff[R, A] =
    send[Safe, R, A](FailedValue(t))

  def finalizerException[R :_safe](t: Throwable): Eff[R, Unit] =
    send[Safe, R, Unit](FailedFinalizer(t))
}

trait SafeInterpretation extends SafeCreation { outer =>

  /**
   * Run a safe effect
   *
   * Collect finalizer exceptions if any
   */
  def runSafe[R, U, A](r: Eff[R, A])(implicit m: Member.Aux[Safe, R, U]): Eff[U, (ThrowableEither[A], List[Throwable])] = {
    type Out = (ThrowableEither[A], Vector[Throwable])
    interpretLoop1[R, U, Safe, A, Out]((a: A) => (Right(a), Vector.empty): Out)(safeLoop[R, U, A])(r).map { case (a, vs) => (a, vs.toList) }
  }

  /** run a safe effect but drop the finalizer errors */
  def execSafe[R, U, A](r: Eff[R, A])(implicit m: Member.Aux[Safe, R, U]): Eff[U, ThrowableEither[A]] =
    runSafe(r).map(_._1)

  /**
   * Attempt to execute a safe action including finalizers
   */
  def attemptSafe[R, A](r: Eff[R, A])(implicit m: Safe <= R): Eff[R, (ThrowableEither[A], List[Throwable])] = {
    type Out = (ThrowableEither[A], Vector[Throwable])
    interceptLoop1[R, Safe, A, Out]((a: A) => (Right(a), Vector.empty): Out)(safeLoop[R, R, A])(r).map { case (a, vs) => (a, vs.toList) }
  }

  def safeLoop[R, U, A]: Loop[Safe, R, A, Eff[U, (Throwable Either A, Vector[Throwable])]] = {
    type Out = (ThrowableEither[A], Vector[Throwable])

    new Loop[Safe, R, A, Eff[U, Out]] {
      type S = Vector[Throwable]
      val init: S = Vector.empty[Throwable]

      def onPure(a: A, s: S): (Eff[R, A], S) Either Eff[U, Out] =
        Right(pure((Right(a), s)))

      def onEffect[X](sx: Safe[X], continuation: Arrs[R, X, A], s: S): (Eff[R, A], S) Either Eff[U, Out] =
        sx match {
          case EvaluateValue(v) =>
            Either.catchNonFatal(v.value) match {
              case Left(e) =>
                Right(pure((Left(e), s)))

              case Right(x) =>
                Either.catchNonFatal(continuation(x)) match {
                  case Left(e) => Right(pure((Left(e), s)))
                  case Right(c) =>  Left((c, s))
                }
            }

          case FailedValue(t) =>
            Right(pure((Left(t), s)))

          case FailedFinalizer(t) =>
            Either.catchNonFatal(continuation(())) match {
              case Left(e) => Right(pure((Left(e), s :+ t)))
              case Right(c) =>  Left((c, s :+ t))
            }
        }

      def onApplicativeEffect[X, T[_] : Traverse](xs: T[Safe[X]], continuation: Arrs[R, T[X], A], s: S): (Eff[R, A], S) Either Eff[U, Out] =  {
        type F = (Vector[Throwable], Option[Throwable])

        val traversed: State[F, T[X]] = xs.traverse {
          case FailedFinalizer(t) => State { case (o, n) => ((o :+ t, n),       ().asInstanceOf[X]) }
          case FailedValue(t)     => State { case (o, n) => ((o,      Some(t)), ().asInstanceOf[X]) }
          case EvaluateValue(v)   => State { case (o, n) =>
            n match {
              case None =>
                Either.catchNonFatal(v.value) match {
                  case Right(a) => ((o, None), a)
                  case Left(t)  => ((o, Option(t)), ().asInstanceOf[X])
                }
              case Some(_) => ((o, n), ().asInstanceOf[X])
            }
          }
        }

        val ((o, n), tx) = traversed.run((s, None)).value
        n match {
          case Some(t) => Right(pure((Left(t), o)))
          case None    => Left((continuation(tx), o))
        }
      }
    }
  }


  /**
   * evaluate 1 action possibly having error effects
   * execute a second action whether the first is successful or not but keep track of finalizer exceptions
   */
  def thenFinally[R, A](action: Eff[R, A], last: Eff[R, Unit])(implicit m: Safe <= R): Eff[R, A] = {
    val loop = new StatelessLoop[Safe, R, A, Eff[R, A]] {
      def onPure(a: A): Eff[R, A] Either Eff[R, A] =
        Right(attempt(last) flatMap {
          case Left(t)   => outer.finalizerException[R](t) >> pure(a)
          case Right(()) => pure(a)
        })

      def onEffect[X](sx: Safe[X], continuation: Arrs[R, X, A]): Eff[R, A] Either Eff[R, A] =
        sx match {
          case EvaluateValue(v) =>
            Either.catchNonFatal(v.value) match {
              case Left(e) =>
                Right(attempt(last) flatMap {
                  case Left(t)   => outer.finalizerException[R](t) >> outer.exception[R, A](e)
                  case Right(()) => outer.exception[R, A](e)
                })

              case Right(x) =>
                Left(attempt(last) flatMap {
                  case Left(t)   => outer.finalizerException[R](t) >> continuation(x)
                  case Right(()) => continuation(x)
                })
            }

          case FailedValue(t) =>
            Right(outer.exception(t))

          case FailedFinalizer(t) =>
            Right(outer.finalizerException(t) >> continuation(()))
        }

      def onApplicativeEffect[X, T[_] : Traverse](xs: T[Safe[X]], continuation: Arrs[R, T[X], A]): Eff[R, A] Either Eff[R, A] = {
        // all the values are executed because they are considered to be independent in the applicative case
        type F = Vector[FailedValue[X]]
        val executed: State[F, T[X]] = xs.traverse {
          case EvaluateValue(a) =>
            Either.catchNonFatal(a.value) match {
              case Left(t)  => State { failed => (failed :+ FailedValue(t), ().asInstanceOf[X]) }
              case Right(x) => State { failed => (failed, x) }
            }
          case FailedValue(t)     => State { failed => (failed :+ FailedValue(t), ().asInstanceOf[X]) }
          case FailedFinalizer(t) => State { failed => (failed, ().asInstanceOf[X]) }
        }

        val (failures, successes) = executed.run(Vector.empty[FailedValue[X]]).value

        failures.toList match {
          case Nil =>
            Left(continuation(successes))

          case FailedValue(throwable) :: rest =>
            // we just return the first failed value as an exception
            Right(attempt(last) flatMap {
              case Left(t)   => outer.finalizerException[R](t) >> outer.exception[R, A](throwable)
              case Right(()) => exception[R, A](throwable)
            })
        }
      }
    }

    interceptStatelessLoop1[R, Safe, A, A]((a: A) => a)(loop)(action)
  }

  def bracket[R, A, B, C](acquire: Eff[R, A])(step: A => Eff[R, B])(release: A => Eff[R, C])(implicit m: Safe <= R): Eff[R, B] =
    for {
      a <- acquire
      b <- thenFinally(step(a), release(a).void)
    } yield b

  /**
   * evaluate 1 action possibly having error effects
   *
   * Execute a second action if the first one is not successful
   */
  def otherwise[R, A](action: Eff[R, A], onThrowable: Eff[R, A])(implicit m: Safe <= R): Eff[R, A] =
    whenFailed(action, _ => onThrowable)

  /**
   * evaluate 1 action possibly having error effects
   *
   * Execute a second action if the first one is not successful, based on the error
   */
  def catchThrowable[R, A, B](action: Eff[R, A], pureValue: A => B, onThrowable: Throwable => Eff[R, B])(implicit m: Safe <= R): Eff[R, B] =
    attemptSafe(action).flatMap {
      case (Left(t), ls)  => onThrowable(t).flatMap(b => ls.traverse(f => finalizerException(f)).as(b))
      case (Right(a), ls) => pure(pureValue(a)).flatMap(b => ls.traverse(f => finalizerException(f)).as(b))
    }

  /**
   * evaluate 1 action possibly throwing exceptions
   *
   * Execute a second action if the first one is not successful, based on the exception
   *
   * The final value type is the same as the original type
   */
  def whenFailed[R, A](action: Eff[R, A], onThrowable: Throwable => Eff[R, A])(implicit m: Safe <= R): Eff[R, A] =
    catchThrowable(action, identity[A], onThrowable)

  /**
   * try to execute an action an report any issue
   */
  def attempt[R, A](action: Eff[R, A])(implicit m: Safe <= R): Eff[R, Throwable Either A] =
    catchThrowable(action, Right[Throwable, A], (t: Throwable) => pure(Left(t)))

  /**
   * ignore one possible exception that could be thrown
   */
  def ignoreException[R, E <: Throwable : ClassTag, A](action: Eff[R, A])(implicit m: Safe <= R): Eff[R, Unit] =
    catchThrowable[R, A, Unit](action, (a: A) => (), {
      case t if implicitly[ClassTag[E]].runtimeClass.isInstance(t) => pure(())
      case t => outer.exception(t)
    })

}

object SafeInterpretation extends SafeInterpretation

/**
 * The Safe type is a mix of a ThrowableEither / Eval effect
 *   and a writer effect to collect finalizer failures
 */
sealed trait Safe[A]

case class EvaluateValue[A](a: Eval[A])  extends Safe[A]
case class FailedValue[A](t: Throwable)  extends Safe[A]
case class FailedFinalizer(t: Throwable) extends Safe[Unit]
