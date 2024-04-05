package coop.rchain.sdk

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import coop.rchain.sdk.cache.Cache
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CacheSpec extends AnyFlatSpec with Matchers {

  it should "cache a value" in {
    val c: Cache[IO] = Cache.lru[IO](10).unsafeRunSync()
    var counter      = 0
    val f = IO.delay {
      counter += 1
      0
    }

    val p = c.cached("key", f) >> c.cached("key", f)

    p.unsafeRunSync() shouldBe 0
    counter shouldBe 1
  }

  it should "remove oldest records once hit size limit" in {
    val c: Cache[IO] = Cache.lru[IO](2).unsafeRunSync()
    var counter      = 0
    val f = IO.delay {
      counter += 1
      0
    }

    val p =
      c.cached("key1", f) >> c.cached("key2", f) >> c.cached("key3", f) >>
        // this call should also execute f, since cache size is 2 and first call on key1 is removed by now
        c.cached("key1", f)

    p.unsafeRunSync() shouldBe 0
    counter shouldBe 4
  }
}
