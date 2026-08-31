package com.example.ui

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.example.data.local.FoodEntry
import com.example.data.local.ActivityEntry
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Display row used by the on-screen `ReportTab` (in `ChatScreen.kt`) and the
 * XLSX exporter.
 *
 * Reports are a hybrid view: manual `ActivityEntry` rows, Samsung Health workout sessions, and
 * per-day SH "ambient" residual activity (active calories not covered by any SH workout) all
 * coexist in the same table. Rather than wedging two unrelated DB shapes into one, the merge
 * happens once in `ReportTab` and produces a uniform `ReportActivityRow` stream — both the
 * Compose UI (via [ActivitySummaryTableFromRows] / [ActivityPieChartFromRows]) and the
 * exporter consume it directly. The PNG share path no longer renders this manually:
 * `ReportTab` captures its own Compose subtree via `GraphicsLayer.toImageBitmap()` so the
 * shared image always mirrors what the user sees.
 */
data class ReportActivityRow(
    val name: String,
    val typeLabel: String,
    val durationMinutes: Int,
    val caloriesBurned: Int,
    val source: Source,
    /**
     * Fine-grained modality label the user-facing summary should display
     * INSIDE its coarse `type` bucket ("Distribuição por tipo"). For SH
     * workouts this is `w.title ?: w.sourceLabel` — i.e. the Samsung
     * `customTitle` when the user set one (rare but meaningful, e.g.
     * "Treino Sala"), falling back to the SDK's fine label ("Pilates",
     * "Weight machine"). For manual/chat entries this is the persisted
     * `ActivityEntry.sourceLabel`. Null when neither source has a specific
     * label (legacy quick-log paths); the aggregator then falls back to
     * `type.displayKey` for that bucket. Semantically identical to
     * `ShWorkout.displayLabel` for SH rows.
     */
    val sourceLabel: String? = null
) {
    enum class Source { MANUAL, SAMSUNG_HEALTH_WORKOUT, SAMSUNG_HEALTH_DAILY }
}

object ReportRenderer {

    fun generateReportPdf(
        context: Context,
        title: String,
        foods: List<FoodEntry>,
        activities: List<ActivityEntry>,
        /** TMB summed day-by-day, folded into the headline "Burned" total so
         *  Net = Consumed − Burned reflects a real deficit rather than ignoring
         *  resting energy expenditure. Default 0 keeps legacy callers green. */
        aggregatedBmrKcal: Int = 0
    ): File {
        val sharesDir = File(context.cacheDir, "shares").apply { mkdirs() }
        val outputFile = File(sharesDir, "NutriMadhvi_Report_${System.currentTimeMillis()}.pdf")
        
        val pdfDocument = PdfDocument()
        
        val width = 1000
        val rowHeight = 45
        val headerHeight = 220
        val totalsHeight = 160
        
        val foodRows = foods.size.coerceAtLeast(1)
        val activityRows = activities.size.coerceAtLeast(1)
        val height = headerHeight + totalsHeight + 100 + (foodRows * rowHeight) + 120 + (activityRows * rowHeight) + 120
        
        val pageInfo = PdfDocument.PageInfo.Builder(width, height, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas
        
        canvas.drawColor(Color.parseColor("#0F172A"))
        
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
        }
        
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 24f
        }
        
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val primaryColorPaint = Paint().apply {
            color = Color.parseColor("#10B981")
        }
        
        val blueColorPaint = Paint().apply {
            color = Color.parseColor("#3B82F6")
        }
        
        val cardPaint = Paint().apply {
            color = Color.parseColor("#1E293B")
            style = Paint.Style.FILL
        }

