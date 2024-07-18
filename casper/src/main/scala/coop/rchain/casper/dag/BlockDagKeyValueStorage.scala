package coop.rchain.casper.dag

import cats.effect.std.Semaphore
import cats.effect.{Async, Ref, Sync}
import cats.syntax.all._
import cats.{Monad, Show}
import coop.rchain.blockstorage._
import coop.rchain.blockstorage.dag.BlockDagStorage.DeployId
import coop.rchain.blockstorage.dag._
import coop.rchain.blockstorage.dag.codecs._
import coop.rchain.blockstorage.syntax._
import coop.rchain.casper.dag.BlockDagKeyValueStorage._
import coop.rchain.casper.merging.BlockIndex
import coop.rchain.casper.protocol.{BlockMessage, DeployData, EjectSystemDeployData}
import coop.rchain.casper.{MultiParentCasper, PrettyPrinter}
import coop.rchain.crypto.signatures.Signed
import coop.rchain.metrics.Metrics.Source
import coop.rchain.metrics.{Metrics, MetricsSemaphore}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.Validator.Validator
import coop.rchain.models.syntax._
import coop.rchain.models.{BlockMetadata, FringeData}
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.rspace.hashing.Blake2b256Hash.codecBlake2b256Hash
import coop.rchain.sdk.dag.View.IncludeBottom
import coop.rchain.sdk.error.FatalError
import coop.rchain.shared.Log
import coop.rchain.shared.syntax._
import coop.rchain.store.{KeyValueStoreManager, KeyValueTypedStore}
import fs2.Stream

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.SortedMap

