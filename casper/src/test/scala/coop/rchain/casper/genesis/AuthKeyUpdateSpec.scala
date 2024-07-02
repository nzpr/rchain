package coop.rchain.casper.genesis

import cats.implicits.catsSyntaxTuple2Semigroupal
import coop.rchain.casper.helper.TestNode
import coop.rchain.casper.helper.TestNode._
import coop.rchain.casper.merging.{BlockIndex, DeployChainIndex, MergeScope}
import coop.rchain.casper.rholang.BlockRandomSeed
import coop.rchain.casper.syntax.casperSyntaxRuntimeManager
import coop.rchain.casper.util.ConstructDeploy
import coop.rchain.crypto.PrivateKey
import coop.rchain.crypto.signatures.Secp256k1
import coop.rchain.models.GDeployId
import coop.rchain.models.rholang.RhoType.{RhoBoolean, RhoString, RhoTuple2}
import coop.rchain.models.rholang.implicits._
import coop.rchain.models.syntax._
import coop.rchain.p2p.EffectsTestInstances.LogicalTime
import coop.rchain.rholang.interpreter.SystemProcesses.BlockData
import coop.rchain.rholang.interpreter.util.RevAddress
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.sdk.dag.merging.ConflictResolutionLogic.computeConflictsMap
import coop.rchain.shared.Log
import coop.rchain.shared.scalatestcontrib._
import org.scalatest.Inspectors
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.io.Source

class AuthKeyUpdateSpec extends AnyFlatSpec with Matchers with Inspectors {

  import coop.rchain.casper.util.GenesisBuilder._

  implicit val timeEff = new LogicalTime[Effect]
  private val shardId  = "root"
  private val p1 =
    PrivateKey("fc743bd08a822d544bfbe05a5663fc325039a44c8f0c7fbea95a85517da5c36b".unsafeDecodeHex)
  private val pub1 = Secp256k1.toPublic(p1)
  private val noPermissionKey =
    PrivateKey("6e88cf274735f3f7f73ec3d7f0362c439ab508427682b5bd788007aca665d810".unsafeDecodeHex)
  private val noPermissionKeyPub = Secp256k1.toPublic(noPermissionKey)
  private val p3 =
    PrivateKey("87369d132ed6a7626dc4c5dfbaf41e954dd0ec55830e613a3f868c74d64a7a22".unsafeDecodeHex)
  private val pub3          = Secp256k1.toPublic(p3)
  private val validatorKeys = Seq((p1, pub1), (noPermissionKey, noPermissionKeyPub), (p3, pub3))
  private val genesisVaults =
    Seq((p1, 100000000000L), (noPermissionKey, 100000000000L), (p3, 100000000000L))
  private val bonds   = Map(pub1 -> 1000000L, noPermissionKeyPub -> 1000000L, pub3 -> 1000000L)
  private val genesis = buildGenesis(buildGenesisParameters(validatorKeys, genesisVaults, bonds))

  private def getBalanceTerm(address: String) =
    s"""new return, rl(`rho:registry:lookup`), RevVaultCh, vaultCh, balanceCh in {
       #  rl!(`rho:rchain:revVault`, *RevVaultCh) |
       #  for (@(_, RevVault) <- RevVaultCh) {
       #    @RevVault!("findOrCreate", "${address}", *vaultCh) |
       #    for (@(true, vault) <- vaultCh) {
       #      @vault!("balance", *balanceCh) |
       #      for (@balance <- balanceCh) {
       #        return!(balance)
       #      }
       #    }
       #  }
       #}
       #""".stripMargin('#')

