package coop.rchain.models

import cats.Show
import com.google.protobuf.ByteString
import coop.rchain.models.syntax.modelsSyntaxByteString

object BlockHash {
  type BlockHash = ByteString

  val Length = 32

  implicit val showBlockHash = Show.show[BlockHash](_.toHexString)
}
