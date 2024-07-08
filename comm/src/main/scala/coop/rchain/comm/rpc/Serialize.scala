package coop.rchain.comm.rpc

import cats.Eval
import com.google.protobuf.CodedOutputStream
import coop.rchain.comm.rpc.RpcMethod.F
import coop.rchain.models.rholangn.parmanager.protobuf.{ProtoPrimitiveReader, ProtoPrimitiveWriter}
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.sdk.serialize.Serialize
import org.apache.commons.io.input.QueueInputStream

import java.io.InputStream

object Serialize {
  // Stream using protobuf format
  def streamProtobuf[A](obj: A)(implicit sA: Serialize[Eval, A]): InputStream = {
    // piped input stream
    val pipeIn      = new QueueInputStream()
    val pipeOut     = pipeIn.newQueueOutputStream()
    val protoStream = CodedOutputStream.newInstance(pipeOut)
    // writer that writes to output stream that pipes to input stream
    // write object using protobuf primitive writer
    sA.write(obj)(ProtoPrimitiveWriter.apply(protoStream)).value
    // flush and close
    protoStream.flush()
    pipeOut.flush()
    pipeOut.close()
    pipeIn
  }

  // Parse using protobuf format
  def parseProtobuf[A](is: InputStream)(implicit sA: Serialize[Eval, A]): A = {
    val reader = ProtoPrimitiveReader.apply(is)
    sA.read(reader).value
  }

  def getProtoHash[A](obj: A)(implicit sA: Serialize[Eval, A]): Blake2b256Hash =
    Blake2b256Hash.create(streamProtobuf(obj).readAllBytes())
}
