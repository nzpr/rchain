package coop.rchain.state

import java.nio.ByteBuffer

trait TrieImporter[F[_]] {
  // Type of the key to uniquely defines the trie / in RSpace this is the hash of the trie
  type KeyHash
  type Result

  // Set history values / branch nodes in the trie
  def setHistoryItems[Value](
      keys: Seq[(KeyHash, Value)],
      toBuffer: Value => ByteBuffer
  ): F[Result]

  // Set data values / leaf nodes in the trie
  def setDataItems[Value](
      keys: Seq[(KeyHash, Value)],
      toBuffer: Value => ByteBuffer
  ): F[Result]
}
