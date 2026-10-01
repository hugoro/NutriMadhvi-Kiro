package com.example.data.chat

import com.example.data.local.BodyCompositionSnapshotEntity
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
        language: String
    ): String {
        val langName = when {
            language.startsWith("pt", ignoreCase = true) || language.isBlank() -> "português"
            language.startsWith("gu", ignoreCase = true) -> " gujarati"
            else -> "português"
        }
        val date = DATE_FMT.format(Date(snapshot.capturedAtMs))
        val sb = StringBuilder()

        sb.appendLine("[AVALIAÇÃO DE PERFORMANCE — $date]")
        sb.appendLine("Utilizador: $userName")
        sb.appendLine()

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
        sb.appendLine("""
Com base nos rácios de silhueta e no histórico de composição corporal acima, escreve um relatório de coach conciso (máximo 180 palavras) em $langName que:
1. Avalia a distribuição corporal atual — onde está a massa/gordura concentrada com base nos rácios
2. Relaciona com a tendência de composição corporal das últimas semanas (se disponível)
3. Dá 2–3 recomendações práticas e específicas de treino e nutrição baseadas nos dados

Usa linguagem directa, sem elogios vazios, sem introdução genérica. Começa imediatamente com a análise.
        """.trimIndent())

        return sb.toString()
    }
}
