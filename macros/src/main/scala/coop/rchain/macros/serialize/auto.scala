package coop.rchain.macros.serialize

import cats.{Applicative, Eval}
import coop.rchain.sdk.serialize._

/**
  * Automatic derivation of instances for stack safe serialization using Eval.
  *
  * {{{
  * Usage:
  *
  * // Define your class
  * final case class MyObject(a: Int, b: String)
  *
  * // Import derivation macro
  * import sdk.serialize.auto.*
  *
  * // Instance is derived automatically.
  * val x = implicitly[Serialize[Eval, MyObject]]
  * }}}
  *
  * In case of missing implicit error if instance cannot be derived,
  * make sure that all fields of the class that are primitives (Int, String, etc)
  * or sum types (Option, List, etc) have instances of Serialize in the specification
  * defined in [[sdk.codecs.SerializePrimitiveTypes]] and [[sdk.codecs.SerializeSumTypes]], respectively.
  */
object auto
    extends SerializePrimitiveTypes[Eval]
    with SerializeSumTypes[Eval]
    with SerializeDerivation[Eval] {
  implicit override val appF: Applicative[Eval] = Eval.catsBimonadForEval
}
