package com.example.data.export

import com.example.data.activity.DailyActivityResolver
import com.example.data.bmr.BmrResolver
import com.example.data.health.BmrSnapshot
import com.example.data.health.ShDailyActivity
import com.example.data.health.ShWorkout
import com.example.data.local.ActivityEntry
import com.example.data.local.BmrSnapshotEntity
import com.example.data.local.DailyActivitySnapshotEntity
import com.example.data.local.FoodEntry
import com.example.data.local.MealCategory
import com.example.data.local.TargetSnapshot
import com.example.ui.ReportActivityRow
import com.example.ui.tr
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

/**
 * Builds the Drive-export `.xlsx` blob.
 *
 * Layout matches the reference workbook the user already shares with the nutritionist
 * (`diario_nutricao_junho_2026-06-01_pt.xlsx`): one sheet, eleven columns, one row per
 * logged meal in chronological order, then a TOTAL row at each day / week / month
 * boundary that summarises the macros consumed and the activity / energy balance.
 *
 *   | Data | Refeição | Descrição / Alimentos | Calorias | Hidratos | Proteína | Gordura | Gasto Ativa (kcal) | Gasto Total (kcal) | Meta diária | Atividade / Notas |
 *
 * The "Meta diária" column behaves differently on meal rows vs TOTAL rows.
 * On a MEAL row it carries that meal's share of the day's macro target,
 * per macronutrient (e.g. "H 8% · P 12% · G 21%") — so the nutritionist sees
 * how much each meal contributes toward the daily budget, not the budget
 * repeated verbatim on every row. On TOTAL rows it becomes the accumulated
 * target vs consumed (`Meta: … / Consumido: … (%)`), summing per-day targets
 * across the bucket (week / month). The targets themselves are pulled from the
 * `target_snapshots` table by the caller, NOT from userProfile, so a target
 * change today doesn't silently re-anchor last month's report. When no snapshot
 * covers a meal's day the cell degrades to "—".
 *
 * The "Gasto Ativa (kcal)" column carries only voluntary movement — SH active
 * kcal (workouts + ambient per snapshot / live aggregate) + non-overlapping
 * manual entries. It excludes BMR by design ([[activity-goal-vs-total-burned]]).
 * The "Gasto Total (kcal)" column adds BMR to that value, so it matches the
 * "Burned" scalar shown in the Análise tab. Both are blank on meal rows and
 * numeric on TOTAL rows only.
 *
 * The "Atividade / Notas" column is intentionally blank for meal rows. The full
 * activity summary for the bucket is concatenated (newline-separated) into the K
 * cell of the matching TOTAL row.
 */
object NutritionXlsxExporter {