  "merging" should "work" in effectTest {
    val update = Source.fromResource("UpdateAuthKey/UpdateAuthKey.rho").mkString
    val updateDeploy =
      ConstructDeploy.sourceDeployNow(update, p1, 100000000L, 0L, shardId = shardId)

    val transferAmount = 100000
    val pub1RevAddr    = RevAddress.fromPublicKey(pub1).get.toBase58
    val pub2RevAddr    = RevAddress.fromPublicKey(noPermissionKeyPub).get.toBase58
    val transferTerm =
      s"""
         #new rl(`rho:registry:lookup`), RevVaultCh, vaultCh, toVaultCh, deployerId(`rho:rchain:deployerId`), stdout(`rho:io:stdout`),revVaultKeyCh, resultCh in {
         #  rl!(`rho:rchain:revVault`, *RevVaultCh) |
         #  for (@(_, RevVault) <- RevVaultCh) {
         #    @RevVault!("findOrCreate", "${pub1RevAddr}", *vaultCh) |
         #    @RevVault!("findOrCreate", "${pub2RevAddr}", *toVaultCh) |
         #    @RevVault!("deployerAuthKey", *deployerId, *revVaultKeyCh) |
         #    for (@(true, vault) <- vaultCh; key <- revVaultKeyCh; @(true, toVault) <- toVaultCh) {
         #      @vault!("transfer", "${pub2RevAddr}", $transferAmount, *key, *resultCh) |
         #      for (@res <- resultCh) { stdout!(("outcome", res)) }
         #    }
         #  }
         #}""".stripMargin('#')
    val transferDeploy = ConstructDeploy
      .sourceDeployNow(transferTerm, sec = p1, shardId = shardId, phloLimit = 900000L)

    TestNode.standaloneEff(genesis).use { node =>
      val rm     = node.runtimeManager
      val pre1   = genesis.genesisBlock.postStateHash
      val bN1    = 1L
      val sN1    = 1L
      val bN2    = 2L
      val sN2    = 2L
      val sender = pub1
      val bD1    = BlockData(bN1, sender, sN1)
      val bD2    = BlockData(bN2, sender, sN2)

      implicit val l: Log[Effect] = Log.log[Effect]

      for {
        // build 2 states, one on top of another
        r1 <- {
          val rand1 = BlockRandomSeed.randomGenerator(shardId, bN1, sender, pre1.toBlake2b256Hash)
          rm.computeState(pre1)(Seq(updateDeploy), Seq(), rand1, bD1)
        }
        (post1, pD1, spD1) = r1
        pre2               = post1
        r2 <- {
          val rand2 = BlockRandomSeed.randomGenerator(shardId, bN2, sender, pre2.toBlake2b256Hash)
          rm.computeState(pre2)(Seq(transferDeploy), Seq(), rand2, bD2)
        }
        (post2, pD2, spD2) = r2
        // index 1
        mergeableChs1 <- rm.loadMergeableChannels(post1, sender.bytes, sN1)
        index1 <- BlockIndex(
                   Blake2b256Hash.create(Array(1.toByte)).toByteString,
                   pD1.toList,
                   spD1.toList,
                   pre1.toBlake2b256Hash,
                   post1.toBlake2b256Hash,
                   rm.getHistoryRepo,
                   mergeableChs1
                 )
        // index 2
        mergeableChs2 <- rm.loadMergeableChannels(post2, sender.bytes, sN2)
        index2 <- BlockIndex(
                   Blake2b256Hash.create(Array(2.toByte)).toByteString,
                   pD2.toList,
                   spD2.toList,
                   pre2.toBlake2b256Hash,
                   post2.toBlake2b256Hash,
                   rm.getHistoryRepo,
                   mergeableChs2
                 )
        // index combined
        mergeableChs12 <- (
                           rm.loadMergeableChannels(post1, sender.bytes, sN1),
                           rm.loadMergeableChannels(post2, sender.bytes, sN2)
                         ).mapN(_ ++ _)
        index12 <- BlockIndex(
                    Blake2b256Hash.create(Array(3.toByte)).toByteString,
                    (pD1 ++ pD2).toList,
                    (spD1 ++ spD2).toList,
                    pre1.toBlake2b256Hash,
                    post2.toBlake2b256Hash,
                    rm.getHistoryRepo,
                    mergeableChs12
                  )
        // merge
        mPost1 <- MergeScope
                   .computeMergedState(
                     index1.deployChains.toSet,
                     pre1.toBlake2b256Hash,
                     rm.getHistoryRepo
                   )
        mPost2 <- MergeScope
                   .computeMergedState(
                     index2.deployChains.toSet,
                     pre2.toBlake2b256Hash,
                     rm.getHistoryRepo
                   )
        mPost12 <- MergeScope
                    .computeMergedState(
                      index12.deployChains.toSet,
                      pre1.toBlake2b256Hash,
                      rm.getHistoryRepo
                    )
        mPost2Batch <- MergeScope
                        .computeMergedState(
                          index1.deployChains.toSet ++ index2.deployChains.toSet,
                          pre1.toBlake2b256Hash,
                          rm.getHistoryRepo
                        )
      } yield {
        index1.deployChains.size shouldBe 1
        index2.deployChains.size shouldBe 1
        DeployChainIndex
          .deploysAreConflicting(index1.deployChains.head, index2.deployChains.head) shouldBe false
        DeployChainIndex
          .depends(index2.deployChains.head, index1.deployChains.head) shouldBe true
        DeployChainIndex
          .depends(index1.deployChains.head, index2.deployChains.head) shouldBe false
        mPost1 shouldBe post1.toBlake2b256Hash
        mPost2 shouldBe post2.toBlake2b256Hash
        mPost12 shouldBe post2.toBlake2b256Hash
        // this fails because random generator for merged value is computed differently when merging 2 changes
        // and when executing 2 changes one after another. [[RholangMergingLogic line 105]]
        // In case of merge RND is a merge of 2 changes
        // In case of execution RND is reused latest one
        mPost2Batch shouldBe post2.toBlake2b256Hash
      }
    }
  }

