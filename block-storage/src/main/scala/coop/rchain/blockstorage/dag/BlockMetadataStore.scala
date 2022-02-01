package coop.rchain.blockstorage.dag

import cats.data.OptionT
import cats.{Monad, Show}
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.{Deferred, Ref}
import cats.mtl.MonadState
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.dag.BlockDagStorage.DagFringe
import coop.rchain.casper.PrettyPrinter
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.BlockMetadata
import coop.rchain.models.Validator.Validator
import coop.rchain.models.block.StateHash.StateHash
import coop.rchain.shared.syntax._
import coop.rchain.shared.{AtomicMonadState, Log}
import coop.rchain.store.KeyValueTypedStore
import monix.execution.atomic.AtomicAny

import scala.collection.immutable.SortedMap
import scala.collection.mutable

object BlockMetadataStore {
  def apply[F[_]: Concurrent: Log](
      blockMetadataStore: KeyValueTypedStore[F, BlockHash, BlockMetadata],
      latestFringesStore: KeyValueTypedStore[F, Long, DagFringe]
  ): F[BlockMetadataStore[F]] =
    for {
      _ <- Log[F].info("Building in-memory blockMetadataStore.")
      // Iterate over block metadata store and collect info for in-memory cache
      blockInfoMap <- blockMetadataStore.collect {
                       case (hash, metaData) =>
                         (hash, blockMetadataToInfo(metaData()))
                     }
      _        <- Log[F].info("Reading data from blockMetadataStore done.")
      dagState = recreateInMemoryState(blockInfoMap.toMap)
      _        <- Log[F].info("Successfully built in-memory blockMetadataStore.")
      fringes  <- latestFringesStore.toMap
      ds       = dagState.copy(fringesMap = SortedMap[Long, DagFringe]() ++ fringes.toList)
      cache    <- Ref.of[F, Map[BlockHash, Deferred[F, Option[BlockMetadata]]]](Map())
    } yield new BlockMetadataStore[F](
      blockMetadataStore,
      cache,
      new AtomicMonadState(AtomicAny(ds))
    )

  final case class BlockMetadataStoreInconsistencyError(message: String) extends Exception(message)

  private final case class DagState(
      dagSet: Set[BlockHash],
      childMap: Map[BlockHash, Map[Validator, Vector[BlockHash]]],
      witnessMap: Map[BlockHash, Map[Validator, BlockHash]],
      jsMap: Map[BlockHash, Set[BlockHash]],
      sendersMap: Map[BlockHash, Validator],
      heightMap: SortedMap[Long, Set[BlockHash]],
      finalizedBlockSet: Set[BlockHash],
      fringesMap: SortedMap[Long, DagFringe]
  )

  def blockMetadataToInfo(blockMeta: BlockMetadata): BlockInfo =
    BlockInfo(
      blockMeta.blockHash,
      blockMeta.sender,
      blockMeta.justifications.map(_.latestBlockHash).toSet,
      blockMeta.blockNum,
      blockMeta.invalid
    )

