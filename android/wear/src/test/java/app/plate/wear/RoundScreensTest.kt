package com.zandaulion.bitey.wear

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import java.io.File
import kotlin.math.hypot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The home list on the two round watches Play's review asks for (small
 * round 192 dp, large round 227 dp), at the default and the largest font.
 *
 * Play's watch shapes review looks at the list scrolled to its end, so that
 * is what this checks: there, every line of text in the lower half of the
 * screen, and every button resting there, must lie inside the circle. The
 * screens are also saved as PNGs in build/round-screens to look at.
 *
 * The data is the shape of the review's own: three quick bites, then four
 * meals to repeat whose labels wrap onto two lines.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RoundScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(qualifiers = SMALL) fun smallRound() = check("small", 1f, review)
    @Test @Config(qualifiers = SMALL) fun smallRoundLargestFont() = check("small-font", 1.24f, review)
    @Test @Config(qualifiers = SMALL) fun smallRoundBitesOnly() = check("small-bites", 1f, bitesOnly)
    @Test @Config(qualifiers = LARGE) fun largeRound() = check("large", 1f, review)
    @Test @Config(qualifiers = LARGE) fun largeRoundLargestFont() = check("large-font", 1.24f, review)

    @Test @Config(qualifiers = SMALL)
    fun smallRoundWaiting() {
        compose.setContent { BiteyApp(null, Status.Idle, {}, {}, {}) }
        save("small-waiting")
        assertTextInside("small-waiting", lowerHalfOnly = false)
    }

    private fun check(name: String, fontScale: Float, json: String) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent { BiteyApp(Today.parse(json), Status.Idle, {}, {}, {}) }
        save("$name-0-top")
        compose.onRoot().performTouchInput { swipeUp(bottom * 0.7f, top + height * 0.4f, 400) }
        settle()
        save("$name-1-mid")
        repeat(8) {
            compose.onRoot().performTouchInput { swipeUp(bottom * 0.9f, top + height * 0.1f, 120) }
            settle()
        }
        save("$name-2-end")
        assertTextInside("$name-2-end", lowerHalfOnly = true)
        assertButtonsInside("$name-2-end")
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
    }

    private val size get() = compose.onRoot().fetchSemanticsNode().boundsInRoot

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    /** Visible text lines must have all four corners inside the circle. */
    private fun assertTextInside(shot: String, lowerHalfOnly: Boolean) {
        val screen = size
        nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .map { it.boundsInRoot }
            .filter { it.height > 0f && it.top >= screen.top && it.bottom <= screen.bottom }
            .filter { !lowerHalfOnly || it.center.y > screen.center.y }
            .forEach { box ->
                listOf(box.topLeft, box.topRight, box.bottomLeft, box.bottomRight).forEach { corner ->
                    val off = hypot(corner.x - screen.center.x, corner.y - screen.center.y) - screen.width / 2f
                    assertTrue("$shot: text at $box reaches ${off}px past the round edge", off <= 0.5f)
                }
            }
    }

    /** Buttons resting in the lower half: a pill (half-round ends) inside the circle. */
    private fun assertButtonsInside(shot: String) {
        val screen = size
        nodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .map { it.boundsInRoot }
            .filter { it.height > 0f && it.top >= screen.center.y && it.bottom <= screen.bottom }
            .forEach { box -> assertTrue("$shot: button at $box crosses the round edge", pillInside(box, screen)) }
    }

    private fun pillInside(box: Rect, screen: Rect): Boolean {
        val r = box.height / 2f
        val reach = (box.width / 2f - r).coerceAtLeast(0f)
        val dx = (box.center.x - screen.center.x).let { kotlin.math.abs(it) } + reach
        val dy = box.center.y - screen.center.y
        return hypot(dx, dy) + r <= screen.width / 2f + 0.5f
    }

    private fun save(name: String) {
        val dir = File("build/round-screens").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val SMALL = "w192dp-h192dp-round-watch-xhdpi"
        const val LARGE = "w227dp-h227dp-round-watch-xhdpi"

        val bites = """
            {"key":"b1","label":"Bite","icon":"🍏","kcal":50},
            {"key":"b2","label":"Handful","icon":"🥜","kcal":100},
            {"key":"b3","label":"Snack","icon":"🥐","kcal":200}
        """
        val review = """
            {"day":"2099-01-01","kcal":530,"burn":2300,"protein":21,"carbs":60,"fat":22,
             "bites":[$bites],
             "meals":[
               {"key":"m1","label":"Eggs","icon":"🍳","kcal":180},
               {"key":"m2","label":"Snack (~200 kcal)","icon":"🥐","kcal":200},
               {"key":"m3","label":"Bite (~50 kcal)","icon":"🍏","kcal":50},
               {"key":"m4","label":"Handful (~100 kcal)","icon":"🥜","kcal":100}]}
        """
        val bitesOnly = """{"day":"2099-01-01","kcal":0,"bites":[$bites],"meals":[]}"""
    }
}
