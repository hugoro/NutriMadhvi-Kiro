package com.example.ui

import com.google.mlkit.vision.pose.PoseLandmark

/**
 * Android bridge: ML Kit pose landmarks → the pure-JVM [SilhouetteZoneMath]
 * landmark representation.
 *
 * This file is the ONLY place that references the ML Kit landmark int
 * numbering. SilhouetteZoneMath works with its own [SilhouetteZoneMath.LandmarkKey]
 * enum, so the math stays pure JVM and the SDK's numbering can never leak
 * into it (or drift silently).
 */
fun Map<Int, PoseLandmark>?.toLandmarkKeys(): Map<SilhouetteZoneMath.LandmarkKey, SilhouetteZoneMath.LandmarkPt>? {
    if (this == null) return null
    return entries
        .mapNotNull { (type, lm) ->
            val key = when (type) {
                PoseLandmark.NOSE -> SilhouetteZoneMath.LandmarkKey.NOSE
                PoseLandmark.LEFT_SHOULDER -> SilhouetteZoneMath.LandmarkKey.LEFT_SHOULDER
                PoseLandmark.RIGHT_SHOULDER -> SilhouetteZoneMath.LandmarkKey.RIGHT_SHOULDER
                PoseLandmark.LEFT_HIP -> SilhouetteZoneMath.LandmarkKey.LEFT_HIP
                PoseLandmark.RIGHT_HIP -> SilhouetteZoneMath.LandmarkKey.RIGHT_HIP
                PoseLandmark.LEFT_KNEE -> SilhouetteZoneMath.LandmarkKey.LEFT_KNEE
                PoseLandmark.RIGHT_KNEE -> SilhouetteZoneMath.LandmarkKey.RIGHT_KNEE
                PoseLandmark.LEFT_ANKLE -> SilhouetteZoneMath.LandmarkKey.LEFT_ANKLE
                PoseLandmark.RIGHT_ANKLE -> SilhouetteZoneMath.LandmarkKey.RIGHT_ANKLE
                else -> return@mapNotNull null
            }
            key to SilhouetteZoneMath.LandmarkPt(lm.position.x, lm.position.y, lm.inFrameLikelihood)
        }
        .toMap()
}
