package zio
package interop.guava

import com.google.common.util.concurrent.{Futures, ListenableFuture}
import zio.test.Assertion._
import zio.test._

import java.util.concurrent.Executors
import java.util.{concurrent => juc}

object GuavaSpec extends DefaultRunnableSpec {
  def spec: ZSpec[Environment, Failure] = suite("GuavaSpec")(
    suite("`Task.fromListenableFuture` must")(
      test("be lazy on the `Future` parameter") {
        var evaluated                   = false
        def ftr: ListenableFuture[Unit] =
          Futures.submitAsync(
            { () =>
              evaluated = true
              Futures.immediateFuture(())
            },
            Executors.newCachedThreadPool()
          )
        assertM(Task.fromListenableFuture(UIO.succeed(ftr)).when(false).as(evaluated))(isFalse)
      },
      test("catch exceptions thrown by make block") {
        val ex                                                    = new Exception("no future for you!")
        lazy val noFuture: juc.Executor => ListenableFuture[Unit] = _ => throw ex
        assertM(Task.fromListenableFuture(noFuture).exit)(fails(equalTo(ex)))
      },
      test("return an `IO` that fails if `Future` fails 1") {
        val ex                                   = new Exception("no value for you!")
        val noValue: UIO[ListenableFuture[Unit]] = UIO.succeed(Futures.immediateFailedFuture(ex))
        assertM(Task.fromListenableFuture(noValue).exit)(fails[Throwable](equalTo(ex)))
      },
      test("return an `IO` that fails if `Future` fails 2") {
        val ex                                   = new Exception("no value for you!")
        val noValue: UIO[ListenableFuture[Unit]] =
          UIO.succeed(Futures.submitAsync(() => Futures.immediateFailedFuture(ex), Executors.newCachedThreadPool()))
        assertM(Task.fromListenableFuture(noValue).exit)(fails[Throwable](equalTo(ex)))
      },
      test("return an `IO` that produces the value from `Future`") {
        val someValue: UIO[ListenableFuture[Int]] =
          UIO.succeed(Futures.submitAsync(() => Futures.immediateFuture(42), Executors.newCachedThreadPool()))
        assertM(Task.fromListenableFuture(someValue).exit)(succeeds(equalTo(42)))
      },
      test("handle null produced by the completed `Future`") {
        val someValue: UIO[ListenableFuture[String]] = UIO.succeed(Futures.immediateFuture[String](null))
        assertM(Task.fromListenableFuture[String](someValue).map(Option(_)))(isNone)
      },
      test("be referentially transparent") {
        var n    = 0
        val task = ZIO.fromListenableFuture(
          UIO.succeed(Futures.submitAsync(() => Futures.immediateFuture(n += 1), Executors.newCachedThreadPool()))
        )
        for {
          _ <- task
          _ <- task
        } yield assert(n)(equalTo(2))
      }
    ),
    suite("`Task.toListenableFuture` must")(
      test("produce always a successful `IO` of `Future`") {
        val failedIO = IO.fail[Throwable](new Exception("IOs also can fail"))
        assertM(failedIO.toListenableFuture)(isSubtype[ListenableFuture[Unit]](anything))
      },
      test("be polymorphic in error type") {
        val unitIO: Task[Unit]                         = Task.unit
        val polyIO: IO[String, ListenableFuture[Unit]] = unitIO.toListenableFuture
        val _                                          = polyIO // avoid warning
        assert(polyIO)(anything)
      },
      test("return a `ListenableFuture` that fails if `IO` fails") {
        val ex                       = new Exception("IOs also can fail")
        val failedIO: Task[Unit]     = IO.fail[Throwable](ex)
        val failedFuture: Task[Unit] = failedIO.toListenableFuture.flatMap(f => Task(f.get()))
        assertM(failedFuture.exit)(
          fails[Throwable](hasField("message", _.getMessage, equalTo("java.lang.Exception: IOs also can fail")))
        )
      },
      test("return a `ListenableFuture` that produces the value from `IO`") {
        val someIO = Task.succeed[Int](42)
        assertM(someIO.toListenableFuture.map(_.get()))(equalTo(42))
      }
    ),
    suite("`Task.toListenableFutureWith` must")(
      test("convert error of type `E` to `Throwable`") {
        val failedIO: IO[String, Unit] = IO.fail[String]("IOs also can fail")
        val failedFuture: Task[Unit]   = failedIO.toListenableFutureWith(new Exception(_)).flatMap(f => Task(f.get()))
        assertM(failedFuture.exit)(
          fails[Throwable](hasField("message", _.getMessage, equalTo("java.lang.Exception: IOs also can fail")))
        )
      }
    ),
    suite("`Fiber.fromListenableFuture` must")(
      test("be lazy on the `Future` parameter") {
        var evaluated                   = false
        def ftr: ListenableFuture[Unit] =
          Futures.submitAsync(
            { () =>
              evaluated = true
              Futures.immediateFuture(())
            },
            Executors.newCachedThreadPool()
          )
        Fiber.fromListenableFuture(ftr)
        assert(evaluated)(isFalse)
      },
      test("catch exceptions thrown by lazy block") {
        val ex                               = new Exception("no future for you!")
        def noFuture: ListenableFuture[Unit] = throw ex
        assertM(Fiber.fromListenableFuture(noFuture).join.exit)(fails(equalTo(ex)))
      },
      test("return an `IO` that fails if `Future` fails 1") {
        val ex                              = new Exception("no value for you!")
        def noValue: ListenableFuture[Unit] = Futures.immediateFailedFuture(ex)
        assertM(Fiber.fromListenableFuture(noValue).join.exit)(fails[Throwable](equalTo(ex)))
      },
      test("return an `IO` that fails if `Future` fails 2") {
        val ex                              = new Exception("no value for you!")
        def noValue: ListenableFuture[Unit] =
          Futures.submitAsync(() => Futures.immediateFailedFuture(ex), Executors.newCachedThreadPool())
        assertM(Fiber.fromListenableFuture(noValue).join.exit)(fails[Throwable](equalTo(ex)))
      },
      test("return an `IO` that produces the value from `Future`") {
        def someValue: ListenableFuture[Int] = Futures.immediateFuture(42)
        assertM(Fiber.fromListenableFuture(someValue).join.exit)(succeeds(equalTo(42)))
      }
    )
  )
}
