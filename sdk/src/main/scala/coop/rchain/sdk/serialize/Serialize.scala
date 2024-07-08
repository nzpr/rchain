package coop.rchain.sdk.serialize

trait Serialize[F[_], A] {
  def write(x: A): PrimitiveWriter[F] => F[Unit]
  def read: PrimitiveReader[F] => F[A]
}
