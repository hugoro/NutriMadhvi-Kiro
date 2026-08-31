"""
Final root cause confirmation for TTS 'cerquilha' bug.
Key findings and the definitive bug locations.
"""
import re

def strip_markdown(s: str) -> str:
    """Exact translation of TtsController.stripMarkdown()"""
    s = re.sub(r'(?s)```.*?```', ' ', s)
    s = re.sub(r'`([^`]+)`', r'\1', s)
    s = re.sub(r'!\[([^\]]*)\]\([^)]*\)', r'\1', s)
    s = re.sub(r'\[([^\]]+)\]\([^)]*\)', r'\1', s)
    s = re.sub(r'\*\*([^*]+)\*\*', r'\1', s)
    s = re.sub(r'__([^_]+)__', r'\1', s)
    s = re.sub(r'(?<![*])\*([^*\n]+)\*(?![*])', r'\1', s)
    s = re.sub(r'(?<![_])_([^_\n]+)_(?![_])', r'\1', s)
    s = re.sub(r'(?m)^#{1,6}\s*', '', s)
    s = re.sub(r'(?m)^\s*[-*_]{3,}\s*$', '\n', s)
    s = re.sub(r'(?m)^\s*[%=]{3,}\s*$', '\n', s)
    s = re.sub(r'(?m)^\s*[\[{][^\n]*?[}\]]\s*$', '\n', s)
    s = re.sub(r'(?m)^\s*[-*+]\s+', '', s)
    s = re.sub(r'(?m)^\s*\d+\.\s+', '', s)
    s = re.sub(r'\n{3,}', '\n\n', s)
    return s.strip()

print("=" * 70)
print("CONFIRMED ROOT CAUSE #1: Indented ### headers survive stripMarkdown")
print("=" * 70)
# The regex is: (?m)^#{1,6}\s*
# This requires # to be at the VERY start of a line (^).
# But Gemini responses streamed in chunks sometimes have indentation or
# leading whitespace before the # character.
# The regex (?m)^\s*[-*+]\s+ strips indented bullet points but
# the heading regex (?m)^#{1,6}\s* does NOT handle leading whitespace!

indented = "\n  ### Plano alimentar\nAqui está:"
print(f"Input:  {repr(indented)}")
result = strip_markdown(indented)
print(f"Output: {repr(result)}")
print(f"Has #: {'YES - BUG!' if '#' in result else 'No'}")
print()

print("=" * 70)
print("CONFIRMED ROOT CAUSE #2: The _cleanedMessageText guard condition")  
print("=" * 70)
print("""
In ChatViewModel.ingestAssistantMessage() (lines 1089-1090):

    if (parsed.cleanedText != rawText) {
        _cleanedMessageText.update { it + (messageId to parsed.cleanedText) }
    }

This ONLY stores the cleanedText if the parser actually changed something
(stripped a proposal marker). For a plain conversational reply with no
[HABIT_PROPOSAL], [LOG_MEAL_PROPOSAL], etc., parsed.cleanedText == rawText,
so _cleanedMessageText[id] is never populated.

When speakMessage() is called:
    val readable = _cleanedMessageText.value[msg.id] ?: msg.text

...it falls back to msg.text which is the RAW finalReplyText stored in DB.
The raw text contains ### headers. These are fed to TtsController.speak()
which calls stripMarkdown(). For MOST cases this works, BUT...
""")

