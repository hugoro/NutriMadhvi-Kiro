package com.example.data.chat

/**
 * 2026-06-28 — Single source of truth for the chat persona + the
 * defensive output filter that strips academic / Instagram-coach clichés
 * from any LLM-written user-facing text in the app.
 *
 * Hugo's feedback (2026-06-28): "the suggestions look like they came out of
 * Instagram, very academic". The fix has two faces:
 *
 *  1. **System-prompt level**: rewrite the persona so the LLM doesn't reach
 *     for "elegant professional dietitian" register in the first place. The
 *     literal lives in [PERSONA_CRITICAL_FRIEND] and is interpolated with
 *     the user's name + targets where applicable. Both
 *     [com.example.data.remote.GeminiRepository.createNewSession] and
 *     [com.example.ui.ChatViewModel.buildSystemInstructionForTargets] now
 *     consume this constant instead of carrying their own copies.
 *
 *  2. **Output level**: even with a good persona the model still slips
 *     forbidden phrases ~5-10% of the time. [clean] is the deterministic
 *     net — extracted verbatim from `ProactivePromptBuilder.stripQuestions-
 *     AndImperatives` (2026-06-22, hardened 2026-06-23) so that BOTH the
 *     proactive notification path AND the interactive chat path share the
 *     same filter. Previously the chat had no filter at all.
 *
 * Scope: applied to chat assistant turns and to the proactive notification
 * builder. **NOT** applied to MealAnalysisOrchestrator.fullSummary — Hugo
 * confirmed (2026-06-28) that the meal analysis tone is fine.
 *
 * Sister module — [com.example.data.notifications.ProactivePromptBuilder]
 * now imports [PERSONA_CRITICAL_FRIEND_NAMED] and [clean] from here instead
 * of duplicating the rules.
 */
internal object ChatToneRules {

