package coop.rchain.sdk.cache

import cats.effect.{Async, Deferred, Ref}
import cats.syntax.all._
import coop.rchain.sdk.error.FatalError

import scala.collection.mutable

trait Cache[F[_]] {
  def cached[A](k: String, compute: F[A]): F[A]
}

object Cache {
  def apply[F[_]](implicit cache: Cache[F]): Cache[F] = cache

  def noOp[F[_]]: Cache[F] = new Cache[F] {
    override def cached[A](k: String, compute: F[A]): F[A] = compute
  }

  /** LRU cache limited by number of keys. */
  def lru[F[_]: Async](size: Int): F[Cache[F]] =
    Ref.of(new mutable.LinkedHashMap[String, Deferred[F, AnyVal]]).map { st =>
      new Cache[F] {
        override def cached[A](k: String, compute: F[A]): F[A] =
          for {
            newDef <- Deferred[F, A]
            d <- st.modify { st =>
                  st.get(k)
                    .map { existing =>
                      (st, existing.asInstanceOf[Deferred[F, A]])
                    }
                    .getOrElse {
                      val newSt = st.addOne(k -> newDef.asInstanceOf[Deferred[F, AnyVal]])
                      val s     = if (newSt.sizeIs > size) newSt.drop(newSt.size - size) else newSt
                      (s, newDef)
                    }
                }
            _ <- compute.flatMap(d.complete).whenA(newDef == d)
            r <- d.get
          } yield r
      }
    }
}
