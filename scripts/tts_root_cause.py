"""
Root cause analysis: why "cerquilha" (Portuguese TTS word for "#") is spoken.
Tracing the complete text path from Gemini response to TTS engine.
"""
import re

# ─── Simulate the two key paths ────────────────────────────────────────────

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
    s = re.sub(r'(?m)^#{1,6}\s*', '', s)   # LINE 206 in TtsController
    s = re.sub(r'(?m)^\s*[-*_]{3,}\s*$', '\n', s)
    s = re.sub(r'(?m)^\s*[%=]{3,}\s*$', '\n', s)
    s = re.sub(r'(?m)^\s*[\[{][^\n]*?[}\]]\s*$', '\n', s)
    s = re.sub(r'(?m)^\s*[-*+]\s+', '', s)
    s = re.sub(r'(?m)^\s*\d+\.\s+', '', s)
    s = re.sub(r'\n{3,}', '\n\n', s)
    return s.strip()


# ─── CASE A: Normal path (works correctly) ─────────────────────────────────
print("=" * 70)
print("CASE A: Normal path — text with ### at beginning of line")
print("=" * 70)
normal = "### Resumo do dia\nAqui está o plano.\n### Proteína\nFalta 30g."
print(f"Input:  {repr(normal)}")
print(f"Output: {repr(strip_markdown(normal))}")
print("→ CORRECT: # stripped, 'cerquilha' NOT spoken\n")


# ─── CASE B: THE BUG — # that is NOT at start of line ──────────────────────
print("=" * 70)
print("CASE B: # embedded inline (e.g. inside a sentence)")
print("=" * 70)
# This is the KEY: the regex (?m)^#{1,6}\s* ONLY strips # at the START of a line
# If Gemini emits something like:
#   "O objetivo é #1 em proteína"  or  "Ver secção #3"
# The # is NOT at line start, so ^#{1,6} won't match it!
inline_hash = "O objetivo é #1 em proteína.\nVer secção #3 abaixo."
print(f"Input:  {repr(inline_hash)}")
print(f"Output: {repr(strip_markdown(inline_hash))}")
print("→ BUG: inline # NOT stripped, TTS reads 'cerquilha número um'\n")


# ─── CASE C: The REAL BUG — ingestAssistantMessage timing race ─────────────
print("=" * 70)
print("CASE C: THE ACTUAL BUG - speakMessage() receives msg.text from DB")
print("=" * 70)
print("""
FULL TRACE:
1. Gemini replies with finalReplyText (contains ### markdown headers + **/__ bold etc)
2. ChatOrchestrator persists: chatDao.insertMessage(text = finalReplyText) [RAW]
3. ChatOrchestrator emits: HandleEvent.Done(cleanedText = sievedClean, insertedMessageId = id)
   where sievedClean = ChatToneRules.clean(ActionProposalParser.parse(finalReplyText).cleanedText)
   = proposal markers stripped + forbidden phrases stripped, but MARKDOWN STILL INTACT

4. maybeAutoSpeakDone() is called:
   val readable = _cleanedMessageText.value[id] ?: event.cleanedText

   _cleanedMessageText[id] is populated by ingestAssistantMessage() which is called
   from the activeMessages Room Flow collector. This collector fires ASYNCHRONOUSLY
   after Room emits the new row. So at the instant maybeAutoSpeakDone() runs,
   _cleanedMessageText[id] may be null (not yet populated).
   
   Fallback: event.cleanedText = sievedClean = markdown-intact text.

5. TtsController.speak(readable=sievedClean_with_markdown, ...)
   calls stripMarkdown(sievedClean_with_markdown)
   → THIS SHOULD STRIP ### 

WAIT - let me check if the STREAMING path changes anything...
""")

# ─── CASE D: THE STREAMING PATH ────────────────────────────────────────────
print("=" * 70)
print("CASE D: Streaming path — HandleEvent.Partial chunks")
print("=" * 70)
print("""
During streaming, the ViewModel receives HandleEvent.Partial events.
Each partial has accumulatedText — this is the growing response so far.
The streaming bubble in the UI uses _streamingText.value = event.accumulatedText
and that streaming bubble IS rendered with Markdown() in ChatScreen.
So the display strips ### visually via the Markdown renderer.

BUT: the TTS (if triggered during streaming) would get accumulatedText
which may contain ### and those would be stripped by stripMarkdown.

The REAL question is: at what point does speakMessage / auto-speak fire?
→ Only on HandleEvent.Done (after full reply is complete).
→ At that point the full text IS available.

Let me look at the exact text content at the time of the Done event...
""")