        val cardBorderPaint = Paint().apply {
            color = Color.parseColor("#334155")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        val linePaint = Paint().apply {
            color = Color.parseColor("#334155")
            strokeWidth = 1.5f
        }

        // Header
        canvas.drawRect(RectF(40f, 40f, width - 40f, 180f), cardPaint)
        canvas.drawRect(RectF(40f, 40f, width - 40f, 180f), cardBorderPaint)
        canvas.drawCircle(80f, 110f, 25f, primaryColorPaint)
        canvas.drawText("M", 70f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE ; textSize = 30f; typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText(title, 130f, 105f, titlePaint)
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        canvas.drawText("Generated on: ${sdf.format(Date())} • Powered by Gemini", 130f, 145f, subtitlePaint)
        
        // Totals Card
        var y = 200f
        canvas.drawRect(RectF(40f, y, width - 40f, y + totalsHeight), cardPaint)
        canvas.drawRect(RectF(40f, y, width - 40f, y + totalsHeight), cardBorderPaint)
        
        val totalFoodCals = foods.sumOf { it.calories }
        val totalBurnedCals = activities.sumOf { it.caloriesBurned } + aggregatedBmrKcal
        val totalActiveMins = activities.sumOf { it.durationMinutes }
        
        canvas.drawText("TOTAL BALANCE SUMMARY", 70f, y + 45f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#94A3B8"); textSize = 22f; typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText("Consumed", 70f, y + 90f, subtitlePaint)
        canvas.drawText("$totalFoodCals kcal", 70f, y + 130f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; typeface = Typeface.DEFAULT_BOLD })
        
        canvas.drawText("Burned", 360f, y + 90f, subtitlePaint)
        canvas.drawText("$totalBurnedCals kcal ($totalActiveMins min)", 360f, y + 130f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#60A5FA"); textSize = 32f; typeface = Typeface.DEFAULT_BOLD })
        
        val net = totalFoodCals - totalBurnedCals
        canvas.drawText("Net Calories", 680f, y + 90f, subtitlePaint)
        canvas.drawText("$net kcal", 680f, y + 130f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#34D399"); textSize = 32f; typeface = Typeface.DEFAULT_BOLD })
        
        // Foods Card Table
        y += totalsHeight + 40f
        canvas.drawRect(RectF(40f, y, width - 40f, y + 45f), primaryColorPaint)
        canvas.drawText("DIETARY LOGS ENTRY TABLE", 60f, y + 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f; typeface = Typeface.DEFAULT_BOLD })
        
        y += 45f
        canvas.drawRect(RectF(40f, y, width - 40f, y + 40f), cardPaint)
        canvas.drawText("Food Item Name", 60f, y + 28f, boldPaint)
        canvas.drawText("Meal Type", 480f, y + 28f, boldPaint)
        canvas.drawText("Cals", 680f, y + 28f, boldPaint)
        canvas.drawText("Macros (P/C/F)", 800f, y + 28f, boldPaint)
        
        y += 40f
        if (foods.isEmpty()) {
            canvas.drawText("No foods registered under this period timeframe criteria.", 60f, y + 30f, subtitlePaint)
            y += rowHeight
        } else {
            foods.forEach { food ->
                canvas.drawText(food.name.take(24), 60f, y + 30f, textPaint)
                canvas.drawText(food.mealCategory.name, 480f, y + 30f, textPaint)
                canvas.drawText("${food.calories} kcal", 680f, y + 30f, textPaint)
                canvas.drawText(String.format(Locale.US, "%.0f/%.0f/%.0f g", food.protein, food.carbs, food.fat), 800f, y + 30f, textPaint)
                canvas.drawLine(40f, y + rowHeight, width - 40f, y + rowHeight, linePaint)
                y += rowHeight
            }
        }
        
        // Activities Card Table
        y += 30f
        canvas.drawRect(RectF(40f, y, width - 40f, y + 45f), blueColorPaint)
        canvas.drawText("EXERCISE LOGS ENTRY TABLE & STATUS", 60f, y + 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f; typeface = Typeface.DEFAULT_BOLD })
        
        y += 45f
        canvas.drawRect(RectF(40f, y, width - 40f, y + 40f), cardPaint)
        canvas.drawText("Workouts Core Activity", 60f, y + 28f, boldPaint)
        canvas.drawText("Type Category", 450f, y + 28f, boldPaint)
        canvas.drawText("Active Time", 680f, y + 28f, boldPaint)
        canvas.drawText("Energy Burned", 840f, y + 28f, boldPaint)
        
        y += 40f
        if (activities.isEmpty()) {
            canvas.drawText("No specific physical workout entries logged in this timeline.", 60f, y + 30f, subtitlePaint)
            y += rowHeight
        } else {
            activities.forEach { act ->
                canvas.drawText(act.name.take(22), 60f, y + 30f, textPaint)
                canvas.drawText(act.activityType.displayKey, 450f, y + 30f, textPaint)
                canvas.drawText("${act.durationMinutes} minutes", 680f, y + 30f, textPaint)
                canvas.drawText("-${act.caloriesBurned} kcal", 840f, y + 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#60A5FA"); textSize = 28f })
                canvas.drawLine(40f, y + rowHeight, width - 40f, y + rowHeight, linePaint)
                y += rowHeight
            }
        }
        
        pdfDocument.finishPage(page)
        
        val out = FileOutputStream(outputFile)
        pdfDocument.writeTo(out)
        out.flush()
        out.close()
        pdfDocument.close()
        
        return outputFile
    }
}
