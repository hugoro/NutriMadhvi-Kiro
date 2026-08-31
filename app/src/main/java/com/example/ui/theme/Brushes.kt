package com.example.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * Gradient diagonal usado para preencher botões/pills primários — reproduz a
 * spec CSS do Hugo: `linear-gradient(135deg, #5D36FF, #9333EA)`.
 *
 * 135° em CSS = topo-esquerda → fundo-direita. Em Compose um Brush.linearGradient
 * sem `start`/`end` corre topo→fundo (90°). Para 135° precisamos passar Offsets
 * explícitos; usamos coordenadas relativas grandes para que escalonem com o
 * tamanho da Box em que o brush é desenhado.
 */
val AccentGradient: Brush = Brush.linearGradient(
    colors = listOf(AccentDeep, AccentMagenta),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)
