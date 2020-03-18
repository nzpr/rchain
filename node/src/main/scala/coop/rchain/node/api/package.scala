package coop.rchain.node

import cats.effect.Concurrent

import coop.rchain.casper.protocol.deploy.v1.DeployServiceV1GrpcMonix
import coop.rchain.casper.protocol.propose.v1.ProposeServiceV1GrpcMonix
import coop.rchain.catscontrib._
import coop.rchain.grpc.{GrpcServer, Server}
import coop.rchain.node.model.repl._
import coop.rchain.shared._

import io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionService
import monix.eval.Task
import monix.execution.Scheduler

package object api {

  // 16 MB is max message size allowed by HTTP2 RFC 7540
  // grpc and netty can however work with bigger values
  val maxMessageSize: Int = 16 * 1024 * 1024

  def acquireInternalServer(
      port: Int,
      ioScheduler: Scheduler,
      replGrpcService: ReplGrpcMonix.Repl,
      deployGrpcService: DeployServiceV1GrpcMonix.DeployService,
      proposeGrpcService: ProposeServiceV1GrpcMonix.ProposeService
  ): Task[Server[Task]] =
    GrpcServer[Task](
      NettyServerBuilder
        .forPort(port)
        .executor(ioScheduler)
        .maxMessageSize(maxMessageSize)
        .addService(
          ReplGrpcMonix.bindService(replGrpcService, ioScheduler)
        )
        .addService(
          ProposeServiceV1GrpcMonix
            .bindService(proposeGrpcService, ioScheduler)
        )
        .addService(
          DeployServiceV1GrpcMonix
            .bindService(deployGrpcService, ioScheduler)
        )
        .addService(ProtoReflectionService.newInstance())
        .build
    )

  def acquireExternalServer[F[_]: Concurrent: Log: Taskable](
      port: Int,
      ioScheduler: Scheduler,
      deployGrpcService: DeployServiceV1GrpcMonix.DeployService
  ): F[Server[F]] =
    GrpcServer[F](
      NettyServerBuilder
        .forPort(port)
        .executor(ioScheduler)
        .maxMessageSize(maxMessageSize)
        .addService(
          DeployServiceV1GrpcMonix
            .bindService(deployGrpcService, ioScheduler)
        )
        .addService(ProtoReflectionService.newInstance())
        .build
    )
}
