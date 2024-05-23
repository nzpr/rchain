package coop.rchain.casper.batch1

import coop.rchain.casper.blocks.proposer.{Created, NoNewDeploys}
import coop.rchain.casper.helper.TestNode._
import coop.rchain.casper.helper.{BlockApiFixture, TestNode}
import coop.rchain.casper.util.ConstructDeploy
import coop.rchain.p2p.EffectsTestInstances.LogicalTime
import coop.rchain.shared.scalatestcontrib._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.{EitherValues, Inspectors}
import org.scalatest.matchers.should.Matchers
import coop.rchain.shared.syntax._

class MultiParentCasperDeploySpec
    extends AnyFlatSpec
    with Matchers
    with Inspectors
    with BlockApiFixture
    with EitherValues {

  import coop.rchain.casper.util.GenesisBuilder._

  val genesis = buildGenesis()

  it should "not create a block with a repeated deploy" in effectTest {
    TestNode.networkEff(genesis, networkSize = 2).use { nodes =>
      val List(node0, node1) = nodes.toList
      for {

        deploy0 <- ConstructDeploy
                    .basicDeployData[Effect](0, shardId = genesis.genesisBlock.shardId)
        _ <- node0.propagateBlock(deploy0)(node1)
        // add the same deploy to node 1
        _ <- node1.blockDagStorage.addDeploy(deploy0)
        // add one more deploy to node 1
        deploy1 <- ConstructDeploy
                    .basicDeployData[Effect](0, shardId = genesis.genesisBlock.shardId)
        _                  <- node1.blockDagStorage.addDeploy(deploy1)
        createBlockResult2 <- node1.proposeSync.attempt
        // block should be created
        _    = createBlockResult2 shouldBe a[Right[_, _]]
        hash = createBlockResult2.value
        // but deploy0 should not be there, only deploy1
        deploys <- node1.blockStore
                    .get1(hash)
                    .map(_.map(_.state).map(x => x.deploys.map(_.deploy.sig)))
      } yield {
        deploys.isDefined shouldBe true
        deploys.get shouldBe List(deploy1.sig)
      }
    }
  }

  it should "fail when deploying with insufficient phlos" in effectTest {
    TestNode.standaloneEff(genesis).use { node =>
      for {
        deployData     <- ConstructDeploy.sourceDeployNowF[Effect]("Nil", phloLimit = 1)
        r              <- node.createBlock(deployData)
        Created(block) = r
      } yield assert(block.state.deploys.head.isFailed)
    }
  }

  it should "succeed if given enough phlos for deploy" in effectTest {
    TestNode.standaloneEff(genesis).use { node =>
      for {
        deployData     <- ConstructDeploy.sourceDeployNowF[Effect]("Nil", phloLimit = 100)
        r              <- node.createBlock(deployData)
        Created(block) = r
      } yield assert(!block.state.deploys.head.isFailed)
    }
  }

  it should "reject deploy with phloPrice lower than minPhloPrice" in effectTest {
    TestNode.standaloneEff(genesis).use { node =>
      val minPhloPrice = node.minPhloPrice
      val phloPrice    = minPhloPrice - 1L
      for {
        deployData <- ConstructDeploy
                       .sourceDeployNowF[Effect](
                         "Nil",
                         phloPrice = phloPrice,
                         shardId = genesis.genesisBlock.shardId
                       )
        blockApi <- createBlockApi(node)
        err      <- blockApi.deploy(deployData).attempt
      } yield {
        val ex = err.left.value
        ex shouldBe a[RuntimeException]
        ex.getMessage shouldBe s"Phlo price $phloPrice is less than minimum price $minPhloPrice."
      }
    }
  }
}
