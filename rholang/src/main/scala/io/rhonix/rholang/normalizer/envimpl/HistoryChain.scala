package io.rhonix.rholang.normalizer.envimpl

import sdk.syntax.all.*

/**
  * A class representing a history chain of generic type T.
  * Note: T should be immutable.
  */
final class HistoryChain[T](private val chain: collection.mutable.ListBuffer[T]) {

  /**
    * Retrieve the current (last) element of the chain.
    * @return the last element of the chain
    */
  def current(): T = chain.last

  /**
    * Retrieve the depth (length) of the chain.
    * @return the length of the chain
    */
  def depth: Int = chain.length

  /**
    * Retrieve an iterator for the chain.
    * @return an iterator for the chain
    */
  def iter: Iterator[T] = chain.iterator

  /**
    * Append a new element to the end of the chain.
    * @param t the element to be appended
    */
  def push(t: T): Unit = chain.append(t).void()

  /**
    * Append a copy of the last element to the end of the chain.
    *
    */
  def pushCopy(): Unit = this.push(chain.last)

  /**
    * Remove and return the last element of the chain.
    * @return the removed last element of the chain
    */
  def pop(): T = chain.remove(chain.length - 1)
}

object HistoryChain {

  /**
    * Create an empty HistoryChain.
    * @return a new HistoryChain with an empty ListBuffer
    */
  def empty[T]: HistoryChain[T] = new HistoryChain(collection.mutable.ListBuffer[T]())

  /**
    * Create a HistoryChain with predefined Seq.
    * @param seq the Seq of elements to be added to the HistoryChain
    * @return a new HistoryChain with a ListBuffer containing the elements of the Seq
    */
  def apply[T](seq: Seq[T]): HistoryChain[T] =
    new HistoryChain(collection.mutable.ListBuffer[T]() ++= seq)
}
