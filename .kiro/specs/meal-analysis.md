# Spec: Meal Analysis Pipeline

## Overview
The meal analysis pipeline takes a photo (or text description) of a meal and returns structured macro estimates, persisting them as a `FoodEntry` in Room.

## Requirements

### Functional
- **R1**: Accept a `Bitmap` or image `Uri` as input
- **R2**: Send to primary vision model (Gemini 3.6-flash); escalate to 3.5-flash if confidence < threshold
- **R3**: Parse response into `FoodEntry` with fields: `name`, `calories`, `carbsG`, `proteinG`, `fatG`, `saturatedFatG`, `fiberG`, `sugarG`, `effectiveFreeSugarsG`, `ingredientsJson`
- **R4**: Enrich macro estimates using local cluster medians (per-100g from user's own history) when available
- **R5**: Persist `FoodEntry` to Room with `analysisModel` field recording which model produced the result
- **R6**: Retry failed analyses via `retryPendingMealAnalyses`; preserve `analysisModel` through retry path
- **R7**: Escalate to Pro model when fat content is high (saturated fat heuristic) or confidence is low/medium

### Non-functional
- **R8**: Time-to-first-token target ≤ 8 seconds on typical meal photo
- **R9**: Graceful degradation: if vision model returns 503, fall back to text-only model with description prompt
- **R10**: Never log API keys; strip from OkHttp interceptor logs

## Data model
```
FoodEntry {
  id: Long (primary key, auto-generate)
  name: String
  calories: Int
  carbsG: Float
  proteinG: Float
  fatG: Float
  saturatedFatG: Float
  fiberG: Float
  sugarG: Float
  effectiveFreeSugarsG: Float
  ingredientsJson: String?
  imageUri: String?          // persistent filesDir path, never cache://
  analysisModel: String?
  occursOn: LocalDate
  mealType: MealType
  needsReanalysis: Boolean
}
```

## Tasks
- [ ] Verify cascade logic covers all 503/rate-limit cases
- [ ] Add property-based tests for macro parser (valid JSON in, FoodEntry out)
- [ ] Confirm `effectiveFreeSugars()` is the sole computation point (no duplicates)