  class BlockMetadataStore[F[_]: Concurrent](
      private val store: KeyValueTypedStore[F, BlockHash, BlockMetadata],
      private val cache: Ref[F, Map[BlockHash, Deferred[F, Option[BlockMetadata]]]],
      private val dagState: MonadState[F, DagState]
  ) {
    def add(block: BlockMetadata): F[Unit] =
      for {
        // Update DAG state with new block
        _ <- dagState.modify { st =>
              val blockInfo   = blockMetadataToInfo(block)
              val newDagState = addBlockToDagState(blockInfo)(st)
              validateDagState(newDagState)
            }

        // Update persistent block metadata store
        _ <- store.put(block.blockHash, block)
      } yield ()

    def addFringe(fringe: DagFringe): F[Unit] = dagState.modify { st =>
      st.copy(fringesMap = st.fringesMap.updated(fringe.num, fringe))
    }

    import coop.rchain.shared.Caching._
    def get(hash: BlockHash): F[Option[BlockMetadata]] =
      memoize[F, BlockHash, BlockMetadata](store.get(_), cache)(hash)

    def getUnsafe(hash: BlockHash)(
        //implicit f: Sync[F],
        implicit line: sourcecode.Line,
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

    def fringesMap: F[SortedMap[Long, DagFringe]] = dagState.get.map(_.fringesMap)

    def contains(hash: BlockHash): F[Boolean] = dagState.get.map(_.dagSet.contains(hash))

    def childMapData: F[Map[BlockHash, Map[Validator, Vector[BlockHash]]]] =
      dagState.get.map(_.childMap)

    def witnessMap: F[Map[BlockHash, Map[Validator, BlockHash]]] =
      dagState.get.map(_.witnessMap)

    def heightMap: F[SortedMap[Long, Set[BlockHash]]] =
      dagState.get.map(_.heightMap)
  }

  private def addBlockToDagState(block: BlockInfo)(state: DagState): DagState = {
    // Update dag set / all blocks in the DAG
    val newDagSet = state.dagSet + block.hash

    // Update children relation map
    val newChildren = block.justifications.map((_, Map(block.sender -> block.hash))) +
      ((block.hash, Map()))
    val newChildMap = newChildren.foldLeft(state.childMap) {
      case (acc, (key, newChildMap)) =>
        val curChildren = acc.getOrElse(key, Map.empty[Validator, Vector[BlockHash]])
        val newChildren = newChildMap.headOption
          .map {
            case (sender, child) =>
              curChildren.updated(sender, curChildren.getOrElse(sender, Vector()) :+ child)
          }
          .getOrElse(curChildren)
        acc.updated(key, newChildren)
    }

    // Update block height map
    val newHeightMap = if (!block.isInvalid) {
      val currSet = state.heightMap.getOrElse(block.blockNum, Set())
      state.heightMap.updated(block.blockNum, currSet + block.hash)
    } else state.heightMap

    val witnessingSender = block.sender
    def addWit(
        acc1: Map[BlockHash, Map[Validator, BlockHash]],
        m: BlockHash
    ): Map[BlockHash, Map[Validator, BlockHash]] = {
      val newVal = acc1.updated(m, acc1.getOrElse(m, Map()) + (witnessingSender -> block.hash))
      val selfJsOpt =
        state.jsMap.getOrElse(m, Set()).find(j => state.sendersMap(j) == state.sendersMap(m))
      selfJsOpt
        .map { selfJs =>
          if (state.witnessMap.getOrElse(selfJs, Map()).contains(witnessingSender))
            newVal
          else
            addWit(acc1, selfJs)
        }
        .getOrElse(newVal)
    }
    val newWitnessMap = block.justifications.foldLeft(
      state.witnessMap.updated(block.hash, Map.empty[Validator, BlockHash])
    ) {
      case (acc, js) =>
        if (state.witnessMap.getOrElse(js, Map()).contains(witnessingSender))
          acc
        else
          addWit(acc, js)
    }

    state.copy(
      dagSet = newDagSet,
      childMap = newChildMap,
      witnessMap = newWitnessMap,
      heightMap = newHeightMap,
      sendersMap = state.sendersMap + (block.hash -> block.sender),
      jsMap = state.jsMap + (block.hash           -> block.justifications)
    )
  }

  private def validateDagState(state: DagState): DagState = {
    // Validate height map index (block numbers) are in sequence without holes
    val m          = state.heightMap
    val (min, max) = if (m.nonEmpty) (m.firstKey, m.lastKey + 1) else (0L, 0L)
    assert(max - min == m.size.toLong, "DAG store height map has numbers not in sequence.")
    state
  }

  // Used to project part of the block metadata for in-memory initialization
  final case class BlockInfo(
      hash: BlockHash,
      sender: Validator,
      justifications: Set[BlockHash],
      blockNum: Long,
      isInvalid: Boolean
  )

  private def recreateInMemoryState(
      blocksInfoMap: Map[BlockHash, BlockInfo]
  ): DagState = {
    val emptyState: DagState =
      DagState(
        dagSet = Set(),
        childMap = Map(),
        witnessMap = Map(),
        jsMap = blocksInfoMap.mapValues(_.justifications),
        heightMap = SortedMap(),
        sendersMap = blocksInfoMap.mapValues(_.sender),
        finalizedBlockSet = Set(),
        fringesMap = SortedMap()
      )

    // Add blocks to DAG state
    val dagState = blocksInfoMap.foldLeft(emptyState) {
      case (state, (_, block)) => addBlockToDagState(block)(state)
    }

    validateDagState(dagState)
  }
}
