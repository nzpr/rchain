package io.rhonix.rholang.normalizer.envimpl

import io.rhonix.rholang.normalizer.env.NestingInfoReader

final class NestingInfoReaderImpl(
    private val insidePatternStatusFn: () => Boolean,
    private val insideTopLevelReceivePatternStatusFn: () => Boolean,
    private val insideBundleStatusFn: () => Boolean
) extends NestingInfoReader {

  override def insidePattern: Boolean = insidePatternStatusFn()

  override def insideTopLevelReceivePattern: Boolean = insideTopLevelReceivePatternStatusFn()

  override def insideBundle: Boolean = insideBundleStatusFn()
}

object NestingInfoReaderImpl {
  def apply(
      insidePatternFn: () => Boolean,
      insideTopLevelReceivePatternFn: () => Boolean,
      insideBundleFn: () => Boolean
  ): NestingInfoReaderImpl =
    new NestingInfoReaderImpl(insidePatternFn, insideTopLevelReceivePatternFn, insideBundleFn)
}