final class BlockDagKeyValueStorage[F[_]: Async: Log] private (
    representationState: Ref[F, DagRepresentation],
    lock: Semaphore[F],
    metadataStore: KeyValueTypedStore[F, BlockHash, BlockMetadata],
    lfsSetStore: KeyValueTypedStore[F, BlockHash, Unit], // store for latest messages
    fringeDataStore: KeyValueTypedStore[F, Blake2b256Hash, FringeData],
    deployIndex: KeyValueTypedStore[F, DeployId, BlockHash],
    deployStore: KeyValueTypedStore[F, DeployId, Signed[DeployData]]
) extends BlockDagStorage[F] {

  def getRepresentation: F[DagRepresentation] = representationState.get

  // TODO store FringeData from PreState
  override def insert(
      blockMetadata: BlockMetadata,
      block: BlockMessage,
      isSync: Boolean
  ): F[Unit] = {
    def doInsert: F[Unit] =
      for {
        // Update persistent storages
        // Add metadata
        _ <- metadataStore.put(block.blockHash, blockMetadata)

        // Add to LFS set. It will be removed when GC event happens.
        _ <- lfsSetStore.put(block.blockHash, ())

        // Add deploys to deploy index storage
        _ <- deployIndex.put(block.state.deploys.map(_.deploy.sig).map(_ -> block.blockHash))

        // Add fringe data
//        fringeHash = FringeData.fringeHash(blockMetadata.fringe)
        // Calculate blocks included in the fringe
        //        justificationsMsgs = blockMetadata.justifications.map(dagState.msgMap)
        //        prevFringeMsgs = dagState.msgMap.latestFringe(justificationsMsgs)
        //        fringeMsgs     = blockMetadata.fringe.map(dagState.msgMap)
        //        fringeDiff = View
        //          .diff(
        //            Monoid[View[Validator]].combineAll(fringeMsgs.map(_.seen)),
        //            Monoid[View[Validator]].combineAll(prevFringeMsgs.map(_.seen)),
        //            IncludeTop
        //          )
        //          .seen
        //          .map { case (v, r) => r.map(_.toLong).map(v -> _) }
        //          .flatten

        //        fringeDiffHashes = fringeDiff.toList.flatMap(dag.hashLookup).toSet
        // Fringe data object to store

        //        // Update block metadata members of finalized fringe
        //        fringeDiffMetas        <- fringeDiffHashes.toList.traverse(blockMetadataIndex.getUnsafe)
        //        fringeDiffMetasUpdated = fringeDiffMetas.map(_.copy(memberOfFringe = fringeHash.some))
        //        _                      <- fringeDiffMetasUpdated.traverse(blockMetadataIndex.add)
//        metadataStateHash = blockMetadata.fringeStateHash.toBlake2b256Hash
//        fringeData = FringeData(
//          fringeHash,
//          fringe = blockMetadata.fringe,
//          //fringeDiff = fringeDiffHashes,
//          stateHash = metadataStateHash,
//          rejectedDeploys = block.rejectedDeploys
//        )

        _ <- block.fringes.traverse { fringeData =>
              for {
                // Save to fringe data store
                shouldSave <- (!blockMetadata.validationFailed).pure &&^ fringeDataStore
                               .get1(fringeData.fringeHash)
                               .flatMap {
                                 case Some(fd) =>
                                   FatalError(
                                     s"Attempt do add block with equivocating fringe state hash. " +
                                       s"Fringe: ${blockMetadata.fringe.map(_.toHexString.take(6))}, " +
                                       s"persisted: ${fd.stateHash}, " +
                                       s"attempting to add: ${fringeData.stateHash}."
                                   ).raiseError
                                     .whenA(fd.stateHash != fringeData.stateHash)
                                     .as(false)
                                 case None => true.pure
                               }
                _ <- fringeDataStore.put(fringeData.fringeHash, fringeData).whenA(shouldSave)
              } yield ()
            }

        // Update in-mem indices
        dag      <- representationState.get
        dagState = dag.dagMessageState

        r <- representationState.modify { dr =>
              // Update DAG messages state
              val dagMsgSt = dr.dagMessageState
              val msg      = messageFromBlockMetadata(blockMetadata)

              val newDagMsgState = dagMsgSt.insertMsg(msg)
              val newHashLookup = dr.hashLookup.updated(
                (msg.sender, msg.senderSeq),
                dr.hashLookup.getOrElse((msg.sender, msg.senderSeq), Set()) + msg.id
              )
              val newFringes = block.fringes.foldLeft(dr.fringeStates) {
                case (acc, fd) => acc + (fd.fringe -> fd)
              }
              val newDagSet = dr.dagSet + msg.id
              val newChildMap = msg.parents.foldLeft(dr.childMap) {
                case (acc, p) => acc.updated(p, acc.get(p).map(_ + msg.id).getOrElse(Set(msg.id)))
              }
              val newHeightMap = dr.heightMap.updated(
                msg.height,
                dr.heightMap.get(msg.height).map(_ + msg.id).getOrElse(Set(msg.id))
              )
              // Updated DagRepresentation
              val newDag = dr.copy(
                newDagSet,
                newChildMap,
                newHeightMap,
                newDagMsgState,
                newFringes,
                newHashLookup
              )

              // Blocks that no more required to process any future message
              val garbage = if (!isSync) {
                // attempt to prune only when sender is the only outsider
                // (this means all justifications already advanced the fringe compared to self justification)
                // so no new valid (non equivocating) messages will reference what is about to be pruned
                val isNewFringe = blockMetadata.fringe !=
                  dagMsgSt.latestMsgs
                    .find(_.sender == blockMetadata.sender)
                    .map(_.fringe)
                    .getOrElse(Set())

                lazy val oldLowestFringe =
                  dag.dagMessageState.msgMap.lowestFringe(dagState.latestMsgs).map(_.id)
                lazy val outsiders =
                  dag.dagMessageState.latestMsgs.filter(_.fringe == oldLowestFringe).map(_.sender)

                val shouldPrune = isNewFringe && (outsiders == Set(blockMetadata.sender))

//                println(
//                  s"outsiders ${(outsiders == Set(blockMetadata.sender))} newFringe $isNewFringe"
//                )

                if (shouldPrune) {
                  val x = executeGC(
                    newDag.dagMessageState,
                    dag.dagMessageState,
                    newDag.childMap,
                    (v, sN) => newDag.hashLookup.getUnsafe(v -> sN).head
                  )
//                  println(
//                    s"Garbage: ${x.map(_.toHexString.take(8))} blocks, new index size: ${BlockIndex.cache.size - x.size}"
//                  )
                  x
                } else
                  Set.empty[BlockHash]
              } else
                Set.empty[BlockHash]

              val gcDagMsgState = newDagMsgState.copy(msgMap = newDagMsgState.msgMap -- garbage)
              val gcHashLookup = {
                // TODO handle equivocations
                val gc = garbage
                  .map(dr.dagMessageState.msgMap)
                  .map(x => x.sender -> x.senderSeq)
                newHashLookup -- gc
              }
              // Update fringe data cache
              // TODO: remove out of reach records (not needed for further finalization)
              val gcFringes = {
                val gc = garbage.map(dr.dagMessageState.msgMap(_).fringe)
                newFringes -- gc
              }
              val gcDagSet   = newDagSet -- garbage
              val gcChildMap = newChildMap -- garbage
              val gcHeightMap = {
                garbage.map(dagMsgSt.msgMap.getUnsafe(_)).foldLeft(newHeightMap) {
                  case (acc, m) =>
                    acc.updated(
                      m.height,
                      acc.get(m.height).map(_ - m.id).getOrElse(Set())
                    )
                }
              }
              val gcDag = newDag.copy(
                gcDagSet,
                gcChildMap,
                gcHeightMap,
                gcDagMsgState,
                gcFringes,
                gcHashLookup
              )

              (gcDag, (gcDag, garbage))
            }

        (dag, garbage) = r

        // Delete garbage from other stores
        _ = garbage.toList.foreach(BlockIndex.cache.remove)
        _ <- lfsSetStore.delete(garbage.toList)
        _ <- Log[F].info(
              s"Pruning ${garbage.size} blocks, new LFS set size ${BlockIndex.cache.size}"
            )

        _ <- removeExpiredFromPool(deployStore, dag).map(
              _.map((_, ())).foreach((expiredMap.update _).tupled)
            )
      } yield ()

    lock.permit.use { _ =>
      metadataStore
        .contains(blockMetadata.blockHash)
        .ifM(
          Log[F]
            .warn(
              s"Block ${PrettyPrinter.buildString(block, short = true)} is already in the DAG."
            ),
          doInsert *>
            Log[F].info(s"Block ${PrettyPrinter.buildString(block, short = true)} added to DAG.")
        )
    }

  }

  /**
    * Prune database.
    * Remove data that is not required for processing any future block.
    * TODO for now its just clean merging index cache, but can be used to prune the whole DB.
    * `Diff` here is because data pruned is what is not required in new state compared to current state.
    */
  private def executeGC(
      newState: DagMessageState[BlockHash, Validator],
      curState: DagMessageState[BlockHash, Validator],
      childMap: Map[BlockHash, Set[BlockHash]],
      lookup: (Validator, Long) => BlockHash
  ): Set[BlockHash] = {
    val newLPF = dbPruneFringe(newState, childMap)
    val curLPF = dbPruneFringe(curState, childMap)
    if (newLPF == curLPF)
      Set.empty[BlockHash]
    else
      newState.msgMap.between(newLPF.map(_.id), curLPF.map(_.id), lookup, IncludeBottom)
  }

  override def lookup(blockHash: BlockHash): F[Option[BlockMetadata]] =
    metadataStore.get1(blockHash)

  override def lookupByDeployId(deployId: DeployId): F[Option[BlockHash]] =
    deployIndex.get1(deployId)

  override def addDeploy(d: Signed[DeployData]): F[Unit] = deployStore.put(d.sig, d)

  override def pooledDeploys: F[Map[DeployId, Signed[DeployData]]] = deployStore.toMap

  override def containsDeployInPool(deployId: DeployId): F[Boolean] = deployStore.contains(deployId)

  // Map of deploys being executed and execution results
  private val expiredMap = TrieMap.empty[DeployId, Unit]
}

