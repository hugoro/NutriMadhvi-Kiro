package com.example.data.ai.provider

/**
 * Stable `provider:model` identity (plan §3.1, §12).
 *
 * This is the single string that keys the circuit breaker, appears in debug
 * logs (§20) and is used everywhere a provider needs to be named. Keeping it
 * a value type — rather than two loose strings threaded around — means the
 * breaker, the registry and the router all agree on exactly one spelling of
 * `groq:qwen3.8-27b`.
 *
 * [provider] and [model] are kept separate so a model can be swapped without
 * touching the provider adapter (plan §3.4: `groq:qwen3.8-27b` must be
 * replaceable by `groq:<new-model>` without changing orchestration).
 */
@JvmInline
value class ProviderModelId private constructor(val value: String) {

    val provider: String get() = value.substringBefore(':')
    val model: String get() = value.substringAfter(':')

    override fun toString(): String = value

    companion object {
        /** Build from parts, e.g. `of("groq", "qwen3.8-27b")`. The provider
         *  token must not itself contain ':' so [provider]/[model] round-trip. */
        fun of(provider: String, model: String): ProviderModelId {
            require(provider.isNotBlank()) { "provider must not be blank" }
            require(!provider.contains(':')) { "provider token must not contain ':' — got '$provider'" }
            require(model.isNotBlank()) { "model must not be blank" }
            return ProviderModelId("$provider:$model")
        }

        /** Parse a canonical `provider:model` string (e.g. from configuration
         *  or a log line). Requires exactly one ':'. */
        fun parse(raw: String): ProviderModelId {
            val idx = raw.indexOf(':')
            require(idx > 0 && idx < raw.length - 1) {
                "expected 'provider:model', got '$raw'"
            }
            return of(raw.substring(0, idx), raw.substring(idx + 1))
        }
    }
}
