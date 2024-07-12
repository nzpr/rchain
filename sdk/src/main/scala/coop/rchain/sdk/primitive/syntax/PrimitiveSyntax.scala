package coop.rchain.sdk.primitive.syntax

import cats.syntax.TrySyntax

trait PrimitiveSyntax
    extends ByteArraySyntax
    with MapSyntax
    with ThrowableSyntax
    with TrySyntax
    with VoidSyntax
    with ByteBufferSyntax
    with ArrayByteSyntax
