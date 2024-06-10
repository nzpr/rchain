package coop.rchain.casper.serialize

import cats.Applicative
import com.google.protobuf.ByteString
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.sdk.serialize.{PrimitiveReader, PrimitiveWriter, Serialize}
import cats.syntax.all._

trait PrimitiveSerializers[F[_]] {
  implicit val appF: Applicative[F]
  // Serializers for custom primitive types (from external libraries)
  implicit val serializeByteString: Serialize[F, ByteString] =
    new Serialize[F, ByteString] {
      override def write(x: ByteString): PrimitiveWriter[F] => F[Unit] =
        (w: PrimitiveWriter[F]) => w.write(x.toByteArray)

      override def read: PrimitiveReader[F] => F[ByteString] =
        (r: PrimitiveReader[F]) => r.readBytes.map(ByteString.copyFrom)
    }

  implicit val serializeBlakeHash: Serialize[F, Blake2b256Hash] =
    new Serialize[F, Blake2b256Hash] {
      override def write(x: Blake2b256Hash): PrimitiveWriter[F] => F[Unit] =
        (w: PrimitiveWriter[F]) => w.write(x.bytes.toArray)

      override def read: PrimitiveReader[F] => F[Blake2b256Hash] =
        (r: PrimitiveReader[F]) => r.readBytes.map(Blake2b256Hash.fromByteArray)
    }
}
