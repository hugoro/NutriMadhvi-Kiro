package com.example.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFF9CCAFF)
val PurpleGrey80 = Color(0xFFBFC7D5)
val Pink80 = Color(0xFFD6C0E6)

// Sleek Interface Theme Palette
val SleekBg = Color(0xFFF3F4F9)
val SleekPrimary = Color(0xFF005FB0)
val SleekOnPrimary = Color.White
val SleekUserBubble = Color(0xFFD3E3FD)
val SleekUserText = Color(0xFF041E49)
val SleekBotBubble = Color.White
val SleekBotText = Color(0xFF1E293B)
val SleekSlateBack = Color(0xFF1E293B)
val SleekBorder = Color(0xFFE2E8F0)
val SleekTextMuted = Color(0xFF64748B)

// ── Accent palette (violeta) ────────────────────────────────────────────────
// Substitui o esquema verde antigo (#10B981 / #34D399 / #052E1A). Inspirada
// na spec CSS do Hugo: linear-gradient(135deg, #5D36FF, #9333EA) +
// box-shadow rgba(93,54,255, ...). Usada em botões primários, segmented
// selectors, indicadores de progresso, focus borders, FAB, gradients de
// gráficos. Coerente com o wallpaper aurora (drawable-nodpi/bg_aurora.webp).
val AccentPrimary = Color(0xFF7C3AED)   // violet-600 — substitui #10B981
val AccentSoft    = Color(0xFFA78BFA)   // violet-400 — substitui #34D399
val AccentDeep    = Color(0xFF5D36FF)   // azul-violeta da spec CSS — usado no glow
val AccentMagenta = Color(0xFF9333EA)   // magenta-violet — fim do gradient
val AccentInk     = Color(0xFF1E0B4D)   // violeta muito escuro — substitui #052E1A
val AccentGlow    = Color(0x665D36FF)   // 40% alpha do AccentDeep — para shadow/glow
