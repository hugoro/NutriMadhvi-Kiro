package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO
import java.io.File
import kotlin.math.abs

/**
 * Invariants for the demo_muscle_* A-pose renders (v43.13+, 2026-10-23) — the
 * realistic figures rendered by the Plano demo cards AND, since v43.18, by the
 * "Silhueta com objetivos" card in Visão geral (SilhouetteGoalCard).
 *
 * WHY: that card's zone-marker Y bands and flank edge maps are calibrated to
 * the BODY BBOX of these PNGs (per-asset body row spans; see the calibration
 * comment in SilhouetteGoalCard.kt). A re-render that shifts or rescales the
 * figure inside the canvas would silently misplace the markers — the span
 * assertions below catch it. The canvas must also stay 768×1344:
 * pngAspectFor and the demo card layout assume that size.
 *
 * NOTE (v43.18): unlike the retired line-art silhouettes, these renders are
 * NOT required to keep a side gutter — the A-pose arms can reach the canvas
 * edge (male renders touch x=1..2). Markers sit on the torso/limb, never on
 * the arm tips, so an edge-touching arm is fine.
 *
 * Decodes with javax.imageio (plain JVM unit test — no Robolectric; the
 * ~1MB A-pose PNGs trip Robolectric's software BitmapFactory).
 *
 * How to run:  cd app && ../gradlew testDebugUnitTest --tests "com.example.SilhouetteAssetTest"
 */
class SilhouetteAssetTest {

    private data class BodyBox(val top: Int, val bottom: Int, val left: Int, val right: Int)

    private fun decode(name: String): java.awt.image.BufferedImage {
        val file = File("src/main/res/drawable/$name")
        val src = ImageIO.read(file)
        assertTrue("missing or undecodable drawable src/main/res/drawable/$name", src != null)
        // Force an ARGB canvas so the alpha channel survives regardless of
        // what color type ImageIO picked for the PNG.
        val img = java.awt.image.BufferedImage(src.width, src.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.drawImage(src, 0, 0, null)
        g.dispose()
        return img
    }

    // alpha STRICTLY ABOVE threshold — matches the calibration measurements.
    private fun bodyBox(img: java.awt.image.BufferedImage, threshold: Int): BodyBox {
        var top = img.height; var bottom = -1; var left = img.width; var right = -1
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                if (img.getRGB(x, y) ushr 24 > threshold) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        return BodyBox(top, bottom, left, right)
    }

    private fun close(v: Int, expected: Int, tol: Int, what: String) {
        assertTrue("$what: got $v, expected $expected ±$tol", abs(v - expected) <= tol)
    }

    @Test
    fun demoMuscleAssets_canvas768x1344_andBodySpansInvariant() {
        // name → (bodyTop, bodyBottom) rows at α>60, measured 2026-10-23 (PIL).
        // v43.20: the male set was regenerated — spans re-measured (34..1309);
        // the male posterior is added here so the Plano-card asset is held to
        // the same invariant as the goal-card assets.
        val spans = mapOf(
            "demo_muscle_male_frontal.png"   to (34 to 1309),
            "demo_muscle_female_frontal.png" to (34 to 1308), // v43.24: full-body single render (head to sole)
            "demo_muscle_male_lateral.png"   to (34 to 1309),
            "demo_muscle_female_lateral.png" to (115 to 1286),
            "demo_muscle_male_posterior.png" to (34 to 1309),
        )
        for ((name, span) in spans) {
            val img = decode(name)
            assertEquals("$name canvas width", 768, img.width)
            assertEquals("$name canvas height", 1344, img.height)
            val box = bodyBox(img, 60)
            close(box.top, span.first, 3, "$name body top row")
            close(box.bottom, span.second, 3, "$name body bottom row")
            // Full-body render: tall enough and not degenerate. (150px is the
            // floor on purpose — a slim figure seen from the side is narrow:
            // the female lateral silhouette's bbox is 189px wide at 768px.)
            assertTrue("$name body height degenerate", box.bottom - box.top >= 1000)
            assertTrue("$name body width degenerate", box.right - box.left >= 150)
        }
    }
}
