package com.example.data.chat

import com.example.data.local.BodyCompositionSnapshotEntity
import com.example.data.local.PerformanceGoalEntity
import com.example.data.local.PerformanceSnapshotEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the LLM prompt for the async performance coach report.
 *
 * The report synthesises silhouette ratios (Farina et al. 2022 validated body
 * segment analysis) with the existing body composition history from Samsung
 * Health / VitaDock. Output is ~150–200 words in the user's language.
 */
object PerformanceCoachBuilder {

    private val DATE_FMT = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    fun buildPrompt(
        snapshot: PerformanceSnapshotEntity,
        bodyCompHistory: List<BodyCompositionSnapshotEntity>,
        userName: String,
        language: String,
        goal: PerformanceGoalEntity? = null,
    ): String {
        val langName = when {
            language.isBlank() || language.startsWith("pt", ignoreCase = true) -> "português (Portugal)"
            language.startsWith("en", ignoreCase = true) -> "English"
            language.startsWith("gu", ignoreCase = true) -> "Gujarati"
            // QA 2026-10 (second pass): never silently force Portuguese on a
            // non-PT user — pass the declared language code through.
            else -> language
        }
        val date = DATE_FMT.format(Date(snapshot.capturedAtMs))
        val sb = StringBuilder()

        sb.appendLine("[AVALIAÇÃO DE PERFORMANCE — $date]")
        sb.appendLine("Utilizador: $userName")
        sb.appendLine()

        // User goal — injected before silhouette data so the LLM frames analysis against it
        if (goal?.goalText != null) {
            sb.appendLine("## Objetivo declarado pelo utilizador")
            sb.appendLine(goal.goalText)
            sb.appendLine()
        }

        // Silhouette ratios — only emit non-null rows
        val hasFrontal = listOf(
            snapshot.frontalShoulderRatio, snapshot.frontalWaistRatio,
            snapshot.frontalHipRatio, snapshot.frontalThighRatio
        ).any { it != null }
        val hasLateral = listOf(
            snapshot.lateralChestRatio, snapshot.lateralBellyRatio, snapshot.lateralThighRatio
        ).any { it != null }

        if (hasFrontal) {
            sb.appendLine("## Rácios de silhueta (vista frontal)")
            snapshot.frontalShoulderRatio?.let { sb.appendLine("- Largura ombros/altura: ${"%.3f".format(it)}") }
            snapshot.frontalWaistRatio?.let    { sb.appendLine("- Largura cintura/altura: ${"%.3f".format(it)}") }
            snapshot.frontalHipRatio?.let      { sb.appendLine("- Largura ancas/altura: ${"%.3f".format(it)}") }
            snapshot.frontalThighRatio?.let    { sb.appendLine("- Largura coxas/altura: ${"%.3f".format(it)}") }
            sb.appendLine()
        }
        if (hasLateral) {
            sb.appendLine("## Rácios de silhueta (vista lateral)")
            snapshot.lateralChestRatio?.let   { sb.appendLine("- Profundidade peito/altura: ${"%.3f".format(it)}") }
            snapshot.lateralBellyRatio?.let   { sb.appendLine("- Profundidade barriga/altura: ${"%.3f".format(it)}") }
            snapshot.lateralThighRatio?.let   { sb.appendLine("- Profundidade coxa/altura: ${"%.3f".format(it)}") }
            sb.appendLine()
        }

        if (bodyCompHistory.isNotEmpty()) {
            sb.appendLine("## Histórico de composição corporal (últimas medições)")
            sb.appendLine("Data       | Peso (kg) | Gordura% | Músculo (kg) | BMI")
            sb.appendLine("-----------|-----------|----------|-------------|------")
            bodyCompHistory.take(10).forEach { bc ->
                val d = DATE_FMT.format(Date(bc.sourceTimestampMs ?: bc.capturedAtMs))
                val fat  = bc.bodyFatPct?.let { "${"%.1f".format(it)}%" } ?: "—"
                val musc = bc.muscleMassKg?.let { "${"%.1f".format(it)}" } ?: "—"
                val bmi  = bc.bmiValue?.let { "${"%.1f".format(it)}" } ?: "—"
                sb.appendLine("$d | ${"%.1f".format(bc.weightKg)} | $fat | $musc | $bmi")
            }
            sb.appendLine()
        }

        // Prompt instruction
        sb.appendLine("## Instrução")
        val goalInstruction = if (goal?.goalText != null) {
            "\n4. Relaciona a análise com o objetivo declarado pelo utilizador e ajusta as recomendações para o atingir\n5. No final, inclui obrigatoriamente um bloco com os rácios-alvo de silhueta recomendados para este objetivo no formato:\n[COACH_TARGETS] {\"shoulder\":0.00,\"waist\":0.00,\"hip\":0.00,\"thigh\":0.00,\"chest\":0.00,\"belly\":0.00,\"lateralThigh\":0.00} [/COACH_TARGETS]"
        } else {
            ""
        }
        sb.appendLine("""
Com base nos rácios de silhueta e no histórico de composição corporal acima, escreve um relatório de coach conciso (máximo 200 palavras) em $langName que:
1. Avalia a distribuição corporal atual — onde está a massa/gordura concentrada com base nos rácios
2. Relaciona com a tendência de composição corporal das últimas semanas (se disponível)
3. Dá 2–3 recomendações práticas e específicas de treino e nutrição baseadas nos dados$goalInstruction

Usa linguagem directa, sem elogios vazios, sem introdução genérica. Começa imediatamente com a análise.
        """.trimIndent())

        return sb.toString()
    }

