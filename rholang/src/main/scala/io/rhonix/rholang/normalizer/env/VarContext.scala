package io.rhonix.rholang.normalizer.env

import coop.rchain.rholang.interpreter.compiler.SourcePosition

final case class VarContext[T](index: Int, typ: T, sourcePosition: SourcePosition)