object BlockDagKeyValueStorage {
  implicit private val BlockDagKeyValueStorage_FromFileMetricsSource: Source =
    Metrics.Source(BlockStorageMetricsSource, "dag-key-value-store")

  /**
    * Fringe messages below which (+ messages of the fringe) can be pruned since they are not
    * required for processing of any future message.
    *
    * Returns prune fringe and lowest fringe across messages of latest fringe in the view.
    */
  def dbPruneFringe(
      dbState: DagMessageState[BlockHash, Validator],
      childMap: Map[BlockHash, Set[BlockHash]]
  ): Set[Message[BlockHash, Validator]] = {
    // Lowest fringe seen by latest messages
    val lowestFringe = dbState.msgMap.lowestFringe(dbState.latestMsgs)
    // Lowest fringe seen by messages of a fringe
    val lowestFringe2 = dbState.msgMap.lowestFringe(lowestFringe)
    // Prune fringe required to merge anything that has lowestFringe2 as a lower boundary
    // Anything below can be discarded and all future messages on top of dbState still can be processed
    dbState.msgMap.lowestFringe(lowestFringe2)
//    dbState.msgMap.pruneFringe(lowestFringe2.map(_.id), childMap)
  }

  private final case class DagStores[F[_]](
      metadataStore: KeyValueTypedStore[F, BlockHash, BlockMetadata],
      lfsSet: KeyValueTypedStore[F, BlockHash, Unit], // set of blocks metadata required to start the node
      fringeDataDb: KeyValueTypedStore[F, Blake2b256Hash, FringeData],
      deploys: KeyValueTypedStore[F, DeployId, BlockHash],
      deployPool: KeyValueTypedStore[F, DeployId, Signed[DeployData]]
  )

