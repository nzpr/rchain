package coop.rchain.casper.serialize

import cats.{Applicative, Eval}

object auto extends OrderingRules with PrimitiveSerializers[Eval] {
  implicit override val appF: Applicative[Eval] = Eval.catsBimonadForEval
}
