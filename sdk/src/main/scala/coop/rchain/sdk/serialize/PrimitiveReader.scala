package coop.rchain.sdk.serialize

trait PrimitiveReader[F[_]] {
  def readByte: F[Byte]
  def readBytes: F[Array[Byte]]
  def readBool: F[Boolean]
  def readInt: F[Int]
  def readLong: F[Long]
  def readString: F[String]
}
