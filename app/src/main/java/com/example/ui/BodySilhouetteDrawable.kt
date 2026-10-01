package com.example.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

/**
 * Programmatic anatomical silhouette paths for male and female bodies,
 * frontal and lateral views.
 *
 * All coordinates are normalised [0..1] relative to the canvas size and scaled
 * in [scaledPath]. The style matches anatomical illustration line drawings:
 * neutral stance, minimal pose, clear body-zone boundaries.
 *
 * Zone Y-fractions (shared with SilhouetteAnalyzer):
 *   0.00–0.14  head/neck
 *   0.14–0.28  shoulders
 *   0.28–0.50  torso (waist)
 *   0.50–0.65  hips
 *   0.65–0.80  thighs
 *   0.80–1.00  lower legs / feet
 */
object BodySilhouetteDrawable {

    // ─── Public API ──────────────────────────────────────────────────────────

    fun maleFrontalPath(size: Size): Path = scaledPath(size, MALE_FRONTAL)
    fun femaleFrontalPath(size: Size): Path = scaledPath(size, FEMALE_FRONTAL)
    fun maleLateralPath(size: Size): Path = scaledPath(size, MALE_LATERAL)
    fun femaleLateralPath(size: Size): Path = scaledPath(size, FEMALE_LATERAL)

    // ─── Path builder ────────────────────────────────────────────────────────

    private fun scaledPath(size: Size, pts: FloatArray): Path {
        val w = size.width
        val h = size.height
        return Path().apply {
            var i = 0
            moveTo(pts[i] * w, pts[i + 1] * h); i += 2
            while (i < pts.size - 5) {
                // Cubic bezier: two control points + endpoint
                cubicTo(
                    pts[i] * w, pts[i + 1] * h,
                    pts[i + 2] * w, pts[i + 3] * h,
                    pts[i + 4] * w, pts[i + 5] * h,
                )
                i += 6
            }
            close()
        }
    }

    // ─── Male frontal silhouette ──────────────────────────────────────────────
    // Neutral stance, arms slightly away from body. Broader shoulders, narrower
    // hips. All x values symmetric around 0.5; shape is mirrored left/right.

    private val MALE_FRONTAL = floatArrayOf(
        // Start: top of left shoulder
        0.26f, 0.18f,
        // Left neck curve up to head left
        0.27f, 0.10f,  0.34f, 0.02f,  0.40f, 0.01f,
        // Head top
        0.40f, 0.00f,  0.60f, 0.00f,  0.60f, 0.01f,
        // Head right to right neck
        0.66f, 0.02f,  0.73f, 0.10f,  0.74f, 0.18f,
        // Right shoulder out
        0.75f, 0.19f,  0.82f, 0.20f,  0.84f, 0.22f,
        // Right outer arm down
        0.85f, 0.23f,  0.86f, 0.32f,  0.85f, 0.42f,
        // Right elbow in toward torso
        0.84f, 0.46f,  0.80f, 0.50f,  0.78f, 0.52f,
        // Right hip flare
        0.78f, 0.53f,  0.74f, 0.56f,  0.72f, 0.60f,
        // Right thigh outer
        0.72f, 0.61f,  0.71f, 0.68f,  0.70f, 0.72f,
        // Right knee
        0.69f, 0.75f,  0.68f, 0.78f,  0.68f, 0.80f,
        // Right lower leg
        0.68f, 0.81f,  0.67f, 0.90f,  0.66f, 0.95f,
        // Right foot
        0.66f, 0.96f,  0.68f, 1.00f,  0.56f, 1.00f,
        // Crotch inner right to inner left
        0.56f, 0.99f,  0.52f, 0.70f,  0.50f, 0.65f,
        0.50f, 0.65f,  0.48f, 0.70f,  0.44f, 0.99f,
        // Left foot
        0.44f, 1.00f,  0.32f, 1.00f,  0.34f, 0.95f,
        // Left lower leg
        0.34f, 0.90f,  0.32f, 0.81f,  0.32f, 0.80f,
        // Left knee
        0.32f, 0.78f,  0.31f, 0.75f,  0.30f, 0.72f,
        // Left thigh outer
        0.29f, 0.68f,  0.28f, 0.61f,  0.28f, 0.60f,
        // Left hip
        0.26f, 0.56f,  0.22f, 0.53f,  0.22f, 0.52f,
        // Left elbow
        0.20f, 0.50f,  0.16f, 0.46f,  0.15f, 0.42f,
        // Left arm up
        0.14f, 0.32f,  0.15f, 0.23f,  0.16f, 0.22f,
        // Left shoulder
        0.18f, 0.20f,  0.25f, 0.19f,  0.26f, 0.18f,
    )