# ─── CASE E: ACTUAL ROOT CAUSE — Line 206 regex BUG ──────────────────────
print("=" * 70)
print("CASE E: THE REAL ROOT CAUSE — The regex handles # BUT there's a")
print("         subtlety: # chars NOT at line start pass through unstripped.")
print("=" * 70)

# Looking at Gemini's actual output patterns...
# Gemini sometimes uses "##" or "###" but ALSO uses "#" in contexts like:
# - Hashtags: "#proteína #fitness"  
# - Section references: "ver ponto #2"
# - After certain escaped newlines: if the model writes "\n##Título"
#   and the \n was stored literally (not escaped), the ## may be at a "hidden" line start

# But MORE importantly: let me look at what's ACTUALLY different between
# the display path and the TTS path regarding the same text.

print("""
CONFIRMED ROOT CAUSE — Two-part bug:

PART 1: The text stored in DB (msg.text = finalReplyText) contains raw markdown
        including ### headers. This is intentional — the comment in the code says:
        "Persist the RAW final text so the existing ingestAssistantMessage flow
         in the ViewModel can re-derive proposals on the next collect"

PART 2: speakMessage() looks up:
        val readable = _cleanedMessageText.value[msg.id] ?: msg.text
        
        _cleanedMessageText[id] ONLY exists when ActionProposalParser stripped
        something (i.e., parsed.cleanedText != rawText). 
        
        See ChatViewModel.kt lines 1089-1090:
          if (parsed.cleanedText != rawText) {           ← CONDITIONAL!
            _cleanedMessageText.update { it + (messageId to parsed.cleanedText) }
          }
        
        IF the Gemini reply contains NO proposal markers ([HABIT_PROPOSAL], 
        [LOG_MEAL_PROPOSAL], etc.), then:
          parsed.cleanedText == rawText  (no markers were stripped)
          _cleanedMessageText[id] is NEVER populated!
          
        So speakMessage() falls back to: msg.text  ← RAW TEXT WITH ### 
        
        But wait — TtsController.stripMarkdown() IS called on this raw text,
        and it DOES strip ###. So the cerquilha should still not be spoken...
        
        UNLESS there's a path where stripMarkdown is NOT called!
""")

# ─── CASE F: CRITICAL — Is there any path where stripMarkdown is bypassed? ─
print("=" * 70)
print("CASE F: Looking for any path that bypasses stripMarkdown")
print("=" * 70)
print("""
Both TTS entry points in ChatViewModel call TtsController.speak():
  1. speakMessage() → tts.speak(readable, ...)    
  2. maybeAutoSpeakDone() → tts.speak(readable, ...)
  
TtsController.speak() ALWAYS calls stripMarkdown(text) on line 121.
There is NO way to call TtsController.speak() without going through stripMarkdown.

THEREFORE: The "cerquilha" is NOT caused by ### headers in the text.

NEW HYPOTHESIS: The PT-PT TTS engine vocalises a character that is NOT a #
but SOUNDS LIKE "cerquilha" in Portuguese — or the TTS encounters a # that
was NOT at a line start (not caught by the ^#{1,6}\s* regex).
""")

print("=" * 70)
print("CASE G: Characters that the PT-PT TTS engine reads as 'cerquilha'")
print("=" * 70)

# The only way "cerquilha" gets spoken is if a "#" character reaches the TTS engine.
# Let's catalog ALL places where # might survive stripMarkdown:

test_cases = [
    ("inline # not at line start", "Estás em #1 em proteína"),
    ("# after space mid-sentence", "ver item # 3 abaixo"),
    ("# preceded by newline+space", "\n  ### Heading"),  # note: indent
    ("## at end of line", "texto ## mais texto"),
    ("hashtag like Instagram", "#proteína #fitness"),
    ("# in code block (preserved?)", "valor = a #comentário"),
    ("# at line start but with CRLF", "linha1\r\n### Titulo\r\nlinha2"),
    ("# in quotes", "escrevi \"### secção\""),
]

print("\nCases where # might SURVIVE stripMarkdown:")
for label, text in test_cases:
    result = strip_markdown(text)
    has_hash = '#' in result
    marker = "⚠️  BUG: # SURVIVED" if has_hash else "✓ # stripped"
    print(f"\n  [{marker}]")
    print(f"  Label:  {label}")
    print(f"  Input:  {repr(text)}")
    print(f"  Output: {repr(result)}")
