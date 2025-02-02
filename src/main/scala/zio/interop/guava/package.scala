/*
 * Copyright 2017-2019 John A. De Goes and the ZIO Contributors
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

package zio.interop

import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import zio._

import java.util.concurrent.{CancellationException, CompletionException}
import java.util.{concurrent => juc}
import scala.concurrent.ExecutionException

package object guava {

  private def catchFromGet(isFatal: Throwable => Boolean): PartialFunction[Throwable, Task[Nothing]] = {
    case e: CompletionException   =>
      Task.fail(e.getCause)
    case e: ExecutionException    =>
      Task.fail(e.getCause)
    case _: InterruptedException  =>
      Task.interrupt
    case _: CancellationException =>
      Task.interrupt
    case e if !isFatal(e)         =>
      Task.fail(e)
  }

  private def unwrapDone[A](isFatal: Throwable => Boolean)(f: ListenableFuture[A]): Task[A] =
    try Task.succeedNow(f.get())
    catch catchFromGet(isFatal)

  def fromListenableFuture[A](make: juc.Executor => ListenableFuture[A]): Task[A] =
    Task.suspendSucceedWith { (p, _) =>
      val ex: juc.Executor = p.executor.asExecutionContext.execute(_)
      Task.attempt(make(ex)).flatMap { lf =>
        if (lf.isDone)
          unwrapDone(p.fatal)(lf)
        else
          Task.asyncInterrupt { cb =>
            val fcb = new FutureCallback[A] {
              def onFailure(t: Throwable): Unit = cb(catchFromGet(p.fatal).lift(t).getOrElse(Task.die(t)))

              def onSuccess(result: A): Unit = cb(Task.succeedNow(result))
            }
            Futures.addCallback(lf, fcb, ex)
            Left(UIO(lf.cancel(false)))
          }
      }
    }

  def fromListenableFuture[A](lfUio: UIO[ListenableFuture[A]]): Task[A] =
    lfUio.flatMap(lf => fromListenableFuture(_ => lf))

  implicit class ListenableFutureOps[A](private val lfUio: UIO[ListenableFuture[A]]) extends AnyVal {
    def toZio: Task[A] = Task.fromListenableFuture(lfUio)
  }

  implicit class TaskObjListenableFutureOps(private val taskObj: Task.type) extends AnyVal {
    def fromListenableFuture[A](make: juc.Executor => ListenableFuture[A]): Task[A] =
      guava.fromListenableFuture(make)

    def fromListenableFuture[A](lfUio: UIO[ListenableFuture[A]]): Task[A] =
      guava.fromListenableFuture(lfUio)
  }

  implicit class ZioObjListenableFutureOps(private val zioObj: ZIO.type) extends AnyVal {
    def fromListenableFuture[A](make: juc.Executor => ListenableFuture[A]): Task[A] =
      guava.fromListenableFuture(make)

    def fromListenableFuture[A](lfUio: UIO[ListenableFuture[A]]): Task[A] =
      guava.fromListenableFuture(lfUio)
  }

  implicit class FiberObjOps(private val fiberObj: Fiber.type) extends AnyVal {
    def fromListenableFuture[A](thunk: => ListenableFuture[A]): Fiber[Throwable, A] = {
      lazy val lf: ListenableFuture[A] = thunk

      new Fiber.Synthetic.Internal[Throwable, A] {

        override def await(implicit trace: ZTraceElement): UIO[Exit[Throwable, A]] =
          Task.fromListenableFuture(_ => lf).exit

        def children(implicit trace: ZTraceElement): UIO[Chunk[Fiber.Runtime[_, _]]] = ZIO.succeedNow(Chunk.empty)

        override def poll(implicit trace: ZTraceElement): UIO[Option[Exit[Throwable, A]]] =
          UIO.suspendSucceed {
            if (lf.isDone)
              Task
                .suspendWith((p, _) => unwrapDone(p.fatal)(lf))
                .fold(Exit.fail, Exit.succeed)
                .map(Some(_))
            else
              UIO.succeedNow(None)
          }

        final def getRef[A](ref: FiberRef.Runtime[A])(implicit trace: ZTraceElement): UIO[A] = ref.get

        def id: FiberId = FiberId.None

        final def interruptAs(fiberId: FiberId)(implicit trace: ZTraceElement): UIO[Exit[Throwable, A]] =
          UIO(lf.cancel(false)) *> join.fold(Exit.fail, Exit.succeed)

        final def inheritRefs(implicit trace: ZTraceElement): UIO[Unit] = UIO.unit

      }
    }
  }

  implicit class TaskListenableFutureOps[A](private val io: Task[A]) extends AnyVal {
    def toListenableFuture: UIO[ListenableFuture[A]] =
      io.fold(Futures.immediateFailedFuture[A], Futures.immediateFuture[A])
  }

  implicit class IOListenableFutureOps[E, A](private val io: IO[E, A]) extends AnyVal {
    def toListenableFutureWith(f: E => Throwable): UIO[ListenableFuture[A]] =
      io.mapError(f).toListenableFuture
  }
}