    // ─── Female frontal silhouette ────────────────────────────────────────────
    // Narrower shoulders, defined waist, wider hips.

    private val FEMALE_FRONTAL = floatArrayOf(
        // Start: top of left shoulder
        0.29f, 0.18f,
        // Left neck
        0.30f, 0.10f,  0.36f, 0.02f,  0.41f, 0.01f,
        // Head top
        0.41f, 0.00f,  0.59f, 0.00f,  0.59f, 0.01f,
        // Right neck
        0.64f, 0.02f,  0.70f, 0.10f,  0.71f, 0.18f,
        // Right shoulder
        0.72f, 0.19f,  0.78f, 0.21f,  0.80f, 0.23f,
        // Right arm outer
        0.81f, 0.24f,  0.82f, 0.33f,  0.81f, 0.43f,
        // Right elbow toward waist
        0.80f, 0.47f,  0.77f, 0.50f,  0.75f, 0.52f,
        // Waist narrow in
        0.73f, 0.53f,  0.68f, 0.54f,  0.66f, 0.56f,
        // Hip flare out
        0.66f, 0.57f,  0.75f, 0.62f,  0.76f, 0.65f,
        // Right thigh outer
        0.75f, 0.68f,  0.73f, 0.73f,  0.72f, 0.77f,
        // Right knee
        0.71f, 0.80f,  0.70f, 0.83f,  0.70f, 0.85f,
        // Right lower leg
        0.69f, 0.88f,  0.68f, 0.94f,  0.67f, 0.97f,
        // Right foot
        0.67f, 0.98f,  0.69f, 1.00f,  0.57f, 1.00f,
        // Crotch
        0.57f, 0.99f,  0.52f, 0.72f,  0.50f, 0.67f,
        0.50f, 0.67f,  0.48f, 0.72f,  0.43f, 0.99f,
        // Left foot
        0.43f, 1.00f,  0.31f, 1.00f,  0.33f, 0.97f,
        // Left lower leg
        0.32f, 0.94f,  0.31f, 0.88f,  0.30f, 0.85f,
        // Left knee
        0.30f, 0.83f,  0.29f, 0.80f,  0.28f, 0.77f,
        // Left thigh
        0.27f, 0.73f,  0.25f, 0.68f,  0.24f, 0.65f,
        // Left hip out
        0.25f, 0.62f,  0.34f, 0.57f,  0.34f, 0.56f,
        // Waist narrow
        0.32f, 0.54f,  0.27f, 0.53f,  0.25f, 0.52f,
        // Left elbow
        0.23f, 0.50f,  0.19f, 0.47f,  0.19f, 0.43f,
        // Left arm
        0.18f, 0.33f,  0.19f, 0.24f,  0.20f, 0.23f,
        // Left shoulder
        0.22f, 0.21f,  0.28f, 0.19f,  0.29f, 0.18f,
    )

    // ─── Male lateral silhouette ──────────────────────────────────────────────
    // Right profile view. Visible: chest projection, flat stomach, gluteal curve.

