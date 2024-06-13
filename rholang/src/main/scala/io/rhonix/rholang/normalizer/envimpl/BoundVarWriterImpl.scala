package io.rhonix.rholang.normalizer.envimpl

import coop.rchain.rholang.interpreter.compiler.{IdContext, SourcePosition}
import io.rhonix.rholang.normalizer.env.BoundVarWriter

final case class BoundVarWriterImpl[T](private val putFn: (String, T, SourcePosition) => Int)
    extends BoundVarWriter[T] {

  override def putBoundVars(bindings: Seq[IdContext[T]]): Seq[Int] = {
    // Insert all bindings into the bound map
    val indices = bindings.map(putFn.tupled(_))

    // Find indices that haven't been shadowed by the new bindings
    val names        = bindings.map(_._1)
    val indexedNames = names.zip(indices)
    val unShadowedIndexedNames = indexedNames
      .foldRight(List.empty[(String, Int)]) {
        case ((name, index), acc) =>
          if (acc.exists(_._1 == name)) acc else (name, index) :: acc
      }
    unShadowedIndexedNames.map(_._2)
  }
}
