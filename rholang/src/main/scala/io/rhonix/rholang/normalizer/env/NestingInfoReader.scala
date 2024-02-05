package io.rhonix.rholang.normalizer.env

/** Retrieve information about nesting structure during normalization. */
trait NestingInfoReader {

  /** Current processing is being executed within a pattern. */
  def insidePattern: Boolean

  /** Current processing is being executed within a receive pattern
    *  and this receive is not inside any other pattern */
  def insideTopLevelReceivePattern: Boolean

  /** Current processing is being executed within a bundle*/
  def insideBundle: Boolean
}
