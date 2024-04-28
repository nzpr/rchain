package coop.rchain.sdk

import cats.syntax.all._
import coop.rchain.sdk.dag.View
import coop.rchain.sdk.dag.View.{IncludeBottom, IncludeNone, IncludeTop}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ViewSpec extends AnyFlatSpec with Matchers {

  "Combination of views" should "create a valid view" in {
    val view1 = View(Map(1 -> Range.inclusive(1, 3), 2 -> Range.inclusive(1, 3)))
    val view2 =
      View(Map(1 -> Range.inclusive(1, 3), 2 -> Range.inclusive(2, 5), 3 -> Range.inclusive(1, 2)))

    val ref = Map(
      1 -> Range.inclusive(1, 3),
      2 -> Range.inclusive(1, 5),
      3 -> Range.inclusive(1, 2)
    )

    val r = List(view1, view2).combineAll
    r.seen shouldBe ref
  }

  "Diff of views" should "create valid view" in {
    val view1 = View(Map(1 -> Range.inclusive(1, 3), 2 -> Range.inclusive(1, 5)))
    val view2 =
      View(Map(1 -> Range.inclusive(1, 3), 2 -> Range.inclusive(1, 3), 3 -> Range.inclusive(1, 2)))

    val refTop = Map(
      1 -> Range(0, 0),
      2 -> Range.inclusive(4, 5)
    )
    val refBottom = Map(
      1 -> Range(0, 0),
      2 -> Range.inclusive(3, 4)
    )
    val refNone = Map(
      1 -> Range(0, 0),
      2 -> Range.inclusive(4, 4)
    )

    View.diff(view1, view2, IncludeTop).seen shouldBe refTop
    View.diff(view1, view2, IncludeBottom).seen shouldBe refBottom
    View.diff(view1, view2, IncludeNone).seen shouldBe refNone
  }
}
