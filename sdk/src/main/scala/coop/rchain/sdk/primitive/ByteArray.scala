package coop.rchain.sdk.primitive

import coop.rchain.sdk.syntax.all._

import java.nio.ByteBuffer
import java.util

sealed class ByteArray private (underlying: Array[Byte]) {

  /**
    * Array of bytes underlying for this ByteArray.
    * NOTE: modifying the resulting array will also modify this ByteArray.
    * */
  def bytes: Array[Byte] = underlying

  override def equals(other: Any): Boolean = other match {
    case other: ByteArray => util.Arrays.equals(this.underlying, other.bytes)
    case _                => false
  }

  override def hashCode(): Int = util.Arrays.hashCode(underlying)

  // To see pretty hash when errors happen
  override def toString: String = this.toHex
}

object ByteArray {

  // Ordering uses Java comparison of underlying bytes
  implicit val ordering: Ordering[ByteArray] =
    (x: ByteArray, y: ByteArray) => util.Arrays.compare(x.bytes, y.bytes)

  val Default: ByteArray = ByteArray(Array.empty[Byte])

  /**
    * Wrap array of bytes with the ByteArray.
    * NOTE: modifying the input array will also modify the instance created, since input is not cloned.
    * */
  def apply(x: Array[Byte]): ByteArray = new ByteArray(x)

  /**
    * Copy immutable sequence of bytes into ByteArray.
    * */
  def apply(x: Seq[Byte]): ByteArray = ByteArray(x.toArray)

  /**
    * Copy byte into ByteArray.
    * */
  def apply(x: Byte): ByteArray = new ByteArray(Array(x))

  /**
    * Copy content of ByteBuffer into ByteArray.
    * */
  def apply(buffer: ByteBuffer): ByteArray = ByteArray(buffer.toArray)
}
