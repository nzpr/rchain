package coop.rchain.sdk.serialize

import cats.Applicative
import cats.syntax.all._
import coop.rchain.sdk.primitive.ByteArray
import scodec.bits.ByteVector

/**
  * Specification for serialization of primitive types.
  */
trait SerializePrimitiveTypes[F[_]] {
  implicit def serializeUnit(implicit applicative: Applicative[F]): Serialize[F, Unit] =
    new Serialize[F, Unit] {
      override def write(x: Unit): PrimitiveWriter[F] => F[Unit] =
        (_: PrimitiveWriter[F]) => ().pure[F]

      override def read: PrimitiveReader[F] => F[Unit] =
        (_: PrimitiveReader[F]) => ().pure[F]
    }

  implicit def serializeInt: Serialize[F, Int] =
    new Serialize[F, Int] {
      override def write(x: Int): PrimitiveWriter[F] => F[Unit] =
        (w: PrimitiveWriter[F]) => w.write(x)

      override def read: PrimitiveReader[F] => F[Int] =
        (r: PrimitiveReader[F]) => r.readInt
    }

  implicit def serializeLong: Serialize[F, Long] =
    new Serialize[F, Long] {
      override def write(x: Long): PrimitiveWriter[F] => F[Unit] =
        (w: PrimitiveWriter[F]) => w.write(x)

      override def read: PrimitiveReader[F] => F[Long] =
        (r: PrimitiveReader[F]) => r.readLong
    }

  implicit def serializeArrayByte: Serialize[F, Array[Byte]] =
    new Serialize[F, Array[Byte]] {
      override def write(x: Array[Byte]): PrimitiveWriter[F] => F[Unit] =
        (w: PrimitiveWriter[F]) => w.write(x)

      override def read: PrimitiveReader[F] => F[Array[Byte]] =
        (r: PrimitiveReader[F]) => r.readBytes
    }

  implicit def serializeBool: Serialize[F, Boolean] =
    new Serialize[F, Boolean] {
      override def write(x: Boolean): PrimitiveWriter[F] => F[Unit] =
        (w: PrimitiveWriter[F]) => w.write(x)

      override def read: PrimitiveReader[F] => F[Boolean] =
        (r: PrimitiveReader[F]) => r.readBool
    }

  implicit def serializeString: Serialize[F, String] =
    new Serialize[F, String] {
      override def write(x: String): PrimitiveWriter[F] => F[Unit] =
        (w: PrimitiveWriter[F]) => w.write(x)

      override def read: PrimitiveReader[F] => F[String] =
        (r: PrimitiveReader[F]) => r.readString
    }

  // Since ByteArray constructor is private, Magnolia cannot derive typeclass, so it has to be defined manually
  implicit def serializeByteArray(implicit applicative: Applicative[F]): Serialize[F, ByteArray] =
    new Serialize[F, ByteArray] {
      override def write(x: ByteArray): PrimitiveWriter[F] => F[Unit] =
        (w: PrimitiveWriter[F]) => w.write(x.bytes)

      override def read: PrimitiveReader[F] => F[ByteArray] =
        (r: PrimitiveReader[F]) => r.readBytes.map(ByteArray(_))
    }

  implicit def serializeByteVector(implicit applicative: Applicative[F]): Serialize[F, ByteVector] =
    new Serialize[F, ByteVector] {
      override def write(x: ByteVector): PrimitiveWriter[F] => F[Unit] =
        (w: PrimitiveWriter[F]) => w.write(x.toArray)

      override def read: PrimitiveReader[F] => F[ByteVector] =
        (r: PrimitiveReader[F]) => r.readBytes.map(ByteVector.apply)
    }
}
