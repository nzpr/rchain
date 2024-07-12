package coop.rchain.casper.serialize

import cats.Eval
import cats.effect.IO
import coop.rchain.casper.MultiParentCasper
import coop.rchain.casper.helper.BlockDagStorageFixture
import coop.rchain.casper.merging.BlockIndex
import coop.rchain.casper.util.GenesisBuilder
import coop.rchain.comm.rpc.Serialize
import coop.rchain.sdk.serialize.Serialize
import coop.rchain.shared.Log
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SerializeSpec extends AnyFlatSpec with Matchers with BlockDagStorageFixture {

  val genesisContext: GenesisBuilder.GenesisContext = GenesisBuilder.buildGenesis()

  def roundTrip[A](x: A)(implicit s: Serialize[Eval, A]): A =
    Serialize.parseProtobuf(Serialize.streamProtobuf[A](x))

  "roundTripSerialize" should "work for BlockIndex" in {
    withGenesis(genesisContext) { implicit blockStore => _ => implicit runtimeManager =>
      BlockIndex
        .getBlockIndex[IO](genesisContext.genesisBlock.blockHash)
        .map {
          case target @ BlockIndex(blockHash, deployChains) =>
            import coop.rchain.macros.serialize.auto._
            import coop.rchain.casper.serialize.auto._

            // getProtoHash should provide stable hashing
            Serialize.getProtoHash(target) shouldBe Serialize.getProtoHash(target)

            // Received over the wire index should be the same
            val targetRt = roundTrip(target)
            target shouldBe targetRt

            // It should be the same up to binary format
            Serialize.getProtoHash(target) shouldBe Serialize.getProtoHash(targetRt)
        }
    }
  }
}