  "deploy with correct private key" should "update the rho:rchain:authKey right" in effectTest {
    val update = Source.fromResource("UpdateAuthKey/UpdateAuthKey.rho").mkString
    val updateDeploy =
      ConstructDeploy.sourceDeployNow(update, p1, 100000000L, 0L, shardId = shardId)
    val transferAmount = 100000
    val pub1RevAddr    = RevAddress.fromPublicKey(pub1).get.toBase58
    val pub2RevAddr    = RevAddress.fromPublicKey(noPermissionKeyPub).get.toBase58
    val transferTerm =
      s"""
        #new rl(`rho:registry:lookup`), RevVaultCh, vaultCh, toVaultCh, deployerId(`rho:rchain:deployerId`), stdout(`rho:io:stdout`),revVaultKeyCh, resultCh in {
        #  rl!(`rho:rchain:revVault`, *RevVaultCh) |
        #  for (@(_, RevVault) <- RevVaultCh) {
        #    @RevVault!("findOrCreate", "${pub1RevAddr}", *vaultCh) |
        #    @RevVault!("findOrCreate", "${pub2RevAddr}", *toVaultCh) |
        #    @RevVault!("deployerAuthKey", *deployerId, *revVaultKeyCh) |
        #    for (@(true, vault) <- vaultCh; key <- revVaultKeyCh; @(true, toVault) <- toVaultCh) {
        #      @vault!("transfer", "${pub2RevAddr}", $transferAmount, *key, *resultCh) |
        #      for (@res <- resultCh) { stdout!(("outcome", res)) }
        #    }
        #  }
        #}""".stripMargin('#')

    val exploreUpdateResultTerm =
      """new return, registryLookup(`rho:registry:lookup`), resCh, ret in {
                        #  registryLookup!(`rho:rchain:authKey`, *resCh) |
                        #  for (@(nonce, authKey) <- resCh){
                        #    @authKey!("add", 100, *ret)|
                        #    for (@number <- ret){
                        #      return!(number)
                        #    }
                        #  }
                        #}""".stripMargin('#')
    TestNode.standaloneEff(genesis).use { node =>
      val rm = node.runtimeManager
      for {
        b2      <- node.addBlock(updateDeploy)
        ret     <- rm.playExploratoryDeploy(getBalanceTerm(pub2RevAddr), b2.postStateHash)
        _       = assert(b2.state.deploys.head.cost.cost > 0L, s"$b2 deploy cost is 0L")
        _       = assert(b2.state.deploys.head.systemDeployError.isEmpty, s"$b2 system deploy failed")
        _       = assert(!b2.state.deploys.head.isFailed, s"$b2 deploy failed")
        balance = ret.head.exprs.head.getGInt
        b5 <- node
               .addBlock(
                 ConstructDeploy
                   .sourceDeployNow(transferTerm, sec = p1, shardId = shardId, phloLimit = 900000L)
               )
        ret2 <- rm.playExploratoryDeploy(getBalanceTerm(pub2RevAddr), b5.postStateHash)
        _    = assert(b5.state.deploys.head.cost.cost > 0L, s"$b5 deploy cost is 0L")
        _    = assert(b5.state.deploys.head.systemDeployError.isEmpty, s"$b5 system deploy failed")
        _    = assert(!b5.state.deploys.head.isFailed, s"$b5 deploy failed")
        _    = assert(ret2.head.exprs.head.getGInt == balance + transferAmount.toLong)
        ret3 <- rm.playExploratoryDeploy(exploreUpdateResultTerm, b2.postStateHash)
        _    = assert(ret3.head.exprs.head.getGInt == 100)
      } yield ()
    }
  }

  "deploy with incorrect private key" should "return auth failure" in effectTest {
    val update = Source.fromResource("UpdateAuthKey/UpdateAuthKey.rho").mkString
    val updateDeploy =
      ConstructDeploy.sourceDeployNow(update, noPermissionKey, 100000000L, 0L, shardId = shardId)

    TestNode.standaloneEff(genesis).use { node =>
      val rm = node.runtimeManager
      for {
        b2 <- node.addBlock(updateDeploy)
        _  = assert(b2.state.deploys.head.cost.cost > 0L, s"$b2 deploy cost is 0L")
        _  = assert(b2.state.deploys.head.systemDeployError.isEmpty, s"$b2 system deploy failed")
        _  = assert(!b2.state.deploys.head.isFailed, s"$b2 deploy failed")

        // Get update contract result
        updateResult                                        <- rm.getData(b2.postStateHash)(GDeployId(updateDeploy.sig))
        RhoTuple2((RhoBoolean(success), RhoString(errMsg))) = updateResult.head
        // Expect failed update
        _ = assert(!success, s"update should fail, instead: $errMsg")
      } yield ()
    }
  }
}