  private def createStores[F[_]: Async: Log: Metrics](
      kvm: KeyValueStoreManager[F]
  ): F[DagStores[F]] = {
    implicit val kvm_ = kvm
    for {
      // Block metadata map
      blockMetadataDb <- KeyValueStoreManager[F].database[BlockHash, BlockMetadata](
                          "block-metadata",
                          codecBlockHash,
                          codecBlockMetadata
                        )

      lfsDb <- KeyValueStoreManager[F].database[BlockHash, Unit](
                "lfs-set",
                codecBlockHash,
                scodec.Codec[Unit]
              )

      // Fringe data map
      fringeDataDb <- KeyValueStoreManager[F].database[Blake2b256Hash, FringeData](
                       "fringe-data",
                       codecBlake2b256Hash,
                       codecFringeData
                     )

      // Deploy map
      deployIndexDb <- KeyValueStoreManager[F].database[DeployId, BlockHash](
                        "deploy-index",
                        codecByteString,
                        codecBlockHash
                      )

      // Deploy pool storage
      deployPoolDb <- KeyValueStoreManager[F].database[DeployId, Signed[DeployData]](
                       "deploy-pool",
                       codecByteString,
                       codecSignedDeployData
                     )
    } yield DagStores(
      blockMetadataDb,
      lfsDb,
      fringeDataDb,
      deployIndexDb,
      deployPoolDb
    )
  }

  def create[F[_]: Async: Log: Metrics](
      kvm: KeyValueStoreManager[F]
  ): F[BlockDagKeyValueStorage[F]] =
    for {
      lock   <- MetricsSemaphore.single[F]
      stores <- createStores(kvm)
      initST <- {
        for {
          lfsSet <- stores.lfsSet.toMap.map(_.keySet)

          // Fill message map from BlockMetadata
          dmsSt <- Ref.of(DagMessageState[BlockHash, Validator]())
          fsSt  <- Ref.of(Map[Set[BlockHash], FringeData]())
          hlSt  <- Ref.of(Map[(Validator, Long), Set[BlockHash]]())

          initMsgMapJob = Stream.fromIterator(lfsSet.iterator, 1).evalMap { hash =>
            for {
              ds     <- dmsSt.get
              fs     <- fsSt.get
              hl     <- hlSt.get
              msgMap = ds.msgMap
              updateMessage = for {
                block <- stores.metadataStore.getUnsafe(hash)
                msg   = messageFromBlockMetadata(block)
                newDs = ds.insertMsg(msg)

                fringeDataCached = fs.contains(msg.fringe)
                newFs <- if (!fringeDataCached) {
                          implicit val showHash: Show[Blake2b256Hash] =
                            Show.show[Blake2b256Hash](_.bytes.toHex)
                          val fringeHash = FringeData.fringeHash(msg.fringe)
                          stores.fringeDataDb
                            .getUnsafe(fringeHash)
                            .map(fd => fs + ((msg.fringe, fd)))
                        } else {
                          fs.pure[F]
                        }
                newHl = hl + ((msg.sender, msg.senderSeq) -> (hl
                  .getOrElse((msg.sender, msg.senderSeq), Set.empty) + hash))

                _ <- dmsSt.set(newDs)
                _ <- fsSt.set(newFs)
                _ <- hlSt.set(newHl)
              } yield ()

              // Check if already created
              _ <- updateMessage.unlessA(msgMap.contains(hash))
            } yield ()
          }

          // Initialize DagMessageState
          _            <- initMsgMapJob.compile.drain
          dagMsgsState <- dmsSt.get
          fringeStates <- fsSt.get
          hashLookup   <- hlSt.get

          // TODO do this inside initMsgMapJob
          indices   <- create3Indices(stores.metadataStore, stores.lfsSet)
          dagSet    = indices.dagSet
          childMap  = indices.childMap
          heightMap = indices.heightMap
        } yield DagRepresentation(
          dagSet,
          childMap,
          heightMap,
          dagMsgsState,
          fringeStates,
          hashLookup
        )
      }
      stRef <- Ref.of[F, DagRepresentation](initST)
    } yield new BlockDagKeyValueStorage[F](
      stRef,
      lock,
      stores.metadataStore,
      stores.lfsSet,
      stores.fringeDataDb,
      stores.deploys,
      stores.deployPool
    )