    private val MALE_LATERAL = floatArrayOf(
        // Start: back of head top
        0.35f, 0.01f,
        // Head front
        0.35f, 0.00f,  0.62f, 0.00f,  0.62f, 0.04f,
        // Face/chin
        0.62f, 0.05f,  0.60f, 0.11f,  0.56f, 0.13f,
        // Neck/throat
        0.56f, 0.14f,  0.55f, 0.16f,  0.54f, 0.18f,
        // Chest forward projection
        0.55f, 0.19f,  0.62f, 0.24f,  0.63f, 0.30f,
        // Abdomen (flat)
        0.63f, 0.31f,  0.60f, 0.40f,  0.58f, 0.48f,
        // Groin
        0.57f, 0.52f,  0.55f, 0.58f,  0.55f, 0.62f,
        // Front thigh
        0.55f, 0.63f,  0.56f, 0.72f,  0.55f, 0.78f,
        // Knee front
        0.54f, 0.80f,  0.53f, 0.83f,  0.53f, 0.86f,
        // Shin
        0.53f, 0.88f,  0.52f, 0.94f,  0.53f, 0.98f,
        // Foot
        0.53f, 0.99f,  0.58f, 1.00f,  0.38f, 1.00f,
        // Heel
        0.37f, 0.99f,  0.36f, 0.96f,  0.37f, 0.92f,
        // Calf back
        0.38f, 0.88f,  0.39f, 0.82f,  0.39f, 0.78f,
        // Back of knee
        0.40f, 0.75f,  0.41f, 0.72f,  0.41f, 0.68f,
        // Gluteal curve
        0.41f, 0.65f,  0.43f, 0.61f,  0.44f, 0.58f,
        0.44f, 0.57f,  0.46f, 0.52f,  0.46f, 0.50f,
        // Lower back (slight lordosis)
        0.45f, 0.48f,  0.42f, 0.40f,  0.41f, 0.32f,
        // Upper back / shoulder blade
        0.40f, 0.28f,  0.39f, 0.22f,  0.38f, 0.18f,
        // Back of neck
        0.37f, 0.16f,  0.36f, 0.12f,  0.35f, 0.08f,
        0.35f, 0.06f,  0.34f, 0.03f,  0.35f, 0.01f,
    )

    // ─── Female lateral silhouette ────────────────────────────────────────────
    // More pronounced bust projection, defined waist, larger gluteal curve.

    private val FEMALE_LATERAL = floatArrayOf(
        // Head back-top
        0.36f, 0.01f,
        0.36f, 0.00f,  0.61f, 0.00f,  0.61f, 0.04f,
        // Face
        0.60f, 0.06f,  0.59f, 0.11f,  0.55f, 0.13f,
        // Neck
        0.55f, 0.14f,  0.54f, 0.16f,  0.54f, 0.18f,
        // Bust projection (more pronounced than male)
        0.55f, 0.19f,  0.65f, 0.26f,  0.66f, 0.33f,
        // Waist in
        0.64f, 0.38f,  0.58f, 0.44f,  0.57f, 0.48f,
        // Belly/lower abdo — slight protrusion
        0.58f, 0.50f,  0.59f, 0.54f,  0.58f, 0.59f,
        // Front thigh
        0.57f, 0.63f,  0.57f, 0.72f,  0.56f, 0.78f,
        // Knee
        0.55f, 0.80f,  0.54f, 0.83f,  0.54f, 0.86f,
        // Shin
        0.54f, 0.89f,  0.53f, 0.95f,  0.54f, 0.99f,
        // Foot
        0.54f, 1.00f,  0.58f, 1.00f,  0.37f, 1.00f,
        // Heel
        0.36f, 0.99f,  0.35f, 0.95f,  0.36f, 0.90f,
        // Calf
        0.37f, 0.86f,  0.38f, 0.80f,  0.38f, 0.76f,
        // Back of knee
        0.39f, 0.73f,  0.40f, 0.70f,  0.40f, 0.65f,
        // Gluteal (larger)
        0.40f, 0.62f,  0.43f, 0.56f,  0.45f, 0.53f,
        0.46f, 0.51f,  0.47f, 0.48f,  0.46f, 0.46f,
        // Lower back (more curve)
        0.44f, 0.43f,  0.41f, 0.37f,  0.40f, 0.30f,
        // Upper back
        0.39f, 0.26f,  0.38f, 0.21f,  0.37f, 0.17f,
        // Back neck
        0.36f, 0.15f,  0.35f, 0.10f,  0.36f, 0.06f,
        0.36f, 0.04f,  0.35f, 0.02f,  0.36f, 0.01f,
    )
}
