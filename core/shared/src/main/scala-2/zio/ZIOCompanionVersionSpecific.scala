package zio

import zio.ZIO.{Async, ZIOError, asyncInterrupt, blocking}

import java.io.IOException

trait ZIOCompanionVersionSpecific {

  /**
   * Converts an asynchronous, callback-style API into a ZIO effect, which will
   * be executed asynchronously.
   *
   * This method allows you to specify the fiber id that is responsible for
   * invoking callbacks provided to the `register` function. This is called the
   * "blocking fiber", because it is stopping the fiber executing the async
   * effect from making progress (although it is not "blocking" a thread).
   * Specifying this fiber id in cases where it is known will improve
   * diagnostics, but not affect the behavior of the returned effect.
   */
  def async[R, E, A](
    register: (ZIO[R, E, A] => Unit) => Any,
    blockingOn: => FiberId = FiberId.None
  )(implicit trace: Trace): ZIO[R, E, A] =
    Async(trace, register, () => blockingOn)

  /**
   * Converts an asynchronous, callback-style API into a ZIO effect, which will
   * be executed asynchronously.
   *
   * With this variant, you can specify either a way to cancel the asynchrounous
   * action, or you can return the result right away if no asynchronous
   * operation is required.
   *
   * This method allows you to specify the fiber id that is responsible for
   * invoking callbacks provided to the `register` function. This is called the
   * "blocking fiber", because it is stopping the fiber executing the async
   * effect from making progress (although it is not "blocking" a thread).
   * Specifying this fiber id in cases where it is known will improve
   * diagnostics, but not affect the behavior of the returned effect.
   */
  def asyncInterrupt[R, E, A](
    register: (ZIO[R, E, A] => Unit) => Either[URIO[R, Any], ZIO[R, E, A]],
    blockingOn: => FiberId = FiberId.None
  )(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.suspendSucceed {
      val cancelerRef = Ref.unsafe.make[URIO[R, Any]](ZIO.unit)(Unsafe.unsafe)

      ZIO
        .async[R, E, A](
          { k =>
            val result = register(k(_))

            result match {
              case Left(canceler) => cancelerRef.unsafe.set(canceler)(Unsafe.unsafe)
              case Right(done)    => k(done)
            }
          },
          blockingOn
        )
        .onInterrupt(cancelerRef.unsafe.get(Unsafe.unsafe))
    }

  /**
   * Converts an asynchronous, callback-style API into a ZIO effect, which will
   * be executed asynchronously.
   *
   * With this variant, the registration function may return the result right
   * away, if it turns out that no asynchronous operation is required to
   * complete the operation.
   *
   * This method allows you to specify the fiber id that is responsible for
   * invoking callbacks provided to the `register` function. This is called the
   * "blocking fiber", because it is stopping the fiber executing the async
   * effect from making progress (although it is not "blocking" a thread).
   * Specifying this fiber id in cases where it is known will improve
   * diagnostics, but not affect the behavior of the returned effect.
   */
  def asyncMaybe[R, E, A](
    register: (ZIO[R, E, A] => Unit) => Option[ZIO[R, E, A]],
    blockingOn: => FiberId = FiberId.None
  )(implicit trace: Trace): ZIO[R, E, A] =
    asyncInterrupt(
      register(_) match {
        case None      => Left(ZIO.unit)
        case Some(now) => Right(now)
      },
      blockingOn
    )

  /**
   * Returns an effect that, when executed, will cautiously run the provided
   * code, catching any exception and translated it into a failed ZIO effect.
   *
   * This method should be used whenever you want to take arbitrary code, which
   * may throw exceptions or not be type safe, and convert it into a ZIO effect,
   * which can safely execute that code whenever the effect is executed.
   *
   * {{{
   * def printLine(line: String): Task[Unit] = ZIO.attempt(println(line))
   * }}}
   */
  def attempt[A](code: => A)(implicit trace: Trace): Task[A] =
    ZIO.withFiberRuntime[Any, Throwable, A] { (fiberState, _) =>
      try {
        val result = code

        ZIO.succeedNow(result)
      } catch {
        case t: Throwable if !fiberState.isFatal(t)(Unsafe.unsafe) => throw ZIOError.Traced(Cause.fail(t))
      }
    }

  /**
   * Returns an effect that, when executed, will cautiously run the provided
   * code, catching any exception and translated it into a failed ZIO effect.
   *
   * This method should be used whenever you want to take arbitrary code, which
   * may throw exceptions or not be type safe, and convert it into a ZIO effect,
   * which can safely execute that code whenever the effect is executed.
   *
   * This variant expects that the provided code will engage in blocking I/O,
   * and therefore, pro-actively executes the code on a dedicated blocking
   * thread pool, so it won't interfere with the main thread pool that ZIO uses.
   */
  def attemptBlocking[A](effect: => A)(implicit trace: Trace): Task[A] =
    ZIO.blocking(ZIO.attempt(effect))

  /**
   * Returns an effect that, when executed, will cautiously run the provided
   * code, catching any exception and translated it into a failed ZIO effect.
   *
   * This method should be used whenever you want to take arbitrary code, which
   * may throw exceptions or not be type safe, and convert it into a ZIO effect,
   * which can safely execute that code whenever the effect is executed.
   *
   * This variant expects that the provided code will engage in blocking I/O,
   * and therefore, pro-actively executes the code on a dedicated blocking
   * thread pool, so it won't interfere with the main thread pool that ZIO uses.
   *
   * Additionally, this variant allows you to specify an effect that will cancel
   * the blocking operation. This effect will be executed if the fiber that is
   * executing the blocking effect is interrupted for any reason.
   */
  def attemptBlockingCancelable[R, A](effect: => A)(cancel: => URIO[R, Any])(implicit trace: Trace): RIO[R, A] =
    ZIO.blocking(ZIO.attempt(effect)).fork.flatMap(_.join).onInterrupt(cancel)

  /**
   * This function is the same as `attempt`, except that it only exposes
   * `IOException`, treating any other exception as fatal.
   */
  def attemptBlockingIO[A](effect: => A)(implicit trace: Trace): IO[IOException, A] =
    attemptBlocking(effect).refineToOrDie[IOException]

  /**
   * Returns an effect that models success with the specified value.
   */
  def succeed[A](a: => A)(implicit trace: Trace): ZIO[Any, Nothing, A] =
    ZIO.Sync(trace, () => a)

  /**
   * Returns a synchronous effect that does blocking and succeeds with the
   * specified value.
   */
  def succeedBlocking[A](a: => A)(implicit trace: Trace): UIO[A] =
    ZIO.blocking(ZIO.succeed(a))
}