  // TODO create these indices inside `create`
  private final case class DagState(
      dagSet: Set[BlockHash],
      childMap: Map[BlockHash, Set[BlockHash]],
      heightMap: SortedMap[Long, Set[BlockHash]]
  )

  private def create3Indices[F[_]: Sync: Log](
      blockMetadataStore: KeyValueTypedStore[F, BlockHash, BlockMetadata],
      lfsSetStore: KeyValueTypedStore[F, BlockHash, Unit]
  ): F[DagState] = {
    // Used to project part of the block metadata for in-memory initialization
    final case class BlockInfo(
        hash: BlockHash,
        parents: Set[BlockHash],
        blockNum: Long,
        validationFailed: Boolean
    )

    def blockMetadataToInfo(blockMeta: BlockMetadata): BlockInfo =
      BlockInfo(
        blockMeta.blockHash,
        blockMeta.justifications,
        blockMeta.blockNum,
        blockMeta.validationFailed
      )

    @tailrec
    def validateDagState(
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
      //      s"DAG store height map has numbers not in sequence. heightMap size ${m.size.toLong}: $m \nmax $max \nmin $min "
      //    )
    }

    def recreateInMemoryState(
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

    def addBlockToDagState(block: BlockInfo)(state: DagState): DagState = {
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

    for {
      lfsSet <- lfsSetStore.toMap.map(_.keySet)
      _      <- Log[F].info(s"Loading blocks metadata (${lfsSet.size} blocks).")
      // Iterate over block metadata store and collect info for in-memory cache
      blockInfoMap <- blockMetadataStore
                       .get(lfsSet.toList)
                       .map(_.flatten.map(x => x.blockHash -> blockMetadataToInfo(x)).toMap)
      _ <- new FatalError(
            s"Missing block metadata required: ${(lfsSet -- blockInfoMap.keySet)
              .map(_.toHexString.take(8))}"
          ).raiseError.whenA(blockInfoMap.size != lfsSet.size)
      _ <- Log[F].info("Loading blocks metadata done.")
      // garbage can be in the lfsSetStore if node shut down after block is added but before GC-ed blocks
      // are removed from lfsSetStore
      (dagState, garbage) = recreateInMemoryState(blockInfoMap)
      _                   <- lfsSetStore.delete(garbage.toSeq)
      _ <- Log[F]
            .info(s"Lfs set store contains ${garbage.size} garbage records. Cleaning up.")
            .whenA(garbage.nonEmpty)
      _ <- Log[F].info("Successfully built in-memory blockMetadataStore.")
    } yield dagState
  }

  private def messageFromBlockMetadata(
      block: BlockMetadata
  ): Message[BlockHash, Validator] = Message(
    id = block.blockHash,
    height = block.blockNum,
    sender = block.sender,
    senderSeq = block.seqNum,
    bondsMap = block.bondsMap,
    parents = block.justifications,
    fringe = block.fringe,
    seen = block.view,
    ejections = block.ejections
  )

  private def removeExpiredFromPool[F[_]: Monad](
      deployStore: KeyValueTypedStore[F, DeployId, Signed[DeployData]],
      dag: DagRepresentation
  ): F[List[DeployId]] = {
    val expiredF = deployStore
      .collect {
        case (_, v) =>
          val d       = v()
          val expired = dag.latestBlockNumber - d.data.validAfterBlockNumber > MultiParentCasper.deployLifespan
          expired.guard[Option].as(d)
      }
      .map(_.flatten.toList)
    expiredF.flatMap { v =>
      val sigs = v.map(_.sig)
      deployStore.delete(sigs).as(sigs)
    }
  }
}
