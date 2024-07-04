package coop.rchain.node.runtime

import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.effect.{Resource, Sync}
import cats.syntax.all._
import coop.rchain.casper.merging.BlockIndex
import coop.rchain.comm.rpc.{BlockIndexEndpoint, RootPathString, RpcHandler, RpcMethod}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.shared.Log
import io.grpc.netty.NettyServerBuilder
import io.grpc.{Server, ServerServiceDefinition}

import java.net.InetSocketAddress

object RpcServer {

  import coop.rchain.casper.serialize.auto._
  import coop.rchain.macros.serialize.auto._

  def apply[F[_]: Async](
      port: Int,
      maxMessageSize: Int,
      getIndex: BlockHash => F[BlockIndex]
  ): Resource[F, Server] =
    Dispatcher.sequential[F].flatMap { dispatcher =>
      val serviceDefinition: ServerServiceDefinition = ServerServiceDefinition
        .builder(RootPathString)
        .addMethod(
          RpcMethod[BlockHash, BlockIndex](BlockIndexEndpoint),
          RpcHandler(getIndex, dispatcher)
        )
        .build()

      Resource.make(
        Sync[F].delay {
          NettyServerBuilder
            .forAddress(new InetSocketAddress("0.0.0.0", port))
            .maxInboundMessageSize(maxMessageSize)
            .addService(serviceDefinition)
            .build
            .start()
        } <* Log.log[F].info(s"Low level RPC engine started on port $port.")
      ) { server =>
        Sync[F].delay(server.shutdown().awaitTermination()) *> Log
          .log[F]
          .info(s"Low level RPC engine stopped.")
      }
    }
}
