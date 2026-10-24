package com.example

import com.example.data.performance.TrainingDemoCatalog
import com.example.ui.demoIllustrationResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * v43.10 (2026-10) — the "Força card with no data" class of bug.
 *
 * A strength tactic whose deterministic demo had no illustration crashed at
 * composition time ([demoIllustrationResource] threw `error(...)`), so the
 * whole tactic card lost its data — exactly the user-visible symptom "o
 * cartão Força não tem nenhuns dados". This test pins, for EVERY catalog
 * demo, both halves of the contract:
 *
 *  1. the LOCAL illustration asset exists on disk (240x240 RGBA PNG with
 *     real content — a blank placeholder would defeat the point), and
 *  2. [demoIllustrationResource] resolves its name to a drawable id
 *     WITHOUT throwing (closed-set mapping covers the whole catalog).
 *
 * Pure JVM — no Android runtime, no Robolectric.
 */
class TrainingDemoIllustrationTest {

    @Test
    fun every_catalog_demo_has_a_local_illustration_asset() {
        for (d in TrainingDemoCatalog.ALL) {
            val f = File("src/main/res/drawable/${d.illustrationName}.png")
            assertTrue("missing illustration asset for demo ${d.id}: $f", f.isFile)
            val img: BufferedImage = ImageIO.read(f)
            assertNotNull("unreadable illustration asset for demo ${d.id}: $f", img)
            assertEquals("asset width for ${d.id}", 240, img.width)
            assertEquals("asset height for ${d.id}", 240, img.height)
            // real content: at least a few opaque pixels (line art on
            // transparency) — a fully transparent PNG is a placeholder.
            var opaque = 0
            for (y in 0 until img.height step 2) {
                for (x in 0 until img.width step 2) {
                    if ((img.getRGB(x, y) ushr 24) > 0) opaque++
                }
            }
            assertTrue(
                "illustration asset for ${d.id} has no visible content (blank placeholder)",
                opaque > 20,
            )
        }
    }

    @Test
    fun every_catalog_demo_illustration_name_resolves_to_a_drawable() {
        for (d in TrainingDemoCatalog.ALL) {
            // must NOT throw — an unmapped name is a developer error that
            // used to take the whole tactic card down at composition time.
            val resId = runCatching {
                demoIllustrationResource(d.illustrationName)
            }.getOrElse { e: Throwable ->
                org.junit.Assert.fail(
                    "demoIllustrationResource threw for demo ${d.id} (${d.illustrationName}): $e"
                )
            }
            assertTrue(
                "demo ${d.id} resolved to a null/zero drawable id ($resId)",
                resId != 0,
            )
        }
    }
}
