package org.http4s
package blaze
package websocket

import fs2.async.mutable.Signal
import org.http4s.websocket.WebsocketBits._

import scala.util.{Failure, Success}
import org.http4s.blaze.pipeline.stages.SerializingStage
import org.http4s.blaze.util.Execution.{directec, trampoline}
//import org.http4s.internal.compatibility._
import org.http4s.{websocket => ws4s}

//import scalaz.concurrent._
//import scalaz.{\/, \/-, -\/}
import fs2.async
import fs2._

import pipeline.{TrunkBuilder, LeafBuilder, Command, TailStage}
import pipeline.Command.EOF

class Http4sWSStage(ws: ws4s.Websocket) extends TailStage[WebSocketFrame] {
  // FIXME it is probably not right to set the strategy here in stone
  implicit val strategy = fs2.Strategy.fromFixedDaemonPool(8, threadName = "worker")
  def name: String = "Http4s WebSocket Stage"

  def log[A](prefix: String): Pipe[Task, A, A] = _.evalMap{a => Task.delay {println(s"$prefix> $a"); a} }

  private val dead: Task[Signal[Task, Boolean]] = async.signalOf[Task, Boolean](false)

  //////////////////////// Source and Sink generators ////////////////////////

  def snk: Sink[Task, WebSocketFrame] = _.evalMap { frame =>
    Task.async[Unit] { cb =>
      channelWrite(frame).onComplete {
        case Success(res) => cb(Right(res))
        case Failure(t@Command.EOF) => cb(Left(t))
        case Failure(t) => cb(Left(t))
      }(directec)
    }
  }

  def inputstream: Stream[Task, WebSocketFrame] = {
    val t = Task.async[WebSocketFrame] { cb =>
      def go(): Unit = channelRead().onComplete {
        case Success(ws) => ws match {
            case Close(_)    =>
              for {
                _ <- dead.map(_.set(true))
              } yield {
                sendOutboundCommand(Command.Disconnect)
                cb(Left(new RuntimeException("a")))
              }

            // TODO: do we expect ping frames here?
            case Ping(d)     =>  channelWrite(Pong(d)).onComplete {
              case Success(_)   => go()
              case Failure(EOF) => cb(Left(new RuntimeException("b")))
              case Failure(t)   => cb(Left(t))
            }(trampoline)

            case Pong(_)     => go()
            case f           => cb(Right(f))
          }

        case Failure(Command.EOF) => cb(Left(new RuntimeException("c")))
        case Failure(e)           => cb(Left(e))
      }(trampoline)

      go()
    }
    Stream.repeatEval(t)//.onHalt(_.asHalt)
  }

  //////////////////////// Startup and Shutdown ////////////////////////

  override protected def stageStartup(): Unit = {
    super.stageStartup()

    // A latch for shutting down if both streams are closed.
    val count = new java.util.concurrent.atomic.AtomicInteger(2)

    val onFinish: Either[Throwable,Any] => Unit = {
      case Right(_) =>
        logger.trace("WebSocket finish signaled")
        if (count.decrementAndGet() == 0) {
          logger.trace("Closing WebSocket")
          sendOutboundCommand(Command.Disconnect)
        }
      case Left(t) =>
        logger.error(t)("WebSocket Exception")
        sendOutboundCommand(Command.Disconnect)
    }

    /*dead.map(_.discrete.drain)//(wye.interrupt).run.unsafePerformAsync(onFinish) */
    /*
    // The sink is a bit more complicated
    val discard: Sink[Task, WebSocketFrame] = Process.constant(_ => Task.now(()))*/

    // if we never expect to get a message, we need to make sure the sink signals closed
    val routeSink: Sink[Task, WebSocketFrame] = ws.write match {
      //case Process.Halt(Cause.End) => onFinish(\/-(())); discard
      //case Process.Halt(e)   => onFinish(-\/(Cause.Terminated(e))); ws.exchange.write
      case s => s// ++ Process.await(Task{onFinish(\/-(()))})(_ => discard)
    }

    (inputstream.through(log("inputStream")).to(routeSink) mergeHaltBoth ws.read.through(log("output")).to(snk).drain).run.unsafeRunAsyncFuture()
  }

  override protected def stageShutdown(): Unit = {
    dead.map(_.set(true)).unsafeRun
    super.stageShutdown()
  }
}

object Http4sWSStage {
  def bufferingSegment(stage: Http4sWSStage): LeafBuilder[WebSocketFrame] = {
    TrunkBuilder(new SerializingStage[WebSocketFrame]).cap(stage)
  }
}
