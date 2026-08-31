"""
Simulate the exact stripMarkdown logic from TtsController.kt
to confirm whether ### headers are stripped or survive.
"""
import re

def strip_markdown(s: str) -> str:
    # ── Markdown formatting
    s = re.sub(r'(?s)```.*?```', ' ', s)
    s = re.sub(r'`([^`]+)`', r'\1', s)
    s = re.sub(r'!\[([^\]]*)\]\([^)]*\)', r'\1', s)
    s = re.sub(r'\[([^\]]+)\]\([^)]*\)', r'\1', s)
    s = re.sub(r'\*\*([^*]+)\*\*', r'\1', s)
    s = re.sub(r'__([^_]+)__', r'\1', s)
    s = re.sub(r'(?<![*])\*([^*\n]+)\*(?![*])', r'\1', s)
    s = re.sub(r'(?<![_])_([^_\n]+)_(?![_])', r'\1', s)
    # THE CRITICAL LINE: strips "### Heading" -> " Heading" (strips only the "### " prefix per line)
    s = re.sub(r'(?m)^#{1,6}\s*', '', s)
    s = re.sub(r'(?m)^\s*[-*_]{3,}\s*$', '\n', s)
    s = re.sub(r'(?m)^\s*[%=]{3,}\s*$', '\n', s)
    s = re.sub(r'(?m)^\s*[\[{][^\n]*?[}\]]\s*$', '\n', s)
    s = re.sub(r'(?m)^\s*[-*+]\s+', '', s)
    s = re.sub(r'(?m)^\s*\d+\.\s+', '', s)
    s = re.sub(r'\n{3,}', '\n\n', s)
    return s.strip()

# Test 1: Does ### heading get stripped?
test1 = "### Plano de refeições\nAqui está o teu plano."
print("=== TEST 1: ### heading ===")
result1 = strip_markdown(test1)
print(f"Input:  {repr(test1)}")
print(f"Output: {repr(result1)}")
print()

# Test 2: What about a # at the start of a line NOT followed by space?
test2 = "# Título\n## Subtítulo\n### Secção\nTexto normal"
print("=== TEST 2: multiple header levels ===")
result2 = strip_markdown(test2)
print(f"Input:  {repr(test2)}")
print(f"Output: {repr(result2)}")
print()

# Test 3: The key case - what if the AI writes "###Secção" (no space)?
test3 = "###Secção\nTexto aqui"
print("=== TEST 3: ### without space ===")
result3 = strip_markdown(test3)
print(f"Input:  {repr(test3)}")
print(f"Output: {repr(result3)}")
print()

# Test 4: Simulate the actual flow.
# cleanedText from ActionProposalParser = raw text with proposal markers stripped, but markdown INTACT.
# Then ChatToneRules.clean() is applied — but that only filters forbidden sentences, not markdown.
# Then this text is stored in DB (as finalReplyText = raw text) 
# OR sievedClean (which is proposal-stripped + tone-filtered but still has markdown)
# The TTS gets: _cleanedMessageText[id] (which is ActionProposalParser.cleanedText from ingestAssistantMessage)
# OR event.cleanedText (which is sievedClean from ChatOrchestrator — also still has markdown)

raw_with_headers = """### Resumo do dia

Até agora comeste **1200 kcal** com uma boa distribuição.

### Proteína

Estás a 45g de proteína. Vai ter de ser ao jantar."""

print("=== TEST 4: Full realistic AI response ===")
print("INPUT (raw with markdown):")
print(raw_with_headers)
print()
result4 = strip_markdown(raw_with_headers)
print("AFTER stripMarkdown:")
print(result4)
print()

# Test 5: What the TTS actually receives when cleanedMessageText has markdown intact
# The cleanedMessageText stores ActionProposalParser.cleanedText which is ONLY proposal-stripped
# This text still has ### headers
text_with_headers_to_tts = raw_with_headers  # no markdown stripping happened before TTS
print("=== TEST 5: What TTS receives vs what display shows ===")
print("TTS INPUT:", repr(text_with_headers_to_tts[:80]))
print("stripMarkdown output:", repr(strip_markdown(text_with_headers_to_tts)[:80]))
print()

# Test 6: EDGE CASE - what if there's a # inside a line (not at start)?
# The regex is (?m)^#{1,6}\s* which only matches # at start of line
# But what if Gemini writes: "## Macro summary\n" 
# The ## at line start IS caught. So why is "cerquilha" being spoken?

# HYPOTHESIS: The stripMarkdown regex IS working for # at line start.
# But what about #TAGS inline, or when the message text stored in DB
# is the RAW finalReplyText (not sievedClean), and the TTS path
# falls back to msg.text instead of _cleanedMessageText[id]?

print("=== TEST 6: The fallback path ===")
print("""
The key insight:
- ChatOrchestrator.handle() persists: text = finalReplyText (RAW - with markdown AND proposal markers)
- HandleEvent.Done carries: cleanedText = sievedClean (proposal-stripped + tone-filtered, but markdown STILL INTACT)
- _cleanedMessageText[id] is populated from ingestAssistantMessage(messageId, rawText=finalReplyText)
  => ActionProposalParser.parse(rawText).cleanedText = proposal-stripped only, markdown STILL INTACT

So when speakMessage() calls:
  val readable = _cleanedMessageText.value[msg.id] ?: msg.text
  tts.speak(readable, ...)  

Then TtsController.speak() calls:
  val cleaned = stripMarkdown(text)

The stripMarkdown SHOULD handle this. Let's verify the regex is correct...
""")

