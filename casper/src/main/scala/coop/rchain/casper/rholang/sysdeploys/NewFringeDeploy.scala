package coop.rchain.casper.rholang.sysdeploys

import com.google.protobuf.ByteString
import coop.rchain.casper.rholang.BlockRandomSeed
import coop.rchain.casper.rholang.types.{SystemDeploy, SystemDeployUserError}
import coop.rchain.crypto.PublicKey
import coop.rchain.crypto.hash.Blake2b512Random
import coop.rchain.models.NormalizerEnv.{Contains, ToEnvMap}
import coop.rchain.models.rholang.RhoType._
import coop.rchain.rspace.hashing.Blake2b256Hash

// Currently we use parentHash as initial random seed
final case class NewFringeDeploy(initialRand: Blake2b512Random) extends SystemDeploy(initialRand) {
  import coop.rchain.models._
  import rholang.{implicits => toPar}
  import shapeless._

  type Output = (RhoBoolean, Either[RhoString, RhoNil])
  type Result = Unit

  import toPar._
  type Env =
    (`sys:casper:authToken` ->> GSysAuthToken) :: (`sys:casper:return` ->> GUnforgeable) :: HNil
  protected override val envsReturnChannel = Contains[Env, `sys:casper:return`]
  protected override val toEnvMap          = ToEnvMap[Env]

  protected val normalizerEnv: NormalizerEnv[Env] = new NormalizerEnv(
    mkSysAuthToken :: mkReturnChannel :: HNil
  )

  override val source: String =
    """#new rl(`rho:registry:lookup`),
      #  poSCh,
      #  sysAuthToken(`sys:casper:authToken`),
      #  return(`sys:casper:return`), stdout(`rho:io:stdout`)
      #in {
      #  rl!(`rho:rchain:pos`, *poSCh) |
      #  for(@(_, Pos) <- poSCh) {
      #    @Pos!("newFringe", *sysAuthToken, *return)
      #  }
      #}""".stripMargin('#')

  protected override val extractor = Extractor.derive

  protected override def processResult(
      value: (Boolean, Either[String, Unit])
  ): Either[SystemDeployUserError, Unit] = value match {
    case (true, _)               => Right(())
    case (false, Left(errorMsg)) => Left(SystemDeployUserError(errorMsg))
    case _                       => Left(SystemDeployUserError("<no cause>"))
  }
}

object NewFringeDeploy {
  def apply(prevFringe: Blake2b256Hash): NewFringeDeploy =
    NewFringeDeploy(rand(prevFringe))

  def rand(prevFringeHash: Blake2b256Hash): Blake2b512Random = BlockRandomSeed.randomGenerator(
    "shardId",
    0,
    PublicKey.apply(ByteString.EMPTY),
    prevFringeHash
  )
}
