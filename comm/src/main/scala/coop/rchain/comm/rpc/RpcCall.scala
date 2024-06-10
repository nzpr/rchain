package coop.rchain.comm.rpc

import cats.effect.{Async, Sync}
import io.grpc.{CallOptions, Channel, ClientCall, Metadata, MethodDescriptor, Status}

import scala.concurrent.Promise

trait RpcCall[F[_], Req, Resp] {
  def invoke(method: MethodDescriptor[Req, Resp], msg: Req, channel: Channel): F[Resp]
}

object RpcCall {
  private def callFailure(errMsg: String, cause: Throwable): Throwable =
    new RuntimeException(errMsg).initCause(cause)

  def apply[F[_]: Async, Req, Resp]: RpcCall[F, Req, Resp] = new RpcCall[F, Req, Resp] {
    override def invoke(
        method: MethodDescriptor[Req, Resp],
        msg: Req,
        channel: Channel
    ): F[Resp] = {
      val futureF = Sync[F].delay {
        val call = channel.newCall[Req, Resp](method, CallOptions.DEFAULT)

        val promise = Promise[Resp]()

        val callListener = new ClientCall.Listener[Resp] {
          override def onHeaders(headers: Metadata): Unit = super.onHeaders(headers)

          override def onReady(): Unit = super.onReady()

          override def onMessage(message: Resp): Unit = {
            val _ = promise.success(message)
            super.onMessage(message)
          }

          override def onClose(status: Status, trailers: Metadata): Unit = {
            val _ =
              if (status != Status.OK)
                promise.failure(
                  callFailure(
                    s"Failed to send message $msg through channel $channel",
                    status.asRuntimeException()
                  )
                )
          }
        }

        call.start(callListener, new Metadata())

        call.sendMessage(msg)

        call.request(1)

        call.halfClose()

        promise.future
      }

      Async[F].fromFuture(futureF)
    }
  }
}
