package coop.rchain.comm.rpc

import cats.effect.std.Dispatcher
import io.grpc.{Metadata, ServerCall, ServerCallHandler, Status}

object RpcHandler {
  def apply[F[_], Req, Resp](
      callback: Req => F[Resp], // request and remote address
      dispatcher: Dispatcher[F]
  ): ServerCallHandler[Req, Resp] = new ServerCallHandler[Req, Resp] {
    override def startCall(
        call: ServerCall[Req, Resp],
        headers: Metadata
    ): ServerCall.Listener[Req] = {

      // Number of messages to read next from the response (default is no read at all)
      call.request(1)

      new ServerCall.Listener[Req] {
        override def onMessage(message: Req): Unit = {
          call.sendHeaders(headers)
          val result = dispatcher.unsafeRunSync(callback(message))
          call.sendMessage(result)
          call.close(Status.OK, headers)
        }

        override def onHalfClose(): Unit = super.onHalfClose()
        override def onCancel(): Unit    = super.onCancel()
        override def onComplete(): Unit  = super.onComplete()
      }
    }
  }
}
