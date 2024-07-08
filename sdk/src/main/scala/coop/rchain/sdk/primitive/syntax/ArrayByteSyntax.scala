package coop.rchain.sdk.primitive.syntax

import coop.rchain.sdk.primitive.ByteArray
import coop.rchain.sdk.syntax.all._
import java.nio.ByteBuffer

trait ArrayByteSyntax {
  implicit def sdkSyntaxArrayByte(x: Array[Byte]): ArrayByteOps = new ArrayByteOps(x)

  implicit val o: Ordering[Array[Byte]] = (a: Array[Byte], b: Array[Byte]) =>
    java.util.Arrays.compare(a, b)
}

final class ArrayByteOps(private val x: Array[Byte]) extends AnyVal {

  /**
    * Wrap the copy of array with the ByteBuffer.
    * */
  def toByteBuffer: ByteBuffer = ByteBuffer.wrap(x.clone())
  def toHex: String            = ByteArray(x).toHex
}
