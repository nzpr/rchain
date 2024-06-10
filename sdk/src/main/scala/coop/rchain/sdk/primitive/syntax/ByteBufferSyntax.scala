package coop.rchain.sdk.primitive.syntax

import java.nio.ByteBuffer

trait ByteBufferSyntax {
  implicit def sdkSyntaxByteBuffer(x: ByteBuffer): ByteBufferOps = new ByteBufferOps(x)
}

final class ByteBufferOps(private val x: ByteBuffer) extends AnyVal {

  /**
    * Allocate new array of bytes and fill with the copy of ByteBuffer.
    * */
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def toArray: Array[Byte] = {
    val c   = x.duplicate()
    val arr = new Array[Byte](c.remaining)
    c.get(arr)
    arr
  }
}