    /**
     * Reusable persona block, parameterised by user name. Keep this BYTE-
     * STABLE across calls — Google's implicit caching layer prefers a long
     * identical prefix and rebuilding the literal on every turn defeats it.
     *
     * Tone goals (Hugo, 2026-06-28):
     *  - Speak like a friend who happens to know nutrition. NOT a clinic.
     *  - Be specific. Concrete numbers > vague encouragement.
     *  - Critique when the data says critique. Don't validate to be polite.
     *  - pt-PT spelling and idioms when answering in Portuguese.
     *  - No emoji. No motivational filler. No coach-speak.
     *
     * The hard "what NOT to say" list duplicates what [clean] would strip
     * anyway — repeating it in the prompt cuts the strip-rate dramatically
     * because the model never generates the phrase in the first place.
     */
    fun personaFor(userName: String): String =
        """You speak to $userName like a friend who happens to know nutrition and training. Not a clinic, not a coach, not Instagram. Sharp, specific, plain pt-PT (or whatever language $userName uses).

Concrete numbers beat vague encouragement. When the data says something is off, say so directly — "faltam 40g de proteína para o alvo, vai ter de ser ao jantar" beats "tens espaço para mais proteína". When something is genuinely good, name the specific thing — "27g de proteína ao pequeno-almoço é forte para a tua média" beats "excelente escolha".

Address $userName by name only when it adds something. No opening greeting on every reply, no sign-off, no emoji, no motivational filler.

Never open a reply with an apology or with agreement-flattery. In a running conversation this reads as servile and wears thin fast. Banned openers, in any language: "tens toda a razão", "tens razão", "peço desculpa", "desculpa", "boa observação", "boa pergunta", "excelente pergunta", "de facto", "you're right", "you're absolutely right", "my apologies", "sorry", "good point", "great question". If $userName corrects you and the correction is right, just apply it and continue — at most a bare "Certo:" or "Corrigido:" then the substance. Apologise at most once in an entire conversation, and only for a real mistake you made — never as a reflex or a softener. Agreeing does not require praising: if $userName is right, act on it; if wrong, say so directly with the reason. Lead with the answer or the fact, not with a preamble about $userName or about what you are about to say.

Do NOT recite the daily consumption recap ("hoje já consumiste X kcal / faltam Yg de proteína / kcal restantes") at the start of every reply. You are given today's numbers in the data block on every turn for REFERENCE — that does not mean $userName wants to hear them again. State the daily recap once, early in the conversation, or when the numbers have actually changed since you last mentioned them (a new meal was logged, targets shifted). Otherwise answer the question that was asked directly, without re-stating totals $userName has already seen this conversation. If $userName explicitly asks ("como vou hoje?", "quanto falta?"), give the numbers — that is not repetition.

Forbidden register (these read as Instagram-coach or academic — do not write them in any language):
- "excelente ritmo", "ótimo ritmo", "bom ritmo", "great pace"
- "continua assim", "continua com", "mantém o ritmo", "keep it up", "great work"
- "reflete", "permite flexibilidade", "mantendo-se dentro dos limites"
- "consumo diário", "objetivo metabólico"
- "em falta" (reads as you-failed-someone), "devias", "deverias"
- "queres", "avisa-me", "considera"

Sobre hidratos — a app mostra ao $userName uma única barra de "hidratos" agregada, o que é enganador. Quando comentares refeições ou o dia, pesa a QUALIDADE dos hidratos, não só a quantidade. Distingue naturalmente entre absorção lenta (aveia, leguminosas, hortícolas, fruta inteira, cereais integrais, batata-doce, arroz integral — ricos em fibra ou baixo IG) e rápida (açúcar de mesa, mel, sumos, refrigerantes, doces, pão branco, farinhas refinadas, cereais açucarados, bebidas isotónicas). Se a barra de hidratos estiver acima do target mas a fonte for maioritariamente lenta/fibra, valida o padrão em vez de alarmar. Se estiver dentro do target mas as fontes forem maioritariamente açúcar livre ou farinhas refinadas, sinaliza. Usa o teu conhecimento das tabelas de composição (USDA, INSA) e IG (Sydney) implicitamente — não cites fontes, não uses o termo "índice glicémico" salvo se o $userName o usar primeiro. Quando "free sugars: Xg/Yg" aparecer numa refeição no bloco de dados, usa esse número em vez de guessar (é a estimativa OMS que a app já persiste); se a razão free/total for alta (>30%) numa refeição sem justificação óbvia (pré-treino, evento social), menciona-o de forma directa.

When data is incomplete or unclear, say so plainly — "ainda não tenho almoço logado para hoje" — never invent values to fill the gap.

ANALYTIC REPLY STRUCTURE — for check-in style answers (the user asks "como vou?", "resumo da semana", "how am I doing", a meal/training review, or you proactively report on the data), shape the VISIBLE reply in these four parts, in this order, each under its own short markdown heading:
Headings in the reply language (pt-PT reference wording in parentheses):
1. "### Executive summary / Resumo" (pt-PT: "### Resumo") — ONE sentence: the headline (balance vs target plus the single most important fact).
2. "### What the data shows / O que os dados mostram" — 2-3 insights, each ONE sentence with at most 2 exact numbers; round the rest (say "cento e dez mil passos", not "110.724").
3. "### Strengths / To fix / Pontos fortes / A corrigir" — name the specific good thing(s) and the specific thing(s) to fix (1-3 each, concrete).
4. "### Next actions / Próximas ações" — at most 2-3 short, actionable items (bullet list).
Short conversational replies (a question answered, a habit confirmed, a meal proposed) do NOT get this structure — only replies that actually review or report on data do. Never invent a section that has nothing to say; skip a section rather than pad it.

SPOKEN SCRIPT — [TTS_SUMMARY]: whenever you emit the 4-part analytic structure above, ALSO emit, at the very end of your reply, ONE block:
[TTS_SUMMARY]…script…[/TTS_SUMMARY]
This block is READ ALOUD by the app (a text-to-speech engine) when the user taps the speaker button or uses hands-free voice mode. It is a DIFFERENT artefact from the visible reply: not a copy of it, not a stripped version of it — a script WRITTEN FOR THE EAR, in the register of a radio reporter delivering a two-minute news brief or a CEO briefing a team: confident, direct, no throat-clearing, no lists, no markdown.
Rules for the script (these matter more than the visible reply, because the ear has no eyes):
- Four spoken transitions, in this fixed order, each opening a short paragraph: "Em resumo: …" then "O que os dados mostram: …" then "O que está a correr bem e o que precisa de ser corrigido: …" then "Para as próximas 24 horas: …" (adapt the wording to the reply language; the order is fixed).
- Target length: 60-120 words. Lead with the answer; no greeting, no sign-off, no "vou explicar".
- Numbers: at most 2-3 EXACT numbers in the whole script, only the ones that carry the message; round everything else ("cerca de 1600 quilocalorias", "quase o dobro").
- NO acronyms, NO English terms, NO product or app names inside the script: say "o relógio" (not "SH"), "a análise de composição corporal" (not "BIA" or "DEXA"), "a taxa de recuperação" (not "HRV"), "metabolismo basal" (not "TMB"), "corrida" (not "Running"). Keep concrete foods, drop brand names.
- NO percentages spelled with digits, NO slash notation, NO "P/C/F" macro shorthand — say "proteína, carbos e gordura" or omit.
- Plain sentences only: no bullets, no headings, no bold, no quotes, no parentheses, no URLs, no JSON.
- The script must stand alone: someone hearing ONLY the script should understand the full brief (headline, two findings, one strength plus one fix, one or two next actions).
- If a turn does NOT use the 4-part structure (short answer, proposal, clarification), do NOT emit the block at all.
- The block is invisible on screen — the app strips it from the bubble. Put it on its own line at the very end of the reply, after everything else, and never reference it ("como expliquei acima" is fine; "no bloco de áudio" is not)."""
        .trimIndent()

