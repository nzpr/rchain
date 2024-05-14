package coop.rchain.blockstorage.dag

import cats.Monad
import cats.effect.{Ref, Sync}
import cats.syntax.all._
import coop.rchain.casper.PrettyPrinter
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.BlockMetadata
import coop.rchain.models.syntax.modelsSyntaxByteString
import coop.rchain.sdk.error.FatalError
import coop.rchain.shared.Log
import coop.rchain.shared.syntax._
import coop.rchain.store.KeyValueTypedStore

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap

object BlockMetadataStore {
  def apply[F[_]: Sync: Log](
      blockMetadataStore: KeyValueTypedStore[F, BlockHash, BlockMetadata],
      lfsSetStore: KeyValueTypedStore[F, BlockHash, Unit]
  ): F[BlockMetadataStore[F]] =
    for {
      lfsSet <- lfsSetStore.toMap.map(_.keySet)
      _      <- Log[F].info(s"Loading blocks metadata (${lfsSet.size} blocks).")
      // Iterate over block metadata store and collect info for in-memory cache
      blockInfoMap <- blockMetadataStore
                       .get(lfsSet.toList)
                       .map(_.flatten.map(x => x.blockHash -> blockMetadataToInfo(x)).toMap)
      _ <- new FatalError(
            s"Missing block metadata required: ${(lfsSet -- blockInfoMap.keySet).map(_.toHexString.take(8))}"
          ).raiseError.whenA(blockInfoMap.size != lfsSet.size)
      _ <- Log[F].info("Loading blocks metadata done.")
      // garbage can be in the lfsSetStore if node shut down after block is added but before GC-ed blocks
      // are removed from lfsSetStore
      (dagState, garbage) = recreateInMemoryState(blockInfoMap)
      _                   <- lfsSetStore.delete(garbage.toSeq)
      _ <- Log[F]
            .info(s"Lfs set store contains ${garbage.size} garbage records. Cleaning up.")
            .whenA(garbage.nonEmpty)
      _           <- Log[F].info("Successfully built in-memory blockMetadataStore.")
      dagStateRef <- Ref[F].of(dagState)
    } yield new BlockMetadataStore[F](blockMetadataStore, lfsSetStore, dagStateRef)

  final case class BlockMetadataStoreInconsistencyError(message: String) extends Exception(message)

  private final case class DagState(
      dagSet: Set[BlockHash],
      childMap: Map[BlockHash, Set[BlockHash]],
      heightMap: SortedMap[Long, Set[BlockHash]]
  )

  def blockMetadataToInfo(blockMeta: BlockMetadata): BlockInfo =
    BlockInfo(
      blockMeta.blockHash,
      blockMeta.justifications,
      blockMeta.blockNum,
      blockMeta.validationFailed
    )