print("=" * 70)
print("CONFIRMED ROOT CAUSE #3: The actual failing pattern")
print("=" * 70)
print("""
The PT-PT TTS engine (Google TTS) pronounces:
  '#' → 'cerquilha'  (the Portuguese name for the hash/pound symbol)
  
When does # survive to the TTS engine?
  a) When it appears INLINE in text (not at line start)
  b) When a ### heading has leading whitespace before it

Evidence from Gemini's typical responses (pt-PT):
Gemini sometimes emits responses like:
  "Podes ver o progresso na secção #3 acima."
  "Tens #2 refeições abaixo do target."
  or after list items: "  ## Notas importantes"

The regex ^#{1,6}\\s* only catches # at the exact start of a line.
Any # elsewhere passes through unstripped.

Additionally, there's a secondary path for auto-speak (Voice Mode):
  maybeAutoSpeakDone() uses event.cleanedText as fallback
  event.cleanedText = sievedClean from ChatOrchestrator.handle()
  sievedClean = ChatToneRules.clean(ActionProposalParser.parse(finalReplyText).cleanedText)
  
  ChatToneRules.clean() splits on sentence-ending punctuation [.!?]
  and filters sentences — it does NOT strip markdown.
  ActionProposalParser only strips [PROPOSAL_MARKERS].
  
  So event.cleanedText still has all ### intact and those WOULD be
  stripped by stripMarkdown() in TtsController. 
  
  But inline # (#3, #1, etc.) in ANY path pass through unstripped.
""")

# Verify the key path
print("=" * 70)
print("DEMONSTRATION: Typical Gemini response with inline # references")
print("=" * 70)
typical_gemini = """### Plano de hoje

Olá! Aqui está o teu resumo:

**Proteína**: Estás no ponto #2 da lista de prioridades.
**Hidratos**: Ver secção #1 para detalhes.

- Item #3: beber mais água

Os passos #1 e #2 são os mais importantes."""

print("Input (typical Gemini response):")
print(typical_gemini)
print()
result = strip_markdown(typical_gemini)
print("After stripMarkdown:")
print(result)
print()
hashes = [i for i, c in enumerate(result) if c == '#']
if hashes:
    print(f"SURVIVING # at positions: {hashes}")
    print("THESE WILL BE SPOKEN AS 'cerquilha' by PT-PT TTS!")
else:
    print("No # survived — but what about this case?")

print()
print("=" * 70)
print("FINAL SUMMARY — Three concurrent bugs:")
print("=" * 70)
print("""
BUG 1 (PRIMARY): TtsController.stripMarkdown() regex gap
  Location: TtsController.kt, line 206
  Current:  s.replace(Regex("(?m)^#{1,6}\\\\s*"), "")
  Problem:  Only strips # at the EXACT start of a line (^).
            Does NOT strip:
              - "secção #3" (inline #)  ← speaks "cerquilha três"
              - "  ### Heading" (indented)  ← speaks "cerquilha cerquilha cerquilha"
              - "#fitness" (hashtag-style inline)
  Fix:      After stripping line-start headings, also strip standalone
            # that appear before digits or words:
              s.replace(Regex("#(?=\\\\d)"), "número ")   // #1 → número 1
              s.replace(Regex("#(?=[^\\\\s])"), "")       // residual #word → word
              OR more aggressively: s.replace("#", " ")   // nuke all remaining #

BUG 2 (SECONDARY): _cleanedMessageText is conditionally populated
  Location: ChatViewModel.kt, lines 1089-1090
  Current:  if (parsed.cleanedText != rawText) { 
                _cleanedMessageText.update { ... }
            }
  Problem:  For plain AI replies (no proposal markers), this map entry
            is never created. speakMessage() falls back to msg.text
            (raw text from DB, still has markdown). This is mostly
            fine because TtsController strips markdown — but it means
            the CONDITIONAL protects nothing against markdown in TTS.
  Fix:      Remove the condition — always populate _cleanedMessageText[id]:
              _cleanedMessageText.update { it + (messageId to parsed.cleanedText) }
            This ensures speakMessage() always uses proposal-stripped text,
            even if it's identical to rawText (no-op but consistent).

BUG 3 (LATENT): Voice Mode auto-speak uses event.cleanedText fallback
  Location: ChatViewModel.kt, line 3565
  Current:  val readable = _cleanedMessageText.value[id] ?: event.cleanedText
  Problem:  event.cleanedText is the sievedClean text which has markdown intact.
            If _cleanedMessageText[id] is null (Bug 2 above), readable
            contains markdown that IS then stripped by TtsController.
            But if Bug 1 exists (inline #), the # still survives.
  Fix:      Depends on fixing Bug 1 (TtsController regex).
""")
