package coop.rchain.casper.dag

import cats.effect.std.Semaphore
import cats.effect.{Async, Ref, Sync}
import cats.kernel.Monoid
import cats.syntax.all._
import cats.{Applicative, Monad, Show}
import coop.rchain.blockstorage._
import coop.rchain.blockstorage.dag.BlockDagStorage.DeployId
import coop.rchain.blockstorage.dag.BlockMetadataStore.BlockMetadataStore
import coop.rchain.blockstorage.dag._
import coop.rchain.blockstorage.dag.codecs._
import coop.rchain.blockstorage.syntax._
import coop.rchain.casper.dag.BlockDagKeyValueStorage._
import coop.rchain.casper.merging.BlockIndex
import coop.rchain.casper.protocol.{BlockMessage, DeployData, ProposeSlot}
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
import coop.rchain.sdk.dag.View
import coop.rchain.sdk.dag.View.{IncludeBottom, IncludeTop}
import coop.rchain.shared.Log
import coop.rchain.shared.syntax._
import coop.rchain.store.{KeyValueStoreManager, KeyValueTypedStore}
import fs2.Stream

import scala.collection.concurrent.TrieMap

final class BlockDagKeyValueStorage[F[_]: Async: Log] private (
    representationState: Ref[F, DagRepresentation],
    lock: Semaphore[F],
    blockMetadataIndex: BlockMetadataStore[F],
    fringeDataStore: KeyValueTypedStore[F, Blake2b256Hash, FringeData],
    deployIndex: KeyValueTypedStore[F, DeployId, BlockHash],
    deployStore: KeyValueTypedStore[F, DeployId, Signed[DeployData]]
) extends BlockDagStorage[F] {

  def getRepresentation: F[DagRepresentation] = representationState.get

  override def insert(
      blockMetadata: BlockMetadata,
      block: BlockMessage,
      isSync: Boolean
  ): F[Unit] = {
    def doInsert: F[Unit] =
      for {
        dag      <- representationState.get
        dagState = dag.dagMessageState

        // Add deploys to deploy index storage
        deployHashes = block.state.deploys.map(_.deploy.sig)
        _            <- deployIndex.put(deployHashes.map(_ -> block.blockHash))

        garbage <- if (!isSync) {
                    // attempt to prune only when sender is the only outsider
                    // (this means all justifications already advanced the fringe compared to self justification)
                    // so no new valid (non equivocating) messages will reference what is about to be pruned
                    val lowestFringe = dagState.msgMap.lowestFringe(dagState.latestMsgs).map(_.id)
                    val outsiders =
                      dagState.latestMsgs.filter(_.fringe == lowestFringe).map(_.sender)
                    val shouldPrune = outsiders == Set(blockMetadata.sender)
                    if (shouldPrune)
                      executeGC(
                        dag.dagMessageState,
                        dagState,
                        dag.childMap,
                        (v, sN) => dag.hashLookup.getUnsafe(v -> sN).head
                      )
                    else
                      Set.empty[BlockHash].pure
                  } else
                    Set.empty[BlockHash].pure

        // Add block metadata
        _ <- blockMetadataIndex.add(blockMetadata)

        // Store fringe data
        fringeHash = FringeData.fringeHash(blockMetadata.fringe)
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
        fringeData = FringeData(
          fringeHash,
          fringe = blockMetadata.fringe,
          //fringeDiff = fringeDiffHashes,
          stateHash = blockMetadata.fringeStateHash.toBlake2b256Hash,
          rejectedDeploys = block.rejectedDeploys,
          rejectedBlocks = block.rejectedBlocks,
          rejectedSenders = block.rejectedSenders
        )
        // Save to fringe data store
        _ <- fringeDataStore.put(fringeHash, fringeData)

//        // Update block metadata members of finalized fringe
//        fringeDiffMetas        <- fringeDiffHashes.toList.traverse(blockMetadataIndex.getUnsafe)
//        fringeDiffMetasUpdated = fringeDiffMetas.map(_.copy(memberOfFringe = fringeHash.some))
//        _                      <- fringeDiffMetasUpdated.traverse(blockMetadataIndex.add)

        // Take current DAG state / view of the DAG
        dagSet    <- blockMetadataIndex.dagSet
        childMap  <- blockMetadataIndex.childMapData
        heightMap <- blockMetadataIndex.heightMap
        dag <- representationState.updateAndGet { dr =>
                // Update DAG messages state
                val dagMsgSt       = dr.dagMessageState
                val msg            = messageFromBlockMetadata(blockMetadata)
                val newDagMsgState = dagMsgSt.insertMsg(msg)

                val newHashLookup = dr.hashLookup.updated(
                  (msg.sender, msg.senderSeq),
                  dr.hashLookup.getOrElse((msg.sender, msg.senderSeq), Set()) + msg.id
                )

                // Update fringe data cache
                // TODO: remove out of reach records (not needed for further finalization)
                val newFringes = dr.fringeStates + ((msg.fringe, fringeData))

                // Updated DagRepresentation
                dr.copy(
                  dagSet,
                  childMap,
                  heightMap,
                  newDagMsgState,
                  fringeStates = newFringes,
                  hashLookup = newHashLookup
                )
              }

        _ <- removeExpiredFromPool(deployStore, dag).map(
              _.map((_, ())).foreach((expiredMap.update _).tupled)
            )
      } yield ()

    lock.permit.use { _ =>
      blockMetadataIndex
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
  ): F[Set[BlockHash]] = {
    val newLPF = dbPruneFringe(newState, childMap)
    val curLPF = dbPruneFringe(curState, childMap)
    val toPrune = newState.msgMap.between(newLPF.map(_.id), curLPF.map(_.id), lookup, IncludeBottom) ++
      curLPF.map(_.id)

    Sync[F].delay(toPrune.toList.foreach(BlockIndex.cache.remove)) *>
      blockMetadataIndex.lfsSetStore.delete(toPrune.toList) *>
      Log[F]
        .info(s"Pruned ${toPrune.size} blocks, new index size: ${BlockIndex.cache.size}")
        .as(toPrune)
  }

  override def lookup(blockHash: BlockHash): F[Option[BlockMetadata]] =
    blockMetadataIndex.get(blockHash)

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

  def lowerBound[F[_]: BlockDagStorage: Applicative]: F[Set[ProposeSlot]] =
    BlockDagStorage[F].getRepresentation.map { dag =>
      val x = dbPruneFringe(dag.dagMessageState, dag.childMap)
        .map(m => ProposeSlot(m.sender, m.senderSeq))
      if (x.isEmpty) dag.dagMessageState.latestMsgs.map(m => ProposeSlot(m.sender, 0L)) else x
    }

  /**
    * Fringe messages below which (+ messages of the fringe) can be pruned since they are not
    * required for processing of any future message.
    */
  def dbPruneFringe(
      dbState: DagMessageState[BlockHash, Validator],
      childMap: Map[BlockHash, Set[BlockHash]]
  ): Set[Message[BlockHash, Validator]] = {
    val lowestFringe = dbState.msgMap.lowestFringe(dbState.latestMsgs).map(_.id)
    dbState.msgMap.pruneFringe(lowestFringe, childMap)
  }

  private final case class DagStores[F[_]](
      metadata: BlockMetadataStore[F],
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

      blockMetadataStore <- BlockMetadataStore[F](blockMetadataDb, lfsDb)

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
      blockMetadataStore,
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
        val metadata = stores.metadata
        for {
          // Take current DAG state / view of the DAG
          dagSet    <- metadata.dagSet
          childMap  <- metadata.childMapData
          heightMap <- metadata.heightMap
          lfsSet    <- metadata.lfsSetStore.toMap.map(_.keySet)

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
                block <- metadata.getUnsafe(hash)
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
      stores.metadata,
      stores.fringeDataDb,
      stores.deploys,
      stores.deployPool
    )

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
    seen = block.view
  )

  def removeExpiredFromPool[F[_]: Monad](
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
