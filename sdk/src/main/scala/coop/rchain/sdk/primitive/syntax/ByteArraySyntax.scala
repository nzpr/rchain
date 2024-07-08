package coop.rchain.sdk.primitive.syntax

import coop.rchain.sdk.codecs.Base16
import coop.rchain.sdk.primitive.ByteArray
import coop.rchain.sdk.syntax.all.sdkSyntaxArrayByte

import java.nio.ByteBuffer

trait ByteArraySyntax {
  implicit def sdkSyntaxByteArray(x: ByteArray): ByteArrayOps = new ByteArrayOps(x)
}

final class ByteArrayOps(private val x: ByteArray) extends AnyVal {
  def size: Int = length

  def length: Int = x.bytes.length

  def nonEmpty: Boolean = x.bytes.nonEmpty

  def isEmpty: Boolean = x.bytes.isEmpty

  def head: Byte = x.bytes.head

  def headOption: Option[Byte] = x.bytes.headOption

  /**
    * Wrap the tail of underlying array of this ByteArray with the new ByteArray.
    * NOTE: resulting ByteArray shares memory with origin.
    * */
  def tail: ByteArray = ByteArray(x.bytes.tail)

  /**
    * The element at given index.
    * */
  def apply(i: Int): Byte = x.bytes(i)

  /**
    * A copy of this array with one single replaced element.
    * */
  def updated(idx: Int, b: Byte): ByteArray = ByteArray(x.bytes.updated(idx: Int, b: Byte))

  /**
    * Create ByteArray from copy of this array concatenated with the argument.
    * */
  def ++(other: ByteArray): ByteArray = ByteArray(x.bytes ++ other.bytes)

  /**
    * Create ByteArray from copy of this array with the argument prepended.
    * */
  def +:(byte: Byte): ByteArray = ByteArray(byte +: x.bytes)

  /**
    * Create ByteArray from copy of this array with the argument appended.
    * */
  def :+(byte: Byte): ByteArray = ByteArray(x.bytes :+ byte)

  /**
    * Converts the content of this byte vector to a hexadecimal string of size * 2 nibbles.
    * */
  def toHex: String = Base16.encode(x.bytes)

  /**
    * Wrap the copy of underlying array with the ByteBuffer.
    * */
  def toByteBuffer: ByteBuffer = x.bytes.toByteBuffer.asReadOnlyBuffer()

  /**
    * Allocate direct buffer and fill with the content of ByteArray.
    * LMDB works only with direct buffers.
    * */
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def toDirectByteBuffer: ByteBuffer = {
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(x.bytes.length)
    buffer.put(x.bytes)
    buffer.flip()
    buffer
  }
}
