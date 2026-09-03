package com.example.data.meal.ensemble

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in live smoke test against the real Groq endpoint.
 *
 * Skipped unless GROQ_LIVE_KEY and GROQ_LIVE_IMAGE are provided, so the normal
 * unit test run stays hermetic and free.
 */
class LiveGroqSmokeTest {

    @Test
    fun analyzesRealMealPhoto() {
        val key = System.getenv("GROQ_LIVE_KEY").orEmpty()
        val imagePath = System.getenv("GROQ_LIVE_IMAGE").orEmpty()
        val promptPath = System.getenv("GROQ_LIVE_PROMPT").orEmpty()
        assumeTrue(key.isNotBlank() && imagePath.isNotBlank())

        val base64 = File(imagePath).readText().trim()
        val core = if (promptPath.isNotBlank()) File(promptPath).readText() else ""

        // JVM-side stand-in for the Android Bitmap shrinker, so this exercises
        // the same oversized-image code path the device takes.
        val provider = QwenGroqFoodVisionProvider(
            apiKey = key,
            promptBuilder = { core + EnsemblePrompts.CANONICAL_NAMING_RULES },
            imageShrinker = { b64, budget ->
                val src = javax.imageio.ImageIO.read(
                    java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(b64))
                )
                var result: String? = null
                for (scale in doubleArrayOf(0.85, 0.72, 0.6, 0.5, 0.4)) {
                    val w = (src.width * scale).toInt()
                    val h = (src.height * scale).toInt()
                    val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    out.createGraphics().apply {
                        setRenderingHint(
                            java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                        )
                        drawImage(src, 0, 0, w, h, null); dispose()
                    }
                    val bos = java.io.ByteArrayOutputStream()
                    javax.imageio.ImageIO.write(out, "jpg", bos)
                    val enc = java.util.Base64.getEncoder().encodeToString(bos.toByteArray())
                    println("SHRINK scale=$scale dim=${w}x${h} chars=${enc.length}")
                    if (enc.length <= budget) { result = enc; break }
                }
                result
            },
        )

        val request = FoodVisionRequest(
            base64Image = base64,
            mimeType = "image/jpeg",
            promptHint = "A balanced daily meal",
            language = "pt",
        )

        val outcome = runBlocking { provider.analyze(request) }
        println("LIVE_OUTCOME=$outcome")

        when (outcome) {
            is FoodVisionOutcome.Success -> {
                val r = outcome.evidence.result
                println("LIVE_NAME=${r.name}")
                println("LIVE_KCAL=${r.calories} P=${r.protein} C=${r.carbs} F=${r.fat}")
                println("LIVE_INGREDIENTS=${r.ingredients?.map { "${it.name}:${it.weightGrams}g" }}")
                println("LIVE_SUMMARY=${r.fullSummary}")
                println("LIVE_CONF=${outcome.evidence.confidence}")
                println("LIVE_LATENCY=${outcome.evidence.latencyMs}ms")
            }
            is FoodVisionOutcome.Failure ->
                println("LIVE_FAILURE kind=${outcome.kind} msg=${outcome.message}")
        }
    }
}
