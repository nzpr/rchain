package coop.rchain.comm.rpc

import cats.Eval
import com.google.protobuf.CodedOutputStream
import coop.rchain.comm.rpc.Serialize.{parseProtobuf, streamProtobuf}
import coop.rchain.models.rholangn.parmanager.protobuf.{ProtoPrimitiveReader, ProtoPrimitiveWriter}
import coop.rchain.sdk.serialize.Serialize
import io.grpc.MethodDescriptor
import org.apache.commons.io.input.QueueInputStream

import java.io.InputStream

/**
  * Constructor for Grpc method descriptor.
  * Used both for server and client.
  * */
object RpcMethod {
  // We have to hardcode here effect type since this is the only way to connect
  // the code with parametrised effect type with gRPC implementation.
  // Current serializer implementation is based on Eval monad.
  type F[A] = Eval[A]

  def apply[A, B](
      endpointName: String
  )(implicit sA: Serialize[F, A], sB: Serialize[F, B]): MethodDescriptor[A, B] =
    MethodDescriptor
      .newBuilder()
      .setType(MethodDescriptor.MethodType.UNARY)
      .setFullMethodName(s"$RootPathString/$endpointName")
      .setRequestMarshaller(
        new MethodDescriptor.Marshaller[A] {
          override def stream(obj: A): InputStream       = streamProtobuf(obj)(sA)
          override def parse(byteStream: InputStream): A = parseProtobuf(byteStream)(sA)
        }
      )
      .setResponseMarshaller(new MethodDescriptor.Marshaller[B] {
        override def stream(obj: B): InputStream       = streamProtobuf(obj)(sB)
        override def parse(byteStream: InputStream): B = parseProtobuf(byteStream)(sB)
      })
      .build()
}
