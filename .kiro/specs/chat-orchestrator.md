# Spec: Chat Orchestrator

## Overview
The chat system routes user messages through a local-first orchestrator: intent classification → parallel data fetch → LLM synthesis. The model never guesses facts that can be read from the database.

## Requirements

### Functional
- **R1**: `IntentRouter` classifies each message via regex patterns before any LLM call
- **R2**: `DataFetcher` loads relevant snapshots (today's food log, activity summary, body composition, BMR) in parallel
- **R3**: LLM receives pre-fetched data as context; it synthesises, it does not retrieve
- **R4**: Fast-reply tier answers simple queries (today's calories, last meal) without a full model call
- **R5**: `ModelCircuitBreaker` tracks failures per model; open circuit skips to fallback
- **R6**: `PredictiveQaWorker` warms likely answers in background after each user message
- **R7**: Streaming response: emit tokens as they arrive; mark `Done` only when `finishReason == "STOP"`
- **R8**: Chat supports voice mode: TTS reads each assistant bubble; mic pill triggers STT
- **R9**: Meal proposals accepted with one tap create a `FoodEntry` equivalent to manual logging

### Non-functional
- **R10**: TTFT target ≤ 8 seconds (front A of 9-front latency plan)
- **R11**: `reverseLayout = false` + `scrollToItem(lastIndex)` — never `reverseLayout = true`
- **R12**: No sycophantic openers ("Tens razão", "Excelente pergunta"); banned in `ChatToneRules`

## Tasks
- [ ] Add property-based tests for IntentRouter pattern coverage
- [ ] Verify circuit breaker resets correctly after model recovers
- [ ] Confirm `PredictiveQaWorker` result is discarded (not shown) if user changes topic
