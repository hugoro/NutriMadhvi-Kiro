package com.example.data.performance

import com.example.data.local.BodyCompositionSnapshotEntity
import com.example.data.local.PerformanceSnapshotEntity

/**
 * Prompt builders for the v39 body-plan LLM enrichment (tactics + checkpoint
 * report). One call does both, mirroring the existing
 * [com.example.data.chat.PerformanceCoachBuilder] pattern (report +
 * [COACH_TARGETS] block): plain text report + one strict `[TACTICS]` JSON
 * block.
 *
 * Privacy property (inherited from the coach report): only NUMERIC ratios,
 * composition numbers and the stated goal text are sent. No photos, no
 * image paths — silhouette images stay app-private and on-device.
 */
object BodyPlanPromptBuilder {

    private const val TACTICS_OPEN = "[TACTICS]"
    private const val TACTICS_CLOSE = "[/TACTICS]"

    /**
     * Prompt for the checkpoint enrichment call (single cheap LLM call:
     * checkpoint report text + one strict `[TACTICS]` JSON block — mirrors
     * the existing [com.example.data.chat.PerformanceCoachBuilder] pattern
     * of report + structured block in one response).
     *
     * The deterministic core (phase, priorities) is computed locally first
     * and passed in, so the LLM only writes tactics consistent with the
     * safety-gated plan — it never decides the direction of a zone itself.
     * When [previousPhase] is provided the report compares this checkpoint
     * with the previous one.
     */
    fun buildTacticsPrompt(
        phase: BodyPlanEngine.BodyPhase?,
        priorities: List<BodyPlanEngine.ZonePriority>,
        goalText: String?,
        latestComp: BodyCompositionSnapshotEntity?,
        name: String,
        lang: String,
        previousPhase: String? = null,
        previousRatios: String? = null,
        deltaSummary: String? = null,
    ): String {
        val comp = latestComp?.let {
            "Peso ${it.weightKg} kg" +
                it.bodyFatPct?.let { f -> ", gordura ${f}% " } +
                it.muscleMassKg?.let { m -> ", massa muscular ${m} kg" } +
                "."
        } ?: "Sem dados de composição corporal (balança) — usa faixas conservadoras."

        val prioText = priorities.takeIf { it.isNotEmpty() }?.joinToString("; ") { p ->
            val dir = when (p.direction) {
                BodyPlanEngine.Direction.REDUCE -> "REDUZIR"
                BodyPlanEngine.Direction.GROW -> "AUMENTAR"
                BodyPlanEngine.Direction.HOLD -> "MANUTENÇÃO"
            }
            "${p.zone}: $dir (gap ${p.gapPp}pp, tecido ${p.tissue.name.lowercase()})"
        } ?: "Sem prioridades (sem objetivos de silhueta definidos)."

        val progress = if (previousPhase == null) {
            "Este é o PRIMEIRO checkpoint — descreve o ponto de partida do plano."
        } else {
            "Fase do checkpoint anterior: $previousPhase.\n" +
                "Silhueta anterior: ${previousRatios ?: "sem dados"}.\n" +
                "Delta (calculado localmente): ${deltaSummary ?: "—"}."
        }

        return """
            Tu és um coach de composição corporal conservador. Escreve em $lang.

            Utilizador: $name
            Fase atual (calculada localmente, não a alteres): ${phase?.name ?: "desconhecida"}
            Objetivo declarado: ${goalText ?: "(nenhum declarado)"}
            Progresso:
            $progress
            Prioridades (calculadas localmente e protegidas por regras de segurança — cumpre-as):
            $prioText
            Composição corporal: $comp

            Escreve NO MÁXIMO 120 palavras de contexto breve: 1 frase sobre a direção do
            progresso, 1–2 frases sobre o que está a funcionar / ajustar, e 1 frase de
            encorajamento concreta (NUNCA genérica). Depois OBRIGATORIAMENTE um bloco
            [TACTICS] com no máximo 6 táticas concretas e mensuráveis, em JSON válido:
            [TACTICS] [{"category":"protein","text":"...","target":"...","windowDays":28}, ...] [/TACTICS]

            Regras das táticas:
            - "category" deve ser um de: protein, strength, steps, sleep, calories, zone
            - "text": 1 frase imperativa, concreta e acionável (máx. 140 caracteres)
            - "target": o número/valores de referência (ex. "120 g/dia", "2–3 sessões/semana")
            - "windowDays": inteiro 7–120
            - Táticas de zona: uma por prioridade, com direção REDUZIR/AUMENTAR e
              OBRIGATORIAMENTE o campo "zone" com a chave da zona
              (shoulder, waist, hip, thigh, chest, belly, lateralThigh)
            - NUNCA recomendar reduzir uma zona muscular (ombro, peito, quadril, coxa)
            - Faixas conservadoras; isto NÃO é um aconselhamento médico
            - Sem links, sem vídeos, sem listas de exercícios específicas de YouTube
            - Texto simples, sem markdown, sem blocos além de [TACTICS]
        """.trimIndent()
    }

    /** Prompt for a zone-ratios one-liner (used as "previous silhouette"
     *  context in the checkpoint enrichment prompt). */
    fun ratiosLine(s: PerformanceSnapshotEntity?): String =
        if (s == null) "sem dados"
        else listOfNotNull(
            s.frontalShoulderRatio?.let { "ombro ${it * 100}%" },
            s.frontalWaistRatio?.let { "cintura ${it * 100}%" },
            s.frontalHipRatio?.let { "quadril ${it * 100}%" },
            s.frontalThighRatio?.let { "coxa ${it * 100}%" },
            s.lateralChestRatio?.let { "peito ${it * 100}%" },
            s.lateralBellyRatio?.let { "abdómen ${it * 100}%" },
            s.lateralThighRatio?.let { "coxa (lateral) ${it * 100}%" },
        ).joinToString(", ").ifEmpty { "sem dados" }
}