# Test 7: Verify the exact Kotlin regex translates correctly
# Kotlin: s.replace(Regex("(?m)^#{1,6}\\\\s*"), "")
# Python equivalent: re.sub(r'(?m)^#{1,6}\s*', '', s)
test_regex = "### Heading\nText\n## Another\nMore text"
print("=== TEST 7: Verifying regex ===")
print(f"Input: {repr(test_regex)}")
result = re.sub(r'(?m)^#{1,6}\s*', '', test_regex)
print(f"After heading strip: {repr(result)}")
print()

# The regex (?m)^#{1,6}\s* DOES work. It replaces "### " (including the space) with "".
# So "### Resumo" becomes "Resumo"
# This means the stripMarkdown DOES work for headings at start of line.
# 
# BUT WAIT - let me re-read the regex carefully:
# ^#{1,6}\\s*  — this matches 1-6 # chars followed by ZERO OR MORE whitespace
# That means it also matches a LONE "#" on a line by itself!
# And it matches "#" followed by word (no space): "###Word" -> "Word"  (test3 confirms)
# 
# So the TTS DOES strip ###. But why is "cerquilha" spoken?
# 
# CRUCIAL QUESTION: Is the TTS getting the text BEFORE or AFTER stripMarkdown is called?
# Answer: TtsController.speak() calls stripMarkdown(text) internally.
# So the question is: what TEXT is passed to tts.speak()?
# 
# Two code paths:
# 1. speakMessage(msg): tts.speak(_cleanedMessageText[id] ?: msg.text, ...)
# 2. maybeAutoSpeakDone(event): tts.speak(_cleanedMessageText[id] ?: event.cleanedText, ...)
# 
# In BOTH paths, the text passed is either:
# a) _cleanedMessageText[id] = ActionProposalParser.cleanedText = proposal-stripped, markdown INTACT
# b) fallback = msg.text (raw) OR event.cleanedText (also markdown-intact)
# 
# So ALL paths pass markdown-intact text to TtsController.speak().
# TtsController.speak() then calls stripMarkdown() which removes ###.
# 
# This SHOULD work... unless there's a timing issue or the regex has a bug.

print("=== FINAL ANALYSIS ===")
# Let me test the actual problematic scenario
problematic = "### Resumo\nTexto aqui"
after_strip = strip_markdown(problematic)
print(f"'### Resumo\\nTexto' -> '{after_strip}'")
# Is the # being spoken? Let's check what a PT-PT TTS engine would do with a bare "#" 
# that somehow survived.
print()
print("Checking if stripMarkdown misses any # case...")
# What if the text has: \n\n### heading (multiple newlines before #)?
tricky = "\n\n### Heading\nText"
print(f"Tricky (newlines before ###): {repr(strip_markdown(tricky))}")

# What about ###heading (no space)?
tricky2 = "###heading\nText"
print(f"No space: {repr(strip_markdown(tricky2))}")

# What about text like "**### heading**"?
tricky3 = "**### heading**\nText"
print(f"Bold heading: {repr(strip_markdown(tricky3))}")
# ** stripping runs BEFORE # stripping, so: "### heading\nText" -> "heading\nText" - works

# WAIT - in the Kotlin code, the order matters:
# First: ** -> stripped (bold)
# THEN: ^#{1,6} -> stripped
# But after ** stripping, "**### heading**" becomes "### heading" - YES that's fine.
# After # stripping: "heading" - correct!

# BUT: what about "**bold**" INSIDE a heading?
tricky4 = "### **Bold Heading**\nText"
print(f"Bold inside heading: {repr(strip_markdown(tricky4))}")
# Bold strip: "### Bold Heading\nText" -> heading strip: "Bold Heading\nText" - correct!

print()
print("ALL CASES HANDLED BY stripMarkdown!")
print()
print("""
CONCLUSION: The stripMarkdown regex correctly handles all ### variants.
The "cerquilha" issue is NOT caused by ### in the text after stripMarkdown.

New hypothesis: The issue is a TIMING RACE CONDITION.

When maybeAutoSpeakDone() fires:
  val readable = _cleanedMessageText.value[id] ?: event.cleanedText

_cleanedMessageText[id] is populated by ingestAssistantMessage() which is 
called when the activeMessages Room Flow re-emits. But there is a race:
- HandleEvent.Done fires with insertedMessageId
- maybeAutoSpeakDone reads _cleanedMessageText[id]
- BUT ingestAssistantMessage may NOT have been called yet for this id!
  (Room Flow re-emission for the inserted message hasn't fired yet)
- So the fallback is event.cleanedText

event.cleanedText = sievedClean from ChatOrchestrator = markdown STILL INTACT
This text IS still passed through stripMarkdown in TtsController.speak()...

So stripMarkdown should still handle it. Unless... the stripping is wrong?
""")