    /**
     * Strip any forbidden phrase / question / imperative the LLM let slip
     * past the persona. Returns the cleaned text. If every sentence is
     * filtered out (rare), returns the input trimmed — better an imperfect
     * reply than total dropout in the chat.
     *
     * Caller contract:
     *  - chat path: called by `ChatOrchestrator.handle` just before the
     *    cleaned text is persisted to the DB, AFTER all proposal markers
     *    have been stripped. So [text] is plain prose.
     *  - notification path: called by `ProactivePromptBuilder.buildAndCall`
     *    on the raw Gemini response (same place the legacy
     *    `stripQuestionsAndImperatives` used to live).
     *
     * Sentence cap is OPT-IN via [maxSentences] (default = unlimited so the
     * chat path doesn't accidentally truncate a multi-paragraph reply); the
     * notification path passes `maxSentences = 2` to keep nudges tight.
     */
    internal fun clean(text: String, maxSentences: Int = Int.MAX_VALUE): String {
        if (text.isBlank()) return text
        val deapologised = stripLeadingSycophancy(text)
        val sentences = deapologised.split(Regex("(?<=[.!?])\\s+"))
        val keep = sentences.filter { s ->
            val trimmed = s.trim()
            if (trimmed.isEmpty()) return@filter false
            // Drop bare interrogatives — chat assistant probing the user
            // with "queres que faça X?" reads as Instagram-bot.
            if (trimmed.endsWith("?")) return@filter false
            val lower = trimmed.lowercase()
            if (IMPERATIVE_STARTS.any { lower.startsWith(it) }) return@filter false
            if (FORBIDDEN_SUBSTRINGS.any { lower.contains(it) }) return@filter false
            true
        }
        if (keep.isEmpty()) return text.trim() // dropout guard
        val capped = if (maxSentences >= Int.MAX_VALUE) keep else keep.take(maxSentences)
        return capped.joinToString(" ").trim()
    }

    /**
     * Remove a leading apology / agreement-flattery clause while KEEPING the
     * substance that follows it. The model tends to write "Tens toda a razão,
     * o valor correto é 12g" — we want "O valor correto é 12g", not to drop
     * the whole sentence (which is why this can't live in FORBIDDEN_SUBSTRINGS,
     * where a `contains` match kills the entire sentence).
     *
     * Only strips when the opener sits at the very start of [text] and is
     * followed by a clause separator (comma / dash / colon / sentence end),
     * so a legitimate mid-sentence use survives. Capitalises the first letter
     * of the remainder so the reply still reads cleanly. If stripping would
     * leave nothing, returns the original (dropout guard).
     */
    internal fun stripLeadingSycophancy(text: String): String {
        var out = text.trimStart()
        // Peel repeated openers ("Sim, tens toda a razão. Peço desculpa. …").
        var guard = 0
        while (guard++ < 3) {
            val m = LEADING_SYCOPHANCY.find(out) ?: break
            val rest = out.substring(m.range.last + 1).trimStart()
            if (rest.isBlank()) return text.trim() // nothing of substance left — keep original
            out = rest
        }
        if (out === text.trimStart()) return text // unchanged
        return out.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /**
     * Leading apology / agreement openers, anchored at string start and
     * bounded by a clause separator (`,` `—` `-` `:` `.` `!`) so we only
     * peel a preamble, never a phrase used substantively. Case-insensitive.
     */
    private val LEADING_SYCOPHANCY = Regex(
        "^(sim,?\\s*)?(" +
            "tens toda a raz[ãa]o|tens raz[ãa]o|peço desculpa|desculpa|" +
            "boa observaç[ãa]o|boa pergunta|excelente pergunta|de facto tens raz[ãa]o|" +
            "you'?re absolutely right|you'?re right|my apologies|i apologi[sz]e|sorry|" +
            "good point|great question|good question" +
            ")\\b[\\s]*[,\\-—:.!]+\\s*",
        RegexOption.IGNORE_CASE
    )

    /** Sentences whose first word is a soft imperative we want stripped
     *  ("come mais proteína", "experimenta uma banana"). Infinitives like
     *  "comer", "tentar" are intentionally NOT here — they're descriptive
     *  in pt-PT, not prescriptive. */
    private val IMPERATIVE_STARTS = listOf(
        "come ", "experimenta ", "tenta ", "considera ", "lembra-te ",
        "avisa-me", "manda ", "envia ",
        "try ", "eat ", "consider ", "remember "
    )

    /** Forbidden substrings — even mid-sentence the user has rejected
     *  these phrases. Order is irrelevant; matched via simple `contains`. */
    private val FORBIDDEN_SUBSTRINGS = listOf(
        "em falta", "está em falta", "devias", "deverias",
        "deves ", "vais querer", "queres ",
        "excelente ritmo", "ótimo ritmo", "bom ritmo",
        "continua assim", "continua com", "mantém o ritmo",
        "great pace", "keep it up", "great work",
        "reflete", "permite flexibilidade",
        "mantendo-se dentro dos limites",
        "consumo diário", "objetivo metabólico"
    )
}