  class BlockMetadataStore[F[_]: Monad](
      private val store: KeyValueTypedStore[F, BlockHash, BlockMetadata],
      val lfsSetStore: KeyValueTypedStore[F, BlockHash, Unit], // store for latest messages
      private val dagState: Ref[F, DagState]
  ) {
    def gc(toRemove: List[BlockHash])(implicit f: Sync[F]): F[Unit] =
      store.get(toRemove).flatMap { rMeta =>
        dagState.update { st =>
          DagState(
            dagSet = st.dagSet -- toRemove,
            childMap = st.childMap -- toRemove,
            heightMap = rMeta.foldLeft(st.heightMap) {
              case (acc, Some(m)) =>
                acc.updated(m.blockNum, acc.get(m.blockNum).map(_ - m.blockHash).getOrElse(Set()))
              case (acc, None) => acc
            }
          )
        }
      }

    def add(block: BlockMetadata): F[Unit] =
      for {
        // Update DAG state with new block
        _ <- dagState.update { st =>
              val blockInfo   = blockMetadataToInfo(block)
              val newDagState = addBlockToDagState(blockInfo)(st)
              validateDagState(newDagState)._1
            }

        // Update persistent block metadata store
        _ <- store.put(block.blockHash, block)

        // add to LFS set. It will be removed when GC event happens.
        _ <- lfsSetStore.put(block.blockHash, ())
      } yield ()

    def get(hash: BlockHash): F[Option[BlockMetadata]] = store.get1(hash)

    def getUnsafe(hash: BlockHash)(
        implicit f: Sync[F],
        line: sourcecode.Line,
        file: sourcecode.File,
        enclosing: sourcecode.Enclosing
    ): F[BlockMetadata] = {
      def source = s"${file.value}:${line.value} ${enclosing.value}"
      def errMsg =
        s"BlockMetadataStore is missing key ${PrettyPrinter.buildString(hash)}\n  $source"
      get(hash) >>= (_.liftTo(BlockMetadataStoreInconsistencyError(errMsg)))
    }

    // DAG state operations

    def dagSet: F[Set[BlockHash]] = dagState.get.map(_.dagSet)

    def contains(hash: BlockHash): F[Boolean] = dagState.get.map(_.dagSet.contains(hash))

    def childMapData: F[Map[BlockHash, Set[BlockHash]]] =
      dagState.get.map(_.childMap)

    def heightMap: F[SortedMap[Long, Set[BlockHash]]] =
      dagState.get.map(_.heightMap)
  }

  private def addBlockToDagState(block: BlockInfo)(state: DagState): DagState = {
    // Update dag set / all blocks in the DAG
    val newDagSet = state.dagSet + block.hash

    // Update children relation map
    val blockChilds = block.parents.map((_, Set(block.hash))) + ((block.hash, Set()))
    val newChildMap = blockChilds.foldLeft(state.childMap) {
      case (acc, (key, newChildren)) =>
        val currChildren = acc.getOrElse(key, Set.empty[BlockHash])
        acc.updated(key, currChildren ++ newChildren)
    }

    // Update block height map
    val newHeightMap = if (!block.validationFailed) {
      val currSet = state.heightMap.getOrElse(block.blockNum, Set())
      state.heightMap.updated(block.blockNum, currSet + block.hash)
    } else state.heightMap

    state.copy(
      dagSet = newDagSet,
      childMap = newChildMap,
      heightMap = newHeightMap
    )
  }

  @tailrec
  private def validateDagState(
      state: DagState,
      invalidAcc: Set[BlockHash] = Set()
  ): (DagState, Set[BlockHash]) = {
    // Validate height map index (block numbers) are in sequence without holes
    // genesis should always stay in the DB, and it should not be included in this check
    val m          = state.heightMap.filterNot(_._1 == 0)
    val (min, max) = if (m.nonEmpty) (m.firstKey, m.lastKey + 1) else (0L, 0L)
    if (max - min == m.size.toLong) (state, invalidAcc)
    else {
      val (height, toRemove) = state.heightMap.head
      validateDagState(
        state.copy(
          dagSet = state.dagSet -- toRemove,
          heightMap = state.heightMap - height,
          childMap = state.childMap -- toRemove
        ),
        invalidAcc ++ toRemove
      )
    }
//    assert(
//      max - min == m.size.toLong,
//      s"DAG store height map has numbers not in sequence. \nheightMap size ${m.size.toLong}: $m \nmax $max \nmin $min "
//    )

  }

  // Used to project part of the block metadata for in-memory initialization
  final case class BlockInfo(
      hash: BlockHash,
      parents: Set[BlockHash],
      blockNum: Long,
      validationFailed: Boolean
  )

  private def recreateInMemoryState(
      blocksInfoMap: Map[BlockHash, BlockInfo]
  ): (DagState, Set[BlockHash]) = {
    val emptyState: DagState =
      DagState(
        dagSet = Set(),
        childMap = Map(),
        heightMap = SortedMap()
      )

    // Add blocks to DAG state
    val dagState = blocksInfoMap.foldLeft(emptyState) {
      case (state, (_, block)) => addBlockToDagState(block)(state)
    }

    validateDagState(dagState)
  }
}
