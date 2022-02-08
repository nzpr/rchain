package coop.rchain.casper.pCasper
import cats.Monad
import cats.syntax.all._

/**
  * Safety Oracle defines whether message should be finalized against some partition.
  * It outputs a partition of senders inside which message is safe.
  * If/when message is declared as a safe against a partition, it is safe to merge message into provisional state,
  * rejecting conflicts with already provisioned body of a partition.
  *
  * IMPORTANT: once message safe against supermajority partition is found, after merging this is the new final state.
  */
object SafetyOracle {

  /**
    * Whether it is safe to finalize the message.
    * Message is always finalized as the part of some partition.
    *
    * @param witnessesF defines "levels". Next level of messages consists of message that sees current level or
    *                   descendants of current level
    * @return if Some(partition) is returned - message is safe to finalize against partition,
    *         if None - message is not safe.
    */
  def run[F[_]: Monad, M, S](m: M)(
      witnessesF: M => F[Map[S, M]],
      justificationsF: M => F[Map[S, M]]
  )(sender: M => S): F[Option[Set[S]]] = {

    def nextLvl(curLvl: List[M]): F[List[M]] =
      curLvl
        .traverse(witnessesF)
        .map { witMaps =>
          // Senders that have a witness message for each message in the current level.
          val nextSenders = witMaps.map(_.keySet).reduceOption(_ intersect _).getOrElse(Set())
          witMaps.flatMap(_.filterKeys(nextSenders.contains).valuesIterator)
        }

    for {
      // Biggest possible partition that message can be part of is the partition
      // consisting of senders that witness the message.
      lvl1 <- witnessesF(m).map(_.valuesIterator.toList)
      lvl2 <- nextLvl(lvl1)
      isSafe = (lvl1, lvl2, 0).tailRecM {
        case (l1, l2, num) =>
          //println(s"traverse $num")
          // partition visible in the last level
          val partition = l2.map(sender).toSet
          // Message is part of detected partition
          val partitionIncludesM = partition.contains(sender(m))
          // Message cannot be part of another partition if messages that prove the partition
          // have the same justifications from senders out of the partition
          val partitionIsCertainF = (l1.toVector ++ l2)
            .traverse(justificationsF(_).map(_.filterNot(j => partition.contains(j._1)).toSet))
            .map(_.distinct.size == 1)
          val provedNotSafe = !partitionIncludesM // partition implied does not include sender of the target message
          if (provedNotSafe)
            none[Set[S]].asRight[(List[M], List[M], Int)].pure
          else {
            // Once safety is proved return partition inside which message is safe
            val safeCase = {
              //println("safe")
              partition.some.asRight[(List[M], List[M], Int)].pure
            }
            // If safety is not proved yet - proceed with the next layer
            val uncertainCase = nextLvl(lvl2).map { nextL =>
              if (nextL.isEmpty) none[Set[S]].asRight[(List[M], List[M], Int)]
              else (l2, nextL, num + 1).asLeft[Option[Set[S]]]
            }
            partitionIsCertainF.ifM(safeCase, uncertainCase)
          }
      }
      r <- isSafe
    } yield r
  }
}