    fun build(
        foods: List<FoodEntry>,
        activities: List<ReportActivityRow>,
        stepsByDate: Map<LocalDate, Long>,
        shWorkoutsByDate: Map<LocalDate, List<ReportActivityRow>>,
        bmrSnapshot: BmrSnapshot?,
        language: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
        /** Day → snapshot in force that day. Empty map = no historical targets
         *  available; the "Meta diária" column degrades to a blank string in that
         *  case so the layout still works. Populate via TargetResolver. */
        targetsByDay: Map<LocalDate, TargetSnapshot> = emptyMap(),
        /** Manual activity entries (with timestamps). Used to compute the
         *  non-overlapping-manual contribution to Gasto Total per day. */
        activitiesManual: List<ActivityEntry> = emptyList(),
        /** Frozen daily activity snapshots. Snapshot-first invariant per
         *  [DailyActivityResolver]. */
        dailyActivitySnapshots: List<DailyActivitySnapshotEntity> = emptyList(),
        /** Live SH workouts (whole list; filtered per day inside). Empty when
         *  SH is unavailable (worker path). */
        shWorkoutsRaw: List<ShWorkout> = emptyList(),
        /** Live SH daily aggregate by date. */
        shDailyByDate: Map<LocalDate, ShDailyActivity> = emptyMap(),
        /** Full BMR history sorted ascending — [BmrResolver.resolveBmrByDay]
         *  picks the value in force each day. */
        bmrSnapshotsAll: List<BmrSnapshotEntity> = emptyList(),
        /** Whether SH was authenticated at export time. Worker path: false. */
        shAvailable: Boolean = false,
    ): ByteArray {
        // The ByteArrayOutputStream lives OUTSIDE the workbook's `use {}` so we read
        // the bytes only after `wb.close()` has run — POI's OPCPackage finalises the
        // ZIP central directory in close(), and a previous version of this function
        // captured the bytes inside the use{} block, which on some Android+POI
        // combinations produced a 0-byte file in Drive while the export pretended
        // to succeed. See plan: Drive Export 0K bug, Hipótese B.
        val out = ByteArrayOutputStream()
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet(tr("Diário de Nutrição", language))

            val wrapStyle = wb.createCellStyle().apply {
                wrapText = true
                verticalAlignment = VerticalAlignment.TOP
            }
            val headerStyle = wb.createCellStyle().apply {
                val font = wb.createFont().apply { bold = true }
                setFont(font)
                alignment = HorizontalAlignment.LEFT
            }
            val totalStyle = wb.createCellStyle().apply {
                val font = wb.createFont().apply { bold = true }
                setFont(font)
                wrapText = true
                verticalAlignment = VerticalAlignment.TOP
            }

            // ---------- Header ----------
            val headerLabels = listOf(
                tr("Data", language),
                tr("Refeição", language),
                tr("Descrição / Alimentos", language),
                tr("Calorias (kcal)", language),
                tr("Hidratos de Carbono (g)", language),
                tr("Proteína (g)", language),
                tr("Gordura (g)", language),
                tr("Gasto Ativa (kcal)", language),
                tr("Gasto Total (kcal)", language),
                tr("Meta diária", language),
                tr("Atividade / Notas", language)
            )
            val headerRow = sheet.createRow(0)
            headerLabels.forEachIndexed { i, label ->
                headerRow.createCell(i).apply {
                    setCellValue(label)
                    cellStyle = headerStyle
                }
            }

            // ---------- Body ----------
            val foodsByDate = foods.sortedBy { it.timestamp }
                .groupBy {
                    Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate()
                }
            val orderedDates = foodsByDate.keys.sorted()

            val tsFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val dateOnlyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

            // Pre-resolve the shared inputs the "Gasto Total" column needs.
            // Snapshot lookup is O(1) per day; BMR-by-day is resolved once over
            // the full envelope so days before the first snapshot get the
            // backwards-fill value per BmrResolver's contract.
            val snapshotByDate: Map<LocalDate, DailyActivitySnapshotEntity> =
                dailyActivitySnapshots.associateBy { LocalDate.ofEpochDay(it.dateEpochDay) }
            val bmrByDay: Map<LocalDate, BmrSnapshotEntity> =
                if (orderedDates.isEmpty() || bmrSnapshotsAll.isEmpty()) emptyMap()
                else BmrResolver.resolveBmrByDay(
                    bmrSnapshotsAll,
                    orderedDates.first()..orderedDates.last()
                )
            // Manuals grouped by day for the non-overlap check.
            val manualsByDay: Map<LocalDate, List<ActivityEntry>> =
                activitiesManual.groupBy {
                    Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate()
                }

            var rowIdx = 1

            // Trackers for week/month TOTAL rollups: emit a TOTAL SEMANAL when ISO
            // week changes between consecutive dates, TOTAL MENSAL when month changes.
            var prevWeek: Int? = null
            var prevYear: Int? = null
            var prevMonth: Int? = null

            val weekFoodAccumulator = MacroAccumulator()
            val weekActivityAccumulator = ActivityAccumulator()
            val weekTargetAccumulator = TargetAccumulator()
            val monthFoodAccumulator = MacroAccumulator()
            val monthActivityAccumulator = ActivityAccumulator()
            val monthTargetAccumulator = TargetAccumulator()

            val rolloverWeekTotal: () -> Unit = {
                if (prevWeek != null && weekFoodAccumulator.hasAny()) {
                    rowIdx = writeTotalRow(
                        sheet, rowIdx,
                        dateLabel = "${tr("Semana", language)} $prevWeek",
                        labelMeal = tr("TOTAL SEMANAL", language),
                        labelDesc = tr("Soma do balanço da semana", language),
                        macros = weekFoodAccumulator,
                        gastoAtivaKcal = weekActivityAccumulator.activeCal + weekActivityAccumulator.manualCal,
                        gastoTotalKcal = weekActivityAccumulator.gastoTotalCal,
                        targetSummary = formatTargetTotal(weekTargetAccumulator, weekFoodAccumulator, language),
                        activitySummary = formatBalanceOnly(weekActivityAccumulator, language = language),
                        style = totalStyle
                    )
                }
                weekFoodAccumulator.reset()
                weekActivityAccumulator.reset()
                weekTargetAccumulator.reset()
            }
            val rolloverMonthTotal: () -> Unit = {
                if (prevMonth != null && monthFoodAccumulator.hasAny()) {
                    val monthLabel = "${prevYear}-${"%02d".format(prevMonth)}"
                    rowIdx = writeTotalRow(
                        sheet, rowIdx,
                        dateLabel = monthLabel,
                        labelMeal = tr("TOTAL MENSAL", language),
                        labelDesc = tr("Soma do balanço do mês", language),
                        macros = monthFoodAccumulator,
                        gastoAtivaKcal = monthActivityAccumulator.activeCal + monthActivityAccumulator.manualCal,
                        gastoTotalKcal = monthActivityAccumulator.gastoTotalCal,
                        targetSummary = formatTargetTotal(monthTargetAccumulator, monthFoodAccumulator, language),
                        activitySummary = formatBalanceOnly(monthActivityAccumulator, language = language),
                        style = totalStyle
                    )
                }
                monthFoodAccumulator.reset()
                monthActivityAccumulator.reset()
                monthTargetAccumulator.reset()
            }

            for (date in orderedDates) {
                val isoWeek = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                val isoYear = date.get(IsoFields.WEEK_BASED_YEAR)

                // Week / month rollovers fire BEFORE writing the new bucket. We compare
                // (year, week) for the ISO week so a Jan-1-still-in-week-52 case is correct.
                if (prevWeek != null && (prevWeek != isoWeek || prevYear != isoYear)) rolloverWeekTotal()
                if (prevMonth != null && (prevMonth != date.monthValue || prevYear != date.year)) rolloverMonthTotal()

                // Per-day food rows. Ativas + Gasto Total + Activity columns intentionally blank.
                val dayFoods = foodsByDate.getValue(date).sortedBy { it.timestamp }
                val dayMacros = MacroAccumulator()
                val dayTarget = targetsByDay[date]
                for (entry in dayFoods) {
                    val ts = Instant.ofEpochMilli(entry.timestamp).atZone(zoneId).toLocalDateTime()
                    val row = sheet.createRow(rowIdx++)
                    row.createCell(0).setCellValue(tsFormatter.format(ts))
                    row.createCell(1).setCellValue(mealLabel(entry.mealCategory, language))
                    row.createCell(2).setCellValue(entry.name)
                    row.createCell(3).setCellValue(entry.calories.toDouble())
                    row.createCell(4).setCellValue(entry.carbs)
                    row.createCell(5).setCellValue(entry.protein)
                    row.createCell(6).setCellValue(entry.fat)
                    row.createCell(7).setCellValue("") // Gasto Ativa (kcal) — only on TOTAL rows
                    row.createCell(8).setCellValue("") // Gasto Total (kcal) — only on TOTAL rows
                    row.createCell(9).setCellValue(formatMealTargetShare(entry, dayTarget))
                    row.createCell(10).setCellValue("")
                    dayMacros.add(entry)
                }

                // Day activity rollup — snapshot-first (past days) or live SH
                // aggregate (today), mirroring the Análise-tab invariant so the
                // Excel matches the app kcal-for-kcal. See DailyActivityResolver.
                val daySteps = stepsByDate[date] ?: 0L
                val dayWorkouts = shWorkoutsByDate[date] ?: emptyList()
                val manualsForDay = filterManualsNotOverlappingSh(
                    manuals = manualsByDay[date].orEmpty(),
                    workouts = shWorkoutsRaw,
                    date = date,
                    zoneId = zoneId,
                    shAvailable = shAvailable
                )
                val gasto = perDayGastoTotal(
                    date = date,
                    snapshotByDate = snapshotByDate,
                    workouts = shWorkoutsRaw,
                    shDaily = shDailyByDate,
                    stepsByDay = stepsByDate,
                    bmrByDay = bmrByDay,
                    manualsForDay = manualsForDay,
                    shAvailable = shAvailable,
                    zoneId = zoneId
                )
                val dayGastoTotal = gasto.totalKcal
                val dayActiveOnly = gasto.shActiveKcal + gasto.manualKcal
                val dayConsumed = dayMacros.calories
                val dayDeficit = dayConsumed - dayGastoTotal

                // Daily TOTAL row — concatenated activity column (steps +
                // per-workout breadcrumbs + deficit). Ativas + Gasto Total now
                // live in their own numeric columns above, so we don't repeat
                // them here.
                val dailyActivityCell = buildList {
                    if (daySteps > 0L) add("$daySteps ${tr("Passos", language)}")
                    for (w in dayWorkouts) {
                        if (w.durationMinutes > 0) {
                            // SH-sourced rows carry the fine-grained modality on
                            // `name` (populated from ShWorkout.displayLabel in
                            // buildReportActivityRows since the sourceLabel
                            // refactor). Manuals still use the coarse translated
                            // bucket in `typeLabel` — user text on `name` isn't
                            // guaranteed to be a modality noun.
                            val label = if (w.source == ReportActivityRow.Source.SAMSUNG_HEALTH_WORKOUT) {
                                w.name
                            } else {
                                tr(w.typeLabel, language)
                            }
                            add("${w.durationMinutes} ${tr("min", language)} $label")
                        }
                    }
                    add("${tr("Défice", language)}: ~$dayDeficit kcal")
                }.joinToString("\n")

                rowIdx = writeTotalRow(
                    sheet, rowIdx,
                    dateLabel = dateOnlyFormatter.format(date),
                    labelMeal = tr("TOTAL DIÁRIO", language),
                    labelDesc = tr("Soma dos macronutrientes do dia", language),
                    macros = dayMacros,
                    gastoAtivaKcal = dayActiveOnly,
                    gastoTotalKcal = dayGastoTotal,
                    targetSummary = formatDailyTargetVsConsumed(dayTarget, dayMacros, language),
                    activitySummary = dailyActivityCell,
                    style = totalStyle
                )

                // Update week/month accumulators after the day TOTAL.
                weekFoodAccumulator.merge(dayMacros)
                weekActivityAccumulator.merge(
                    active = gasto.shActiveKcal,
                    manual = gasto.manualKcal,
                    bmr = gasto.bmrKcal,
                    gastoTotal = gasto.totalKcal,
                    consumedDay = dayConsumed
                )
                weekActivityAccumulator.daysSeen += 1
                monthFoodAccumulator.merge(dayMacros)
                monthActivityAccumulator.merge(
                    active = gasto.shActiveKcal,
                    manual = gasto.manualKcal,
                    bmr = gasto.bmrKcal,
                    gastoTotal = gasto.totalKcal,
                    consumedDay = dayConsumed
                )
                monthActivityAccumulator.daysSeen += 1
                if (dayTarget != null) {
                    weekTargetAccumulator.add(dayTarget)
                    monthTargetAccumulator.add(dayTarget)
                }

                prevWeek = isoWeek
                prevYear = isoYear
                prevMonth = date.monthValue
            }

            // Final flushes for the last week and month in the dataset.
            rolloverWeekTotal()
            rolloverMonthTotal()

            // Apply wrap-style on the J ("Meta diária") and K ("Atividade / Notas")
            // columns so multi-line text renders correctly. Gasto Ativa (H) and
            // Gasto Total (I) are plain numeric cells and don't need wrap.
            for (i in 0..rowIdx) {
                val r = sheet.getRow(i) ?: continue
                listOf(9, 10).forEach { col ->
                    r.getCell(col)?.let { cell ->
                        if (cell.cellStyle.wrapText.not()) cell.cellStyle = wrapStyle
                    }
                }
            }

            // Reasonable column widths so the sheet is readable without manual resize.
            sheet.setColumnWidth(0, 18 * 256)   // Data
            sheet.setColumnWidth(1, 14 * 256)   // Refeição
            sheet.setColumnWidth(2, 50 * 256)   // Descrição
            sheet.setColumnWidth(3, 13 * 256)
            sheet.setColumnWidth(4, 13 * 256)
            sheet.setColumnWidth(5, 13 * 256)
            sheet.setColumnWidth(6, 13 * 256)
            sheet.setColumnWidth(7, 16 * 256)   // Gasto Ativa (kcal)
            sheet.setColumnWidth(8, 16 * 256)   // Gasto Total (kcal)
            sheet.setColumnWidth(9, 32 * 256)   // Meta diária
            sheet.setColumnWidth(10, 38 * 256)  // Atividade

            wb.write(out)
        }
        // wb.close() já correu — bytes prontos.
        return out.toByteArray()
    }

    private fun writeTotalRow(
        sheet: org.apache.poi.ss.usermodel.Sheet,
        rowIdx: Int,
        dateLabel: String,
        labelMeal: String,
        labelDesc: String,
        macros: MacroAccumulator,
        /** Voluntary-movement kcal (SH active + non-overlapping manuals; no BMR). */
        gastoAtivaKcal: Int?,
        /** Gasto Total for this bucket in kcal. Null → the cell stays blank (no
         *  data available); non-null → written as a numeric cell. */
        gastoTotalKcal: Int?,
        targetSummary: String,
        activitySummary: String,
        style: org.apache.poi.ss.usermodel.CellStyle
    ): Int {
        val row = sheet.createRow(rowIdx)
        row.createCell(0).setCellValue(dateLabel)
        row.createCell(1).setCellValue(labelMeal)
        row.createCell(2).setCellValue(labelDesc)
        row.createCell(3).setCellValue(macros.calories.toDouble())
        row.createCell(4).setCellValue(macros.carbs)
        row.createCell(5).setCellValue(macros.protein)
        row.createCell(6).setCellValue(macros.fat)
        if (gastoAtivaKcal != null) {
            row.createCell(7).setCellValue(gastoAtivaKcal.toDouble())
        } else {
            row.createCell(7).setCellValue("")
        }
        if (gastoTotalKcal != null) {
            row.createCell(8).setCellValue(gastoTotalKcal.toDouble())
        } else {
            row.createCell(8).setCellValue("")
        }
        row.createCell(9).setCellValue(targetSummary)
        row.createCell(10).setCellValue(activitySummary)
        for (i in 0..10) row.getCell(i).cellStyle = style
        return rowIdx + 1
    }

    /** "1800 kcal · 220H/85P/60G" — the accumulated/target label used on TOTAL
     *  rows. Empty when no snapshot covers the day (e.g. a brand-new install
     *  before the seed runs). */
    private fun formatDailyTargetLabel(snapshot: TargetSnapshot?): String {
        if (snapshot == null) return ""
        return "${snapshot.targetKcal} kcal · " +
            "${snapshot.targetCarbs.toInt()}H/${snapshot.targetProtein.toInt()}P/${snapshot.targetFat.toInt()}G"
    }

    /** Per-meal share of the day's macro target for the meal-row "Meta diária"
     *  cell, e.g. "H 8% · P 12% · G 21%" — how much this single meal contributes
     *  toward each daily macro budget. Uses the same H/P/G shorthand as the
     *  target label. Returns "—" when no snapshot covers the meal's day, so the
     *  layout still holds without inventing a target. */
    private fun formatMealTargetShare(entry: FoodEntry, target: TargetSnapshot?): String {
        if (target == null) return "—"
        fun pct(part: Double, whole: Double): Int =
            if (whole > 0.0) (part * 100.0 / whole).toInt() else 0
        val h = pct(entry.carbs, target.targetCarbs)
        val p = pct(entry.protein, target.targetProtein)
        val g = pct(entry.fat, target.targetFat)
        return "H $h% · P $p% · G $g%"
    }

    /** "Meta: 1800 kcal · 220H/85P/60G\nConsumido: 1623 kcal (90%)" on a daily TOTAL row. */
    private fun formatDailyTargetVsConsumed(
        snapshot: TargetSnapshot?,
        macros: MacroAccumulator,
        language: String
    ): String {
        if (snapshot == null) return ""
        val pct = if (snapshot.targetKcal > 0)
            (macros.calories * 100.0 / snapshot.targetKcal).toInt()
        else 0
        return buildList {
            add("${tr("Meta", language)}: ${formatDailyTargetLabel(snapshot)}")
            add("${tr("Consumido", language)}: ${macros.calories} kcal ($pct%)")
        }.joinToString("\n")
    }

    /** "Meta acumulada (7 dias): 12 600 kcal · 1540H/595P/420G\nConsumido: 11 200 kcal (89%)"
     *  on a weekly/monthly TOTAL row. */
    private fun formatTargetTotal(
        targets: TargetAccumulator,
        macros: MacroAccumulator,
        language: String
    ): String {
        if (targets.days == 0) return ""
        val pct = if (targets.kcal > 0L)
            (macros.calories * 100.0 / targets.kcal).toInt()
        else 0
        return buildList {
            add(
                "${tr("Meta acumulada", language)} (${targets.days} ${tr("dias", language)}): " +
                    "${targets.kcal} kcal · ${targets.carbs.toInt()}H/${targets.protein.toInt()}P/${targets.fat.toInt()}G"
            )
            add("${tr("Consumido", language)}: ${macros.calories} kcal ($pct%)")
        }.joinToString("\n")
    }

    private fun mealLabel(category: MealCategory, language: String): String =
        tr(category.displayKey, language)

    /**
     * Per-day breakdown of "Gasto Total (kcal)" that MUST match the value
     * shown in the Análise tab for the same date. Mirrors the invariant at
     * `ReportDailyActivity.kt:20-26`:
     *
     *   totalKcal = shActiveKcal + manualKcal + bmrKcal
     *
     * where `shActiveKcal` follows [DailyActivityResolver]'s snapshot-first
     * rule and `bmrKcal` picks the BMR in force that day via
     * [BmrResolver.resolveBmrByDay].
     */
    private data class GastoTotalDay(
        val totalKcal: Int,
        val manualKcal: Int,
        val shActiveKcal: Int,
        val bmrKcal: Int,
    )

    private fun perDayGastoTotal(
        date: LocalDate,
        snapshotByDate: Map<LocalDate, DailyActivitySnapshotEntity>,
        workouts: List<ShWorkout>,
        shDaily: Map<LocalDate, ShDailyActivity>,
        stepsByDay: Map<LocalDate, Long>,
        bmrByDay: Map<LocalDate, BmrSnapshotEntity>,
        manualsForDay: List<ActivityEntry>,
        shAvailable: Boolean,
        zoneId: ZoneId,
    ): GastoTotalDay {
        val shActive = snapshotByDate[date]?.activeKcal
            ?: if (shAvailable) DailyActivityResolver
                .computeLiveKcalForDay(
                    day = date,
                    workouts = workouts,
                    dailySummary = shDaily[date],
                    stepsForDay = stepsByDay[date] ?: 0L,
                    zoneId = zoneId
                ).activeKcal
            else 0
        val manual = manualsForDay.sumOf { it.caloriesBurned }
        val bmr = bmrByDay[date]?.valueKcal ?: 0
        return GastoTotalDay(
            totalKcal = shActive + manual + bmr,
            manualKcal = manual,
            shActiveKcal = shActive,
            bmrKcal = bmr,
        )
    }

    /**
     * Drop manual entries whose [start, start+duration) window overlaps any SH
     * workout on the same day — same rule ChatScreen uses at :6974-6978 so the
     * Excel doesn't double-count a manual "run" the user also logged in SH.
     * When SH isn't available, all manuals count (nothing to overlap with).
     */
    private fun filterManualsNotOverlappingSh(
        manuals: List<ActivityEntry>,
        workouts: List<ShWorkout>,
        date: LocalDate,
        zoneId: ZoneId,
        shAvailable: Boolean,
    ): List<ActivityEntry> {
        if (!shAvailable || workouts.isEmpty()) return manuals
        val workoutsOfDay = workouts.filter {
            it.startTime.atZone(zoneId).toLocalDate() == date
        }
        if (workoutsOfDay.isEmpty()) return manuals
        return manuals.filter { m ->
            val start = Instant.ofEpochMilli(m.timestamp)
            val end = start.plusSeconds(m.durationMinutes.toLong() * 60L)
            workoutsOfDay.none { w ->
                start.isBefore(w.endTime) && w.startTime.isBefore(end)
            }
        }
    }

    private fun formatBalanceOnly(
        acc: ActivityAccumulator,
        language: String
    ): String {
        val deficit = acc.consumed - acc.gastoTotalCal
        return "${tr("Défice", language)}: ~$deficit kcal"
    }

    private class MacroAccumulator {
        var calories: Int = 0
        var carbs: Double = 0.0
        var protein: Double = 0.0
        var fat: Double = 0.0
        fun add(entry: FoodEntry) {
            calories += entry.calories
            carbs += entry.carbs
            protein += entry.protein
            fat += entry.fat
        }
        fun merge(other: MacroAccumulator) {
            calories += other.calories
            carbs += other.carbs
            protein += other.protein
            fat += other.fat
        }
        fun hasAny() = calories != 0 || carbs != 0.0 || protein != 0.0 || fat != 0.0
        fun reset() { calories = 0; carbs = 0.0; protein = 0.0; fat = 0.0 }
    }

    private class ActivityAccumulator {
        var activeCal: Int = 0
        var manualCal: Int = 0
        var bmrCal: Int = 0
        var gastoTotalCal: Int = 0
        var consumed: Int = 0
        var daysSeen: Int = 0
        fun merge(active: Int, manual: Int, bmr: Int, gastoTotal: Int, consumedDay: Int) {
            activeCal += active
            manualCal += manual
            bmrCal += bmr
            gastoTotalCal += gastoTotal
            consumed += consumedDay
        }
        fun reset() {
            activeCal = 0; manualCal = 0; bmrCal = 0; gastoTotalCal = 0
            consumed = 0; daysSeen = 0
        }
    }

    /** Sums per-day targets across a bucket. Days without a covering snapshot
     *  contribute zero — same defensive behaviour as TargetResolver. */
    private class TargetAccumulator {
        var kcal: Long = 0
        var protein: Double = 0.0
        var carbs: Double = 0.0
        var fat: Double = 0.0
        var steps: Long = 0
        var activeMinutes: Long = 0
        var activityCalories: Long = 0
        var days: Int = 0
        fun add(snap: TargetSnapshot) {
            kcal += snap.targetKcal
            protein += snap.targetProtein
            carbs += snap.targetCarbs
            fat += snap.targetFat
            steps += snap.targetSteps
            activeMinutes += snap.targetActiveMinutes
            activityCalories += snap.targetActivityCalories
            days += 1
        }
        fun reset() {
            kcal = 0; protein = 0.0; carbs = 0.0; fat = 0.0
            steps = 0; activeMinutes = 0; activityCalories = 0
            days = 0
        }
    }
}
