package io.rhonix.rholang.normalizer

import io.rhonix.rholang.normalizer.syntax.*

package object syntax { object all extends AllSyntaxNormalizer }

trait AllSyntaxNormalizer extends NormalizerSyntax with HistoryChainSyntax