    /**
     * Builds a prompt to derive [COACH_TARGETS] JSON from a free-text goal.
     *
     * Crucially this now feeds the LLM BOTH the measured silhouette ratios AND
     * the body-composition history (fat% / muscle kg) so target ratios are
     * informed by tissue type, not raw width. A silhouette width alone cannot
     * tell muscle from fat; recommending "reduce" a zone that is predominantly
     * muscle would wrongly tell the user to lose muscle. The instruction below
     * forbids that: zones are only targeted downward when composition data
     * points to fat, never where the signal is likely muscle.
     */
    fun buildTargetsPrompt(
        goalText: String,
        gender: String,
        language: String,
        snapshot: PerformanceSnapshotEntity? = null,
        bodyCompHistory: List<BodyCompositionSnapshotEntity> = emptyList(),
    ): String {
        val genderLabel = if (gender.equals("Female", ignoreCase = true)) "Feminino" else "Masculino"
        val sb = StringBuilder()
        sb.appendLine("Género: $genderLabel")
        sb.appendLine("Objetivo do utilizador: $goalText")
        sb.appendLine()

        // Measured silhouette ratios (context only — NOT values to copy).
        // Ratios outside the physiologically-plausible range are pose-estimation
        // noise; we flag them so the LLM never echoes garbage back as a target.
        if (snapshot != null) {
            fun fmt(label: String, v: Float?, lo: Float, hi: Float): String? {
                if (v == null) return null
                val flag = if (v < lo || v > hi) " [NÃO FIÁVEL — medição fora do normal, ignora como alvo]" else ""
                return "$label ${"%.3f".format(v)}$flag"
            }
            val rows = buildList {
                fmt("ombros",       snapshot.frontalShoulderRatio, 0.18f, 0.55f)?.let(::add)
                fmt("cintura",      snapshot.frontalWaistRatio,    0.12f, 0.45f)?.let(::add)
                fmt("ancas",        snapshot.frontalHipRatio,      0.15f, 0.50f)?.let(::add)
                fmt("coxas",        snapshot.frontalThighRatio,    0.08f, 0.30f)?.let(::add)
                fmt("peito(lat)",   snapshot.lateralChestRatio,    0.12f, 0.40f)?.let(::add)
                fmt("barriga(lat)", snapshot.lateralBellyRatio,    0.10f, 0.40f)?.let(::add)
                fmt("coxa(lat)",    snapshot.lateralThighRatio,    0.08f, 0.30f)?.let(::add)
            }
            if (rows.isNotEmpty()) {
                sb.appendLine("Rácios de silhueta ATUAIS (largura ou profundidade / altura — contexto, não copiar): ${rows.joinToString(", ")}")
            }
        }

        // Body composition — the signal that distinguishes fat from muscle.
        val latest = bodyCompHistory.firstOrNull()
        if (latest != null) {
            val fat  = latest.bodyFatPct?.let { "${"%.1f".format(it)}%" } ?: "—"
            val musc = latest.muscleMassKg?.let { "${"%.1f".format(it)} kg" } ?: "—"
            sb.appendLine("Composição corporal atual: gordura $fat, massa muscular $musc, peso ${"%.1f".format(latest.weightKg)} kg")
            if (bodyCompHistory.size >= 2) {
                val prev = bodyCompHistory[1]
                val dFat = if (latest.bodyFatPct != null && prev.bodyFatPct != null)
                    "%+.1f".format(latest.bodyFatPct!! - prev.bodyFatPct!!) else "?"
                val dMus = if (latest.muscleMassKg != null && prev.muscleMassKg != null)
                    "%+.1f".format(latest.muscleMassKg!! - prev.muscleMassKg!!) else "?"
                sb.appendLine("Tendência recente: gordura ${dFat}pp, músculo ${dMus} kg")
            }
        }
        sb.appendLine()

        sb.appendLine("""
Com base no objetivo, género e composição corporal, define os rácios de silhueta-ALVO
(largura de zona / altura corporal) saudáveis e realistas para atingir o objetivo.

REGRAS CRÍTICAS (não violar):
- Cada alvo DEVE estar DENTRO da gama de referência de literatura abaixo. NUNCA devolvas um valor
  fora dessa gama, mesmo que o rácio atual esteja fora — os rácios atuais são apenas contexto.
- NUNCA copies um rácio atual marcado [NÃO FIÁVEL]: calcula o alvo a partir da gama de referência.
- Um rácio de silhueta mede LARGURA/ESPESSURA, não distingue músculo de gordura.
- Só define um alvo ABAIXO do atual (reduzir) numa zona se a composição corporal indicar GORDURA a reduzir
  (gordura% elevada, ou zona de acumulação de gordura: cintura/barriga/ancas).
- NUNCA mandes reduzir uma zona predominantemente MUSCULAR (coxas/ombros/peito com boa massa muscular
  e gordura controlada). Para essas, mantém ou aumenta ligeiramente.
- Se faltar composição corporal, sê conservador e usa o ponto médio saudável da gama.

Gamas de referência (literatura desportiva — o alvo tem de cair aqui):
  ombros 0.33–0.42 · cintura 0.20–0.27 · ancas 0.28–0.34 · coxas 0.18–0.24
  peito 0.19–0.26 · barriga 0.16–0.22 · coxa lateral 0.17–0.22

Responde APENAS com o bloco JSON abaixo (sem texto adicional, sem comentários):
[COACH_TARGETS] {"shoulder":0.00,"waist":0.00,"hip":0.00,"thigh":0.00,"chest":0.00,"belly":0.00,"lateralThigh":0.00} [/COACH_TARGETS]
        """.trimIndent())

        return sb.toString()
    }

    // Clamp any coach-returned target into the physiological reference range so a
    // stray LLM value can never drive the silhouette. Mirrors the ranges in the
    // prompt. Applied in ChatViewModel before persisting.
    private val TARGET_RANGE = mapOf(
        "shoulder" to (0.33f to 0.42f),
        "waist"    to (0.20f to 0.27f),
        "hip"      to (0.28f to 0.34f),
        "thigh"    to (0.18f to 0.24f),
        "chest"    to (0.19f to 0.26f),
        "belly"    to (0.16f to 0.22f),
        "lateralThigh" to (0.17f to 0.22f),
    )

    /** Clamps each key in a COACH_TARGETS JSON object to its reference range.
     *  Returns the sanitised JSON string (keys outside the map pass through). */
    fun clampTargetsJson(json: String): String {
        return try {
            val obj = org.json.JSONObject(json)
            TARGET_RANGE.forEach { (key, range) ->
                if (obj.has(key) && !obj.isNull(key)) {
                    val v = obj.optDouble(key).toFloat()
                    obj.put(key, v.coerceIn(range.first, range.second).toDouble())
                }
            }
            obj.toString()
        } catch (_: Exception) { json }
    }
}
