package coop.rchain

import coop.rchain.metrics.Metrics
import coop.rchain.rspace.history.syntax.HistoryRepositorySyntax

package object rspace {
  val RSpaceMetricsSource: Metrics.Source = Metrics.Source(Metrics.BaseSource, "rspace")
  object syntax extends AllSyntaxRSpaceHistory

}
trait AllSyntaxRSpaceHistory extends HistoryRepositorySyntax
