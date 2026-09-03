package com.example.ui

import com.example.ui.theme.AccentInk
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentSoft
import com.halilibo.richtext.commonmark.Markdown

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.export.ReportHtmlBuilder
import androidx.compose.ui.graphics.toArgb
import com.example.data.local.ChatMessage
import com.example.data.local.ChatSession
import com.example.data.remote.sanitizeAiNewlines
import com.example.ui.components.Macro
import com.example.ui.components.MacroBarWithIngredients
import com.example.ui.components.MacroBreakdownDialog
import com.example.ui.components.MacroKind
import com.example.ui.components.ConfidenceChip
import com.example.ui.components.effectiveFreeSugars
import com.example.ui.components.formatIngredientHint
import com.example.ui.components.parseIngredients
import com.example.ui.components.topFreeSugarSourceHint
import com.example.data.local.FoodEntry
import com.example.data.local.ActivityEntry
import com.example.data.health.SamsungHealthRepository
import java.time.LocalDate
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// Tabs Enumeration
enum class AppTab {
    DASHBOARD, HISTORY, ACTIVITY, CHAT, HABITS, REPORT
}

/**
 * Detecta swipe horizontal num filho. Consome o gesto, por isso o HorizontalPager
 * pai não recebe nada — útil para zonas onde o swipe deve mudar estado local
 * em vez de mudar de tab (period picker, DateSelectorBar). Threshold em dp evita
 * disparos por toques imprecisos; abaixo do threshold o gesto é ignorado mas
 * mesmo assim consumido, por isso não cai para o pager.
 */
private fun Modifier.horizontalSwipe(
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    thresholdDp: Dp = 48.dp,
): Modifier = composed {
    val thresholdPx = with(LocalDensity.current) { thresholdDp.toPx() }
    pointerInput(onSwipeLeft, onSwipeRight) {
        var totalDx = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDx = 0f },
            onDragEnd = {
                if (totalDx <= -thresholdPx) onSwipeLeft()
                else if (totalDx >= thresholdPx) onSwipeRight()
            },
            onDragCancel = { totalDx = 0f },
            onHorizontalDrag = { change, dx -> totalDx += dx; change.consume() }
        )
    }
}

// Helper image compressor to send Base64 images directly to Gemini Pro API
@Deprecated(
    "Phase 5 (2026-06-13): re-encodes JPEG q=70 — destroys nutrition-label text detail. " +
        "For new code use FoodImageStore.loadImageBytesAndMime(context, fileUri) which reads " +
        "the original bytes verbatim. Kept for legacy callers (drawn-on-canvas etc.) where no source file exists.",
    ReplaceWith("FoodImageStore.loadImageBytesAndMime(context, fileUri)?.first")
)
fun Bitmap?.toBase64(): String? {
    if (this == null) return null
    return try {
        val outputStream = ByteArrayOutputStream()
        this.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}

/**
 * Persists a bitmap to app-private storage under `filesDir/food_images/` and returns the
 * resulting `file://` URI as a string, or null on failure.
 *
 * Used by both the camera and gallery capture paths so that `FoodEntry.imagePath` always
 * points to a stable, app-owned file. Avoids two well-known fragility traps:
 *  - `cacheDir` is purgeable by Android; images written there disappear between launches.
 *  - `content://` URIs returned by `GetContent()` lose read permission on process death
 *    (we'd need `takePersistableUriPermission`, which `GetContent()` doesn't grant).
 *
 * Bytes are copied inside the picker callback while the temporary grant is still alive,
 * so no `READ_MEDIA_IMAGES` runtime permission is required on Android 13+.
 */
@Deprecated(
    "Phase 5 (2026-06-13): re-encodes JPEG q=90 on top of an already-decoded Bitmap. " +
        "Use FoodImageStore.persistFromUri(context, sourceUri) to byte-copy from the source URI " +
        "instead. Kept only for the rare callers that have a Bitmap and no source URI.",
    ReplaceWith("FoodImageStore.persistFromUri(context, sourceUri)")
)
fun Bitmap.persistToFoodImages(context: android.content.Context): String? {
    return try {
        val dir = java.io.File(context.filesDir, "food_images").apply { if (!exists()) mkdirs() }
        val file = java.io.File(dir, "img_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.jpg")
        java.io.FileOutputStream(file).use { out ->
            this.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        Uri.fromFile(file).toString()
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * Persists a bitmap to the public DCIM/Camera/ folder so it shows up in the device
 * Gallery alongside other camera shots. Used only for photos taken INSIDE the app
 * — gallery-picked images already live in public storage so we don't duplicate.
 *
 * Why we need both this and persistToFoodImages():
 *  - persistToFoodImages writes to filesDir (app-private) — fast load for the in-app
 *    thumbnail, but vanishes if the user uninstalls / clears app data / reinstalls
 *    with a different signature.
 *  - This writes to DCIM/Camera/ (public, user-managed). Survives app data wipes,
 *    so the user can recover the picture later and re-attach it to a manually
 *    re-entered meal log. Reported case: "Greek yogurt bowl" lost its image after
 *    a re-install with no public copy to fall back on.
 *
 * MediaStore + IS_PENDING flow on minSdk=29 (scoped storage): no runtime permission
 * needed, the entry the app inserts is owned by the app's package. Returns the
 * content:// URI as a string, or null on failure (caller ignores — fire-and-forget).
 */
@Deprecated(
    "Phase 5 (2026-06-13): re-encodes JPEG when mirroring to DCIM/Camera/. " +
        "Use FoodImageStore.mirrorToPublicCamera(context, sourceFileUri) for a byte-copy mirror.",
    ReplaceWith("FoodImageStore.mirrorToPublicCamera(context, sourceFileUri)")
)
fun Bitmap.persistToPublicCamera(context: android.content.Context): String? {
    return try {
        val displayName = "Nutrimadhvi_${System.currentTimeMillis()}.jpg"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DCIM + "/Camera"
            )
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Images.Media.getContentUri(
            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
        )
        val uri = resolver.insert(collection, values) ?: return null
        val ok = resolver.openOutputStream(uri)?.use { out ->
            this.compress(Bitmap.CompressFormat.JPEG, 90, out)
        } ?: false
        if (!ok) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri.toString()
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// Utility to verify date alignments
fun isSameDay(t1: Long, t2: Long): Boolean {
    val zoneId = java.time.ZoneId.systemDefault()
    val date1 = java.time.Instant.ofEpochMilli(t1).atZone(zoneId).toLocalDate()
    val date2 = java.time.Instant.ofEpochMilli(t2).atZone(zoneId).toLocalDate()
    return date1 == date2
}

// -------- Chat scroll helpers (2026-07-04) --------
//
// Two suspend/pure helpers used by the "pin user msg to viewport top"
// LaunchedEffects in [ChatScreen]. Extracted so the two effects (send
// and end-of-turn re-pin) share exactly one code path — the earlier
// inline duplicates drifted apart across iterations and one of them
// carried the bug.
//
// Design rules (see memory `chat-user-bubble-top-pin-2026-07-03`):
//   * Compute the LazyColumn index directly from `activeMessages`
//     mirroring `chatTimelineRows` — NEVER look up via
//     `listState.layoutInfo.visibleItemsInfo` (the just-sent msg
//     may not be in the viewport yet, fallback picks the wrong
//     item and causes multi-turn drift).
//   * Wait ONE frame via `withFrameNanos` before the scroll so
//     the LazyColumn has committed the new item to its pool.
//     `yield()` alone is not frame-aligned; `withFrameNanos`
//     is the Compose primitive for "next frame".
//   * Pass `topGapPx` in from the caller (density-scaled `12.dp`
//     hoisted to the composable so it's not recomputed per
//     effect).

/**
 * Compute the LazyColumn item index for [userId] within the chat's
 * timeline. Mirrors [chatTimelineRows]: one Header row per
 * day-boundary transition + one Msg row per message.
 *
 * Returns -1 if [userId] is not present in [messages].
 *
 * O(N) in [messages.size] but the inner loop is tight (integer
 * arithmetic on `timestamp / MILLIS_PER_DAY` — no allocation of
 * `Instant`/`ZonedDateTime`/`LocalDate` per message). N is
 * bounded by the active session's message count (typically < 200).
 */
private fun userMsgLazyIndex(
    messages: List<com.example.data.local.ChatMessage>,
    userId: Long,
): Int {
    // Zone-offset-aware "day bucket" via integer math. `zoneOffsetMs`
    // captures the current wall-clock offset (including DST) so that
    // `((timestamp + offset) / MILLIS_PER_DAY)` yields the same
    // integer for two timestamps on the same local calendar day. This
    // matches `LocalDate.ofInstant(instant, zone).toEpochDay()`
    // WITHOUT the per-call ZonedDateTime allocation `chatTimelineRows`
    // uses. If the timeline formula in `chatTimelineRows` ever needs
    // sub-day granularity, both call sites must update together.
    val zone = java.time.ZoneId.systemDefault()
    val zoneOffsetMs = zone.rules.getOffset(java.time.Instant.now()).totalSeconds * 1000L
    var lazyIdx = -1
    var lastDay = Long.MIN_VALUE
    for (m in messages) {
        val day = (m.timestamp + zoneOffsetMs) / MILLIS_PER_DAY
        if (day != lastDay) {
            lazyIdx++  // header row
            lastDay = day
        }
        lazyIdx++      // message row
        if (m.id == userId) return lazyIdx
    }
    return -1
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

/**
 * Pin the message at LazyColumn index [target] to the top of the
 * viewport with a [topGapPx] breathing gap below the ScreenHeader.
 *
 * Waits one frame (via `withFrameNanos`) so the LazyColumn commits
 * any pending item insertions/removals BEFORE the scroll — otherwise
 * `scrollToItem` can land on a stale layout and jump to the wrong
 * offset.
 *
 * Emits one compact `Log.d` line per invocation, tagged with
 * [tag] (e.g. "send" / "end-of-turn") and [userId] for triage. Log
 * cost is one string + one printf per call — cheap enough for both
 * hot sites in [ChatScreen].
 */
private suspend fun pinUserMsgToTop(
    listState: androidx.compose.foundation.lazy.LazyListState,
    target: Int,
    topGapPx: Int,
    tag: String,
    userId: Long,
) {
    // withFrameNanos suspends until the next Choreographer frame —
    // the correct primitive for "wait for Compose to lay out the
    // pending changes before I scroll." `yield()` doesn't guarantee
    // frame alignment.
    androidx.compose.runtime.withFrameNanos { }
    runCatching { listState.scrollToItem(target, -topGapPx) }
    if (CHAT_SCROLL_LOG_ENABLED) {
        android.util.Log.d("CHAT_SCROLL", "$tag id=$userId target=$target")
    }
}

/**
 * Compile-time gate for chat-scroll diagnostic logging. `false`
 * in release keeps `Log.d` off the hot path entirely (dead-code
 * elimination). Flip to `true` locally when debugging a scroll
 * regression, then rebuild.
 */
private const val CHAT_SCROLL_LOG_ENABLED = false



@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(initialPage = AppTab.DASHBOARD.ordinal) {
        AppTab.values().size
    }
    val coroutineScope = rememberCoroutineScope()
    val currentTab = AppTab.values()[pagerState.currentPage]

    // Bloco I (proactive notifications): when MainActivity.handleNotificationIntent
    // parks an AppTab.CHAT switch on the ViewModel (after a notification tap),
    // animate the pager to that page and clear the signal so it doesn't fire
    // again on recomposition / config change. Uses animateScrollToPage to match
    // the existing selectTab() animation style; a no-op when already on CHAT.
    val pendingTab by viewModel.pendingTabSwitch.collectAsState()
    LaunchedEffect(pendingTab) {
        pendingTab?.let {
            pagerState.animateScrollToPage(it.ordinal)
            viewModel.consumeTabSwitch()
        }
    }
    // Tab a regressar quando o pending entry/session terminar (save ou reject).
    // Só é escrito quando a edição é despoletada a partir de outra tab que não
    // a Activity (ex.: tocar num item na History abre o form na Activity).
    var originTab by remember { mutableStateOf<AppTab?>(null) }
    // 2026-07-10 (Hugo) — which dashboard macro popup (Hidratos/Proteína/Gordura/
    // Calorias) to re-open after the user opens Edit Meal from a popup row and
    // then cancels/backs out. Hoisted above the pager because DashboardTab leaves
    // composition during the edit (its `breakdownFor` remember is destroyed).
    // rememberSaveable (MacroKind is a Serializable enum) so it also survives
    // config changes. Cleared on Save and manual tab switches so a popup only
    // ever re-opens on an explicit cancel/back. See plan-no-pop-up-dos-carbs.
    var dashboardBreakdownToRestore by rememberSaveable { mutableStateOf<MacroKind?>(null) }
    var historyFilter by remember { mutableStateOf("All") }
    // 2026-07-10 (Hugo) — hoisted so the Histórico scroll position survives the
    // in-place swap to the Edit Meal form and back (the HISTORY pager slot
    // swaps HistoryTab out of composition while editing, which would otherwise
    // reset an internal LazyListState to the top). rememberSaveable so it also
    // survives config changes. Passed into HistoryTab's LazyColumn.
    val historyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val foods by viewModel.foods.collectAsState()
    val activities by viewModel.activities.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val context = LocalContext.current

    // Helper: clicar manualmente noutra tab descarta a originTab para não
    // saltarmos de volta inesperadamente quando o pending limpar mais tarde.
    // Também limpa o pending session — o utilizador escolheu sair, portanto
    // a edit view fecha (linhas guardadas ficam na DB; drafts são descartados).
    val selectTab: (AppTab) -> Unit = { tab ->
        originTab = null
        dashboardBreakdownToRestore = null
        viewModel.clearPendingSession()
        coroutineScope.launch { pagerState.animateScrollToPage(tab.ordinal) }
    }

    // Cancel / Close & Discard emite `pendingClosed`. Regressamos à tab de
    // origem (se houver) — saves não fecham mais a view, portanto este effect
    // só corre em fechamentos explícitos. `pendingActivitySession` clearing
    // ainda fecha auto-magicamente porque `confirmAndSaveActivitySession`
    // não foi alterado (o pedido do utilizador era só para meals).
    val pendingFood by viewModel.pendingFoodEntry.collectAsState()
    val pendingActivity by viewModel.pendingActivityEntry.collectAsState()
    val hasPending = pendingFood != null || pendingActivity != null
    LaunchedEffect(Unit) {
        viewModel.pendingClosed.collect {
            originTab?.let { pagerState.animateScrollToPage(it.ordinal) }
            originTab = null
        }
    }
    // 2026-07-10 (Hugo) — after a successful meal save + 2s snackbar, the VM
    // clears the session and fires this. Always land on Histórico (regardless
    // of originTab), where the just-saved meal now appears. The hoisted
    // history list state (below) keeps the prior scroll position.
    LaunchedEffect(Unit) {
        viewModel.navigateToHistoryAfterSave.collect {
            originTab = null
            // Save never re-opens a dashboard popup — clear the restore token so
            // a prior popup-origin edit that ends in Save lands cleanly on History.
            dashboardBreakdownToRestore = null
            pagerState.animateScrollToPage(AppTab.HISTORY.ordinal)
        }
    }
    // Activity save path still auto-closes (unchanged behaviour) — this mirrors
    // the old `hasPending` transition just for the activity case.
    var hadActivityPending by remember { mutableStateOf(false) }
    LaunchedEffect(pendingActivity != null) {
        val nowPending = pendingActivity != null
        if (hadActivityPending && !nowPending) {
            originTab?.let { pagerState.animateScrollToPage(it.ordinal) }
            originTab = null
        }
        hadActivityPending = nowPending
    }

    LaunchedEffect(Unit) {
        viewModel.loadUserProfile(context)
    }

    Scaffold(
        bottomBar = {
            // 2026-06-18: when the IME is visible, the bottom-nav slot is
            // released to 0dp so the chat input bar (and any other tab's
            // TextField) sits flush above the keyboard. Standard Android
            // convention (Gmail / WhatsApp / Telegram / Slack all hide
            // their bottom nav while the keyboard is up). Without this
            // gate, Material3 Scaffold's innerPadding.bottom collapses to
            // max(bottomBarHeight, imeBottom) — and the ~80dp nav row
            // wins in some IME configurations, opening a dead band above
            // the keyboard equal to the nav height.
            // See [[scaffold-bottombar-ime-gate]].
            if (!WindowInsets.isImeVisible) {
                // Outer Box paints the dark background down to the screen edge (under
                // the system navigation bar). Inner Row uses a TIGHT bottom padding —
                // we want labels close to the system buttons, not floating above them
                // with a big gap. We use 4dp as the minimum breathing room and clamp
                // any reported nav inset to that, since on this app we're happy to
                // sit right on top of the system bar.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A))
                ) {
                    val navBottom = WindowInsets.navigationBars
                        .asPaddingValues()
                        .calculateBottomPadding()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 12.dp)
                            // Add only what's needed to clear the gesture pill on
                            // gesture-nav phones (S25 in gesture mode reports ~24dp);
                            // capped so 3-button nav (48dp inset) doesn't open a void.
                            .padding(bottom = navBottom.coerceAtMost(8.dp)),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                    ) {
                    AppBottomNavItem(
                        icon = Icons.Rounded.BarChart,
                        label = tr("Dashboard", userProfile.language),
                        selected = currentTab == AppTab.DASHBOARD,
                        onClick = { selectTab(AppTab.DASHBOARD) }
                    )
                    AppBottomNavItem(
                        icon = Icons.Rounded.History,
                        label = tr("History", userProfile.language),
                        selected = currentTab == AppTab.HISTORY,
                        onClick = { selectTab(AppTab.HISTORY) }
                    )
                    AppBottomNavItem(
                        icon = Icons.Rounded.DirectionsRun,
                        label = tr("Activity", userProfile.language),
                        selected = currentTab == AppTab.ACTIVITY,
                        onClick = { selectTab(AppTab.ACTIVITY) }
                    )
                    AppBottomNavItem(
                        icon = Icons.Rounded.Chat,
                        label = tr("Chat", userProfile.language),
                        selected = currentTab == AppTab.CHAT,
                        onClick = { selectTab(AppTab.CHAT) }
                    )
                    AppBottomNavItem(
                        icon = Icons.Rounded.Bookmark,
                        label = tr("Habits", userProfile.language),
                        selected = currentTab == AppTab.HABITS,
                        onClick = { selectTab(AppTab.HABITS) }
                    )
                    AppBottomNavItem(
                        icon = Icons.Rounded.Assessment,
                        label = tr("Analytics", userProfile.language),
                        selected = currentTab == AppTab.REPORT,
                        onClick = { selectTab(AppTab.REPORT) }
                    )
                    }
                }
            }
        },
        containerColor = Color(0xFF0B0F19),
        // 2026-06-18 ROUND 5b: Material3 Scaffold's default
        // contentWindowInsets is WindowInsets.systemBars, which does NOT
        // include WindowInsets.ime. With edge-to-edge enabled (this app
        // calls enableEdgeToEdge in MainActivity), windowSoftInputMode
        // "adjustResize" no longer shrinks the window — the OS draws the
        // IME on top and exposes its height as WindowInsets.ime.
        //
        // Use `union` (component-wise max) NOT `add` (component-wise sum).
        // `add` would stack the navigationBars inset on top of the IME
        // inset and produce a ~110px (≈31dp) phantom gap above the
        // keyboard, because when the IME is up the gesture-pill area
        // is already covered by the keyboard — its inset must not be
        // re-added on top. With union, innerPadding.bottom becomes
        // max(systemBars.bottom, imeBottom) = imeBottom while the
        // keyboard is open, which is exactly what we want.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 2026-06-18: imePadding() removed here. Material3 Scaffold's
                // innerPadding ALREADY incorporates the IME inset along with
                // the bottomBar height — calling .imePadding() on top of
                // .padding(innerPadding) double-consumed the keyboard inset
                // (cf. [[scaffold-imepadding-double-trap]]).
                //
                // The residual nav-height gap that survived removing imePadding
                // is now handled by the IME-gate on the bottomBar slot above
                // (line 301): when the keyboard opens, the nav row is gated
                // out → its 0dp slot lets innerPadding.bottom collapse to just
                // the IME inset → input bar sits flush above the keyboard.
                // See [[scaffold-bottombar-ime-gate]].
                .padding(innerPadding)
        ) {
            // Aurora wallpaper — pintado por trás de todos os tabs do pager.
            // Cards são opacos (#1E293B), portanto o gradient só se vê nas
            // gaps entre cards, status bar e áreas vazias. A bottom-nav fica
            // fora deste Box (paint próprio do Scaffold.bottomBar).
            Image(
                painter = painterResource(R.drawable.bg_aurora),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { it }
            ) { page ->
                when (AppTab.values()[page]) {
                    AppTab.DASHBOARD -> {
                        // Tab-edit-in-place: quando o utilizador toca no nome
                        // de uma refeição no popup Calorias do Dashboard, marcamos
                        // `originTab = DASHBOARD` (sem mexer no pager) e renderizamos
                        // `ActivityCaptureTab` NO slot do Dashboard. O bottom-nav
                        // continua com o Dashboard destacado; guardar/descartar
                        // limpa o pending, o `LaunchedEffect(hasPending)` (~linha
                        // 309) faz `animateScrollToPage(DASHBOARD.ordinal)` — no-op
                        // já que estamos aqui — e a árvore volta a renderizar o
                        // `DashboardTab` normal. Mesmo padrão que a History usa
                        // (~linha 511-512).
                        if (originTab == AppTab.DASHBOARD && hasPending) {
                            ActivityCaptureTab(viewModel = viewModel)
                        } else {
                        // Pull-to-refresh padrão Material3: dispara o force-sync e
                        // só desliga o spinner quando shLastSyncMs é bumped (= sync
                        // terminou) ou após 10 s — salvaguarda para o caso de o
                        // sync falhar e nunca actualizar o timestamp.
                        var isRefreshing by remember { mutableStateOf(false) }
                        LaunchedEffect(isRefreshing) {
                            if (!isRefreshing) return@LaunchedEffect
                            val startMs = viewModel.shLastSyncMs.value
                            viewModel.syncFromSamsungHealth(force = true)
                            withTimeoutOrNull(10_000) {
                                snapshotFlow { viewModel.shLastSyncMs.value }
                                    .first { it != startMs }
                            }
                            isRefreshing = false
                        }
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = { isRefreshing = true },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            DashboardTab(
                                foods = foods,
                                activities = activities,
                                viewModel = viewModel,
                                onMealsClick = { range ->
                                    historyFilter = range
                                    selectTab(AppTab.HISTORY)
                                },
                                onActivitiesClick = { range ->
                                    historyFilter = range
                                    selectTab(AppTab.HISTORY)
                                },
                                onEditMeal = { entry, originKind ->
                                    // Same tab-edit-in-place pattern History
                                    // uses (~line 3493 + the AppTab.HISTORY
                                    // branch below). Setting originTab =
                                    // DASHBOARD tells the pager to render
                                    // ActivityCaptureTab in the Dashboard's
                                    // slot without animating; the bottom-nav
                                    // highlight stays on Dashboard. When the
                                    // pending entry clears (save or reject),
                                    // the LaunchedEffect(hasPending) at the
                                    // top of ChatScreen restores the pager
                                    // to originTab so the user lands back on
                                    // the Dashboard.
                                    // 2026-07-10: remember which popup this edit
                                    // came from so a cancel/back re-opens it
                                    // (Save clears it via navigateToHistoryAfterSave).
                                    dashboardBreakdownToRestore = originKind
                                    originTab = AppTab.DASHBOARD
                                    viewModel.setEditingFoodEntry(entry)
                                    viewModel.setCaptureMode(CaptureMode.FOOD)
                                },
                                // Only feed the restore token back in when no
                                // edit is pending — otherwise the frame that sets
                                // the token (launch) would momentarily re-open the
                                // popup over the opening Edit Meal. On cancel/back
                                // `hasPending` is already false, so the return path
                                // still restores correctly.
                                initialBreakdown = if (hasPending) null else dashboardBreakdownToRestore,
                                onBreakdownRestored = { dashboardBreakdownToRestore = null }
                            )
                        }
                        }
                    }
                    AppTab.ACTIVITY -> ActivityCaptureTab(viewModel = viewModel)
                    AppTab.HISTORY -> {
                        // Quando o utilizador toca num item na History para editar,
                        // setamos `originTab = HISTORY` (sem mexer no pager) e
                        // renderizamos a `ActivityCaptureTab` *no slot da History*.
                        // Resultado: o highlight do bottom-nav fica em "History",
                        // o conteúdo é o formulário "Edit Meal Log". O pager nunca
                        // se move; o `LaunchedEffect(hasPending)` em cima continua
                        // a fazer reset do `originTab` quando o pending limpa,
                        // mas o `animateScrollToPage(HISTORY)` torna-se no-op.
                        if (originTab == AppTab.HISTORY && hasPending) {
                            ActivityCaptureTab(viewModel = viewModel)
                        } else {
                            HistoryTab(
                                foods = foods,
                                activities = activities,
                                viewModel = viewModel,
                                listState = historyListState,
                                initialFilter = historyFilter,
                                onFilterChange = { historyFilter = it },
                                isVisible = pagerState.currentPage == AppTab.HISTORY.ordinal,
                                onNavigateToTab = { tab, mode ->
                                    viewModel.setCaptureMode(mode)
                                    // Marca a History como tab de origem para regressar
                                    // depois de o pending entry ser gravado/rejeitado.
                                    originTab = AppTab.values()[pagerState.currentPage]
                                    // Se a destinação for Activity e estamos em History,
                                    // não mexemos no pager — o slot da History passa a
                                    // renderizar o ActivityCaptureTab graças ao branch
                                    // acima. Para qualquer outra destinação (ou se
                                    // estivermos noutra tab), animamos normalmente.
                                    if (!(tab == AppTab.ACTIVITY && pagerState.currentPage == AppTab.HISTORY.ordinal)) {
                                        coroutineScope.launch { pagerState.animateScrollToPage(tab.ordinal) }
                                    }
                                }
                            )
                        }
                    }
                    AppTab.CHAT -> ChatTab(
                        viewModel = viewModel,
                        isVisible = pagerState.currentPage == AppTab.CHAT.ordinal,
                        isSettledOnChat = pagerState.settledPage == AppTab.CHAT.ordinal &&
                            !pagerState.isScrollInProgress
                    )
                    AppTab.REPORT -> ReportTab(foods = foods, activities = activities, viewModel = viewModel)
                    AppTab.HABITS -> FoodHabitsScreen(viewModel = viewModel)
                }
            }
            // One-shot snackbar host — overlays the entire tab area so the
            // "Modelo avançado indisponível" toast floats above any active
            // tab (typically the edit-meal screen). VM emits i18n keys; we
            // resolve them here in the user's language.
            com.example.ui.components.OneShotSnackbarHost(
                oneShotSnack = viewModel.oneShotSnack,
                resolveMessage = { key -> tr(key, userProfile.language) }
            )
        }
    }
}

@Composable
fun AppBottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color = if (selected) AccentSoft else Color(0xFF94A3B8)
    val indicatorColor = if (selected) Color(0xFF1E293B) else Color.Transparent
    
    Column(
        modifier = Modifier
            .width(58.dp) // Tightened from 72.dp so all 6 nav items fit on standard phones
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(indicatorColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = color)
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// ==========================================
// TAB 1: DASHBOARD
// ==========================================
@Composable
fun DashboardTab(
    foods: List<FoodEntry>,
    activities: List<ActivityEntry>,
    viewModel: ChatViewModel,
    // 2026-06-26: a coluna "Refeições" do card "Consumo de Hoje" foi removida
    // por pedido (Opção A — redesign do dashboard). O click-handler é mantido
    // na assinatura para preservar o call site e permitir reintroduzir o
    // atalho sem mudar a API. Suprimido o warning de unused parameter.
    @Suppress("UNUSED_PARAMETER") onMealsClick: (String) -> Unit,
    onActivitiesClick: (String) -> Unit,
    // 2026-07-01: called when the user taps a meal row inside the macro
    // breakdown popup. Caller wires this to
    // `viewModel.setEditingFoodEntry(entry) + onNavigateToTab(ACTIVITY, FOOD)`,
    // the same two-call pattern History uses (ChatScreen.kt:3493-3495).
    // 2026-07-10: second arg is the popup the tap came from (CALORIES/CARBS/
    // PROTEIN/FAT) so the caller can re-open it after a cancel/back.
    onEditMeal: (FoodEntry, MacroKind?) -> Unit = { _, _ -> },
    // 2026-07-10: when non-null, the dashboard re-opens this macro popup once
    // (used to restore the popup the user came from after cancelling Edit Meal).
    initialBreakdown: MacroKind? = null,
    // Called after [initialBreakdown] has been consumed so the caller can clear
    // its restore token (one-shot; prevents re-opening on later recompositions).
    onBreakdownRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val userProfile by viewModel.userProfile.collectAsState()
    val targetSnapshots by viewModel.targetSnapshots.collectAsState()
    val bmrSnapshotsList by viewModel.bmrSnapshots.collectAsState()
    var showProfileEditDialog by remember { mutableStateOf(false) }
    // 2026-07-01: which macro bar's breakdown popup is currently open, if
    // any. Tapping any of the four bar rows (Protein / Carbs / Fat /
    // Calorias card) sets this; MacroBreakdownDialog dismisses by clearing
    // it. See plan-adding-a-new-radiant-candy.md.
    var breakdownFor by remember { mutableStateOf<MacroKind?>(null) }
    // 2026-07-10: re-open the popup the user cancelled out of. Seeds `breakdownFor`
    // exactly once (guarded on breakdownFor == null so it can't fight a fresh
    // tap), then tells the caller to clear its restore token.
    LaunchedEffect(initialBreakdown) {
        if (initialBreakdown != null && breakdownFor == null) {
            breakdownFor = initialBreakdown
            onBreakdownRestored()
        }
    }

    var dashboardPeriod by remember { mutableStateOf("Daily") }
    var selectedDate by remember { mutableStateOf(LocalDate.now(java.time.ZoneId.systemDefault())) }

    val shLastSyncMs by viewModel.shLastSyncMs.collectAsState()
    val shStatus by viewModel.shStatus.collectAsState()
    val shStepsByDate by viewModel.shStepsByDate.collectAsState()
    val dailyActivitySnapshotsList by viewModel.dailyActivitySnapshots.collectAsState()
    val shWorkouts by viewModel.shWorkouts.collectAsState()
    val shDailyActivity by viewModel.shDailyActivity.collectAsState()
    val bmrSnapshot by viewModel.bmr.collectAsState()

    val periodSteps = remember(shStepsByDate, dashboardPeriod, selectedDate) {
        when (dashboardPeriod) {
            "Daily" -> shStepsByDate[selectedDate] ?: 0L
            "Weekly" -> {
                val monday = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong())
                (0..6).sumOf { shStepsByDate[monday.plusDays(it.toLong())] ?: 0L }
            }
            else -> { // Monthly
                val firstDay = selectedDate.withDayOfMonth(1)
                val lastDay = selectedDate.withDayOfMonth(selectedDate.lengthOfMonth())
                var current = firstDay
                var sum = 0L
                while (!current.isAfter(lastDay)) {
                    sum += shStepsByDate[current] ?: 0L
                    current = current.plusDays(1)
                }
                sum
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadUserProfile(context)
        viewModel.refreshSamsungHealthStatus()
        viewModel.syncFromSamsungHealth(force = false)
    }

    val zoneId = java.time.ZoneId.systemDefault()

    // Filter list recursively based on timeline selections
    val filteredFoods = remember(foods, dashboardPeriod, selectedDate) {
        val (startMs, endMs) = when (dashboardPeriod) {
            "Daily" -> {
                val start = selectedDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = selectedDate.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
            "Weekly" -> {
                val monday = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong())
                val sunday = monday.plusDays(6)
                val start = monday.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = sunday.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
            else -> { // Monthly
                val firstDay = selectedDate.withDayOfMonth(1)
                val lastDay = selectedDate.withDayOfMonth(selectedDate.lengthOfMonth())
                val start = firstDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = lastDay.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
        }
        foods.filter { it.timestamp in startMs..endMs }
    }

    val filteredActivities = remember(activities, dashboardPeriod, selectedDate) {
        val (startMs, endMs) = when (dashboardPeriod) {
            "Daily" -> {
                val start = selectedDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = selectedDate.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
            "Weekly" -> {
                val monday = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong())
                val sunday = monday.plusDays(6)
                val start = monday.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = sunday.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
            else -> { // Monthly
                val firstDay = selectedDate.withDayOfMonth(1)
                val lastDay = selectedDate.withDayOfMonth(selectedDate.lengthOfMonth())
                val start = firstDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = lastDay.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
        }
        activities.filter { it.timestamp in startMs..endMs }
    }

    // Aggregate the period from Samsung Health's ActivitySummary (active time / active
    // kcal). These are the exact numbers SH UI shows under "Tempo ativo / Calorias de
    // atividade" — no derived formulas, no double-counting of workout kcal vs steps.
    val periodDateRange = remember(dashboardPeriod, selectedDate) {
        when (dashboardPeriod) {
            "Daily" -> selectedDate..selectedDate
            "Weekly" -> {
                val monday = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong())
                monday..monday.plusDays(6)
            }
            else -> {
                val firstDay = selectedDate.withDayOfMonth(1)
                firstDay..selectedDate.withDayOfMonth(selectedDate.lengthOfMonth())
            }
        }
    }
    val (shActiveMin, shActiveCal) = remember(shWorkouts, shDailyActivity, shStepsByDate, dailyActivitySnapshotsList, periodDateRange, shStatus) {
        if (shStatus != SamsungHealthRepository.Status.AVAILABLE) 0 to 0
        else {
            // Hybrid resolver: past days with a snapshot use the frozen value;
            // today (and any missing past day) reads the SH aggregate
            // (TOTAL_ACTIVE_CALORIES_BURNED) directly — what the SH home tile
            // shows. See [DailyActivityResolver] for the order of preference
            // and the rationale for retiring the hand-rolled steps × 0.015
            // formula on 2026-06-27.
            val aggregated = com.example.data.activity.DailyActivityResolver.aggregateOverRange(
                snapshots = dailyActivitySnapshotsList,
                workouts = shWorkouts,
                dailySummaryByDate = shDailyActivity,
                stepsByDay = shStepsByDate,
                range = periodDateRange
            )
            val zone = java.time.ZoneId.systemDefault()
            var min = 0
            for (w in shWorkouts) {
                val d = w.startTime.atZone(zone).toLocalDate()
                if (d in periodDateRange) {
                    min += java.time.Duration.between(w.startTime, w.endTime)
                        .toMinutes().toInt().coerceAtLeast(0)
                }
            }
            min to aggregated.kcal.toInt()
        }
    }
    // Distância percorrida pelo Samsung Health durante atividade ("Distance
    // while active"). Aggregation matches shActiveMin/shActiveCal — daily
    // total summed across the selected period.
    val periodDistanceMeters = remember(shDailyActivity, periodDateRange, shStatus) {
        if (shStatus != SamsungHealthRepository.Status.AVAILABLE) 0f
        else {
            var total = 0f
            for ((d, v) in shDailyActivity) {
                if (d in periodDateRange) total += v.distanceMeters
            }
            total
        }
    }

    // Manual activities entered inside the app are kept, but only when they DON'T overlap
    // any workout already recorded by Samsung Health for the same period — otherwise the
    // user would see (e.g.) both their manually-logged 30-min run and the Galaxy-Watch
    // version of the same run summed together.
    val nonOverlappingManuals = remember(filteredActivities, shWorkouts, shStatus) {
        if (shStatus != SamsungHealthRepository.Status.AVAILABLE) filteredActivities
        else {
            val intervals = shWorkouts.map { it.startTime to it.endTime }
            filteredActivities.filter { manual ->
                val mStart = java.time.Instant.ofEpochMilli(manual.timestamp)
                val mEnd = mStart.plusSeconds(manual.durationMinutes * 60L)
                intervals.none { (ws, we) -> mStart.isBefore(we) && mEnd.isAfter(ws) }
            }
        }
    }

    // Totals
    val totalCalories = filteredFoods.sumOf { it.calories }
    // Movement-only "activity calories" — the value the activity goal ring is
    // measured against. Stays free of TMB on purpose: the goal is "how much
    // movement did I do today?", not "how much energy did my body spend?".
    val totalActivityCalories = nonOverlappingManuals.sumOf { it.caloriesBurned } + shActiveCal
    // BMR/TMB summed day-by-day across the period from the versioned snapshot
    // table. See BmrResolver for the algorithm and the backwards-fill rule.
    val aggregatedBmrPeriod = remember(bmrSnapshotsList, periodDateRange) {
        com.example.data.bmr.BmrResolver.aggregateOverRange(bmrSnapshotsList, periodDateRange)
    }
    // Total calories the body actually burned in the period: TMB + activity.
    // This is the number the user sees as "Burned"; it's what makes Net =
    // Consumed - Burned reflect a real deficit instead of ignoring resting
    // metabolism (the bug that flipped some weekly/monthly views into apparent
    // surplus when the day was actually deficit).
    val totalBurnedCalories = totalActivityCalories + aggregatedBmrPeriod.kcal.toInt()
    val netCalories = totalCalories - totalBurnedCalories
    val totalProtein = filteredFoods.sumOf { it.protein }
    val totalCarbs = filteredFoods.sumOf { it.carbs }
    val totalFat = filteredFoods.sumOf { it.fat }
    // Sum of the WHO free-sugars estimate across the window's foods. Nulls
    // (legacy entries, entries whose LLM call omitted the field) are treated
    // as unknown-not-zero. When EVERY food in the window is null we render
    // the legacy single-color carbs bar; otherwise nulls contribute 0 to
    // the split, which reads as "conservative" (only known free sugars are
    // shown as light-violet, unknowns absorb into the dark-violet segment).
    val hasAnyFreeSugarsData = filteredFoods.any { it.freeSugarsG != null }
    val totalFreeSugars = filteredFoods.sumOf { effectiveFreeSugars(it) }
    // Same semantics as free-sugars above, mirrored for saturated fat. Legacy
    // rows (pre-v26) and rows whose LLM call omitted `saturatedFatG` are treated
    // as unknown-not-zero: they contribute 0 to the split so unknowns absorb into
    // the yellow-500 unsaturated segment while the yellow-200 saturated tail is
    // conservative. When every food in the window is null we fall back to the
    // legacy single-color yellow bar.
    val hasAnySatFatData = filteredFoods.any { it.saturatedFatG != null }
    val totalSatFat = filteredFoods.sumOf { (it.saturatedFatG ?: 0.0).coerceAtMost(it.fat) }
    val mealCount = filteredFoods.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App header title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                // Brand wordmark — hardcoded (não-i18n) com gradient
                // linear-gradient(180deg, #fff 40%, rgba(255,255,255,0.5) 100%)
                // pintado via TextStyle.brush (Compose 1.6+).
                val brandBrush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.White,
                        0.4f to Color.White,
                        1.0f to Color.White.copy(alpha = 0.5f)
                    )
                )
                Text(
                    text = nutriBrand(userProfile.name),
                    style = MaterialTheme.typography.headlineMedium.copy(brush = brandBrush),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = tr("Observe and log activity and meals", userProfile.language),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = AccentSoft // violeta — alinha com a paleta dos macros
                )
            }

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(AccentSoft.copy(alpha = 0.15f))
                    .clickable { showProfileEditDialog = true }
                    .testTag("profile_edit_icon_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Face,
                    contentDescription = tr("Edit Health Profile", userProfile.language),
                    tint = AccentSoft,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Period segmented visual picker
        // Swipe horizontal em cima do bloco cicla entre Daily/Weekly/Monthly
        // sem wrap (consome o gesto, por isso o HorizontalPager pai não muda
        // de tab enquanto o utilizador está em cima do picker).
        val dashboardPeriods = remember { listOf("Daily", "Weekly", "Monthly") }
        com.example.ui.components.SegmentedPillRow(
            options = dashboardPeriods,
            selected = dashboardPeriod,
            onSelect = { dashboardPeriod = it },
            labelFor = { tr(it, userProfile.language) },
            rowModifier = Modifier.horizontalSwipe(
                onSwipeLeft = {
                    val i = dashboardPeriods.indexOf(dashboardPeriod)
                    if (i > 0) dashboardPeriod = dashboardPeriods[i - 1]
                },
                onSwipeRight = {
                    val i = dashboardPeriods.indexOf(dashboardPeriod)
                    if (i in 0 until dashboardPeriods.lastIndex) {
                        dashboardPeriod = dashboardPeriods[i + 1]
                    }
                }
            )
        )

        // Date selector picker
        // Swipe horizontal em cima da barra avança/recua o período
        // (1 dia / 1 semana / 1 mês conforme dashboardPeriod). Reutiliza a
        // lógica que os IconButtons internos da DateSelectorBar já usam.
        Box(
            modifier = Modifier.horizontalSwipe(
                onSwipeLeft = {
                    selectedDate = when (dashboardPeriod) {
                        "Daily" -> selectedDate.plusDays(1)
                        "Weekly" -> selectedDate.plusWeeks(1)
                        else -> selectedDate.plusMonths(1)
                    }
                },
                onSwipeRight = {
                    selectedDate = when (dashboardPeriod) {
                        "Daily" -> selectedDate.minusDays(1)
                        "Weekly" -> selectedDate.minusWeeks(1)
                        else -> selectedDate.minusMonths(1)
                    }
                }
            )
        ) {
            DateSelectorBar(
                selectedDate = selectedDate,
                period = dashboardPeriod,
                language = userProfile.language,
                onDateChanged = { selectedDate = it }
            )
        }

        // Current status Card
        // Period targets are now summed day-by-day from the historical
        // target_snapshots — when the user changes a target mid-week/month, the
        // older days continue to count against the value that was prescribed
        // back then. The old `userProfile.targetX × daysMultiplier` shorthand
        // would silently re-anchor every past day to today's number.
        val aggregatedTargets = remember(targetSnapshots, periodDateRange) {
            com.example.data.targets.TargetResolver.aggregateOverRange(targetSnapshots, periodDateRange)
        }
        // Fallback for the rare race where `loadUserProfile` hasn't seeded a
        // snapshot yet — keep the old multiplier maths so the UI still renders.
        val daysMultiplier = when (dashboardPeriod) {
            "Daily" -> 1.0
            "Weekly" -> 7.0
            else -> 30.0
        }
        val calorieTargetGoal = if (aggregatedTargets.days > 0)
            aggregatedTargets.kcal.toInt()
        else (userProfile.targetKcal * daysMultiplier).toInt()

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { breakdownFor = MacroKind.CALORIES },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = if (dashboardPeriod == "Daily") tr("Today's Consumption", userProfile.language) else tr("Logged Range Intake", userProfile.language),
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                // The headline used to read "{netCalories} kcal / {target} kcal"
                // which conflated two scales — Net is Consumed − Burned, the
                // target is "kcal a consumir". Now Consumed/Burned/Net live in
                // the row below as equal peers, the target shows up as a small
                // grey legend, and the bar tracks Consumed against the goal
                // with traffic-light thresholds.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(tr("Consumed", userProfile.language), color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelMedium)
                        Text("$totalCalories", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(tr("Burned", userProfile.language), color = Color(0xFF60A5FA), style = MaterialTheme.typography.labelMedium)
                        // "Burned" is the user-facing total: TMB + activity. The
                        // standalone activity-only number is shown in the activity
                        // goal card further down.
                        Text("-$totalBurnedCalories", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(tr("Net Calories", userProfile.language), color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelMedium)
                        Text("$netCalories", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = if (netCalories <= calorieTargetGoal) AccentSoft else Color(0xFFF87171))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                // Single solid amber fill of Consumed/Goal — clamped to 100% so
                // an over-limit day still renders as "full bar", same look as
                // before the 2026-06 traffic-light experiment. The split-color
                // version (green ≤80% / amber 80–100% / red >100% with overflow
                // domain) lives in git history; see memory
                // dashboard-traffic-light-bar-pattern.md for the rationale.
                val consumedClamped = totalCalories.coerceAtLeast(0)
                val fillFraction = if (calorieTargetGoal > 0)
                    (consumedClamped.toFloat() / calorieTargetGoal).coerceIn(0f, 1f)
                else 0f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF334155))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fillFraction)
                            .fillMaxHeight()
                            .background(Color(0xFFEAB308))
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                // Legend row: left = % of goal already consumed (mirrors the
                // yellow bar's fillFraction so 100% == full bar, 0% == empty /
                // no goal set); right = the absolute goal in kcal. Same style
                // and color so the row reads as one legend.
                val consumedPct = (fillFraction * 100f).roundToInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$consumedPct%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B)
                    )
                    Text(
                        text = "${String.format(java.util.Locale.US, "%,d", calorieTargetGoal)} kcal",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B)
                    )
                }
                
                // Macros (Protein/Carbs/Fat) and Meals count were intentionally
                // removed 2026-06-26: macros are already shown immediately below
                // as full progress bars against their targets — repeating the
                // raw grams here was redundant and made the user scan twice.
                // Refeições foi removido para optimizar espaço; a contagem fica
                // implícita via History tab. See memory dashboard-redesign-2026-06-26.
            }
        }

        // Daily average over range (Satisfies visual instructions from screenshot)
        if (dashboardPeriod != "Daily") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = tr("Daily average over range", userProfile.language),
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val days = if (dashboardPeriod == "Weekly") 7.0 else 30.0
                    val avgCalories = (totalCalories / days).toInt()
                    val avgProtein = totalProtein / days
                    val avgCarbs = totalCarbs / days
                    val avgFat = totalFat / days

                    Text(
                        text = "$avgCalories kcal",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "P ${String.format("%.1f", avgProtein)}g · C ${String.format("%.1f", avgCarbs)}g · F ${String.format("%.1f", avgFat)}g",
                        color = AccentSoft,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Segment block: Macros goal status gauges
        // Movido para antes do Activity Card por pedido — fica colado aos
        // macros já mostrados em "Consumo de Hoje" em vez de no fim do scroll.
        // Sem header — as 3 caixas falam por si.
        //
        // 2026-06-26: títulos passaram a "Macro (NN%)" + denominador à direita,
        // em vez de "Meta de / Limite de Macro (Yg) … X / Y (NN%)". A cor da
        // barra continua a comunicar a semântica (laranja=alvo, roxo=teto,
        // amarelo=teto). Ver memory dashboard-redesign-2026-06-26.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Protein progress
            val proteinTarget = if (aggregatedTargets.days > 0)
                aggregatedTargets.protein
            else userProfile.targetProtein * daysMultiplier
            Box(modifier = Modifier.clickable { breakdownFor = MacroKind.PROTEIN }) {
                MacroProgressIndicator(
                    name = tr("Protein", userProfile.language),
                    actual = totalProtein,
                    target = proteinTarget,
                    color = Color(0xFFF97316) // orange
                )
            }
            // Carbs progress — split-color when any food in the window has a
            // free-sugars estimate. Dark violet = carbs from intact-matrix
            // foods (whole fruit, integrais, leguminosas, proteins' starches,
            // dairy); light violet = WHO free sugars (added + honey/syrups +
            // fruit juices). Reads left-to-right: the further the light tail
            // extends, the more free-sugar in the window's carbs. Falls back
            // to the legacy single-color rendering when no entry carries the
            // estimate (all-legacy day).
            val carbsTarget = if (aggregatedTargets.days > 0)
                aggregatedTargets.carbs
            else userProfile.targetCarbs * daysMultiplier
            Box(modifier = Modifier.clickable { breakdownFor = MacroKind.CARBS }) {
                if (hasAnyFreeSugarsData) {
                    MacroProgressIndicatorSplit(
                        name = tr("Carbohydrates", userProfile.language),
                        actualPrimary = (totalCarbs - totalFreeSugars).coerceAtLeast(0.0),
                        actualSecondary = totalFreeSugars.coerceAtMost(totalCarbs),
                        target = carbsTarget,
                        colorPrimary = Color(0xFF8B5CF6),    // violet-500 (unchanged)
                        colorSecondary = Color(0xFFC4B5FD)   // violet-300 (free sugars tail)
                    )
                } else {
                    MacroProgressIndicator(
                        name = tr("Carbohydrates", userProfile.language),
                        actual = totalCarbs,
                        target = carbsTarget,
                        color = Color(0xFF8B5CF6) // violet
                    )
                }
            }
            // Fat progress — split-color when any food in the window has a
            // saturated-fat estimate. Yellow-500 = unsaturated (from olive oil,
            // nuts, seeds, avocado, fish, lean meats); yellow-200 pale tail =
            // saturated (butter, cheese, cream, bacon, tropical oils). Mirrors
            // the carbs "pale tail is the less-desirable subset" convention.
            // Falls back to the legacy single-color rendering when no entry
            // carries the estimate (all-legacy day).
            val fatTarget = if (aggregatedTargets.days > 0)
                aggregatedTargets.fat
            else userProfile.targetFat * daysMultiplier
            Box(modifier = Modifier.clickable { breakdownFor = MacroKind.FAT }) {
                if (hasAnySatFatData) {
                    MacroProgressIndicatorSplit(
                        name = tr("Fat", userProfile.language),
                        actualPrimary = (totalFat - totalSatFat).coerceAtLeast(0.0),
                        actualSecondary = totalSatFat.coerceAtMost(totalFat),
                        target = fatTarget,
                        colorPrimary = Color(0xFFEAB308),    // yellow-500 (unchanged)
                        colorSecondary = Color(0xFFFDE68A)   // yellow-200 (sat fat tail)
                    )
                } else {
                    MacroProgressIndicator(
                        name = tr("Fat", userProfile.language),
                        actual = totalFat,
                        target = fatTarget,
                        color = Color(0xFFEAB308) // yellow
                    )
                }
            }
        }

        // B. Dedicated Activity Card on the Dashboard
        val totalActivityMinutes = nonOverlappingManuals.sumOf { it.durationMinutes } + shActiveMin
        // Period-scaled targets summed day-by-day from target_snapshots — see the
        // comment on aggregatedTargets above. Fallback to the legacy multiplier
        // path keeps Weekly/Monthly rings rendering even before snapshots load.
        val periodTargetSteps = if (aggregatedTargets.days > 0)
            aggregatedTargets.steps.toInt()
        else (userProfile.targetSteps * daysMultiplier).toInt()
        val periodTargetActiveMinutes = if (aggregatedTargets.days > 0)
            aggregatedTargets.activeMinutes.toInt()
        else (userProfile.targetActiveMinutes * daysMultiplier).toInt()
        val periodTargetActivityCalories = if (aggregatedTargets.days > 0)
            aggregatedTargets.activityCalories.toInt()
        else (userProfile.targetActivityCalories * daysMultiplier).toInt()
        // Tap only navigates to History when there's something user-added to
        // see there. Samsung Health workouts (`shRecordId != null`) live
        // inside the dashboard's aggregated numbers and don't appear as
        // standalone rows in the Activities history list.
        val hasManualActivities = filteredActivities.any { it.shRecordId == null }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasManualActivities) { onActivitiesClick(dashboardPeriod) }
                .testTag("dashboard_activity_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DirectionsRun,
                            contentDescription = "Activity Icon",
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = tr("Activity Metrics", userProfile.language),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (shStatus == SamsungHealthRepository.Status.NEEDS_PERMISSION) {
                            TextButton(
                                onClick = { viewModel.requestSamsungHealthPermissions(context as android.app.Activity) },
                                modifier = Modifier.testTag("sh_connect_button")
                            ) {
                                Text(
                                    text = tr("Connect S-Health", userProfile.language),
                                    color = AccentSoft,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (shStatus == SamsungHealthRepository.Status.AVAILABLE) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "S-Health Active",
                                tint = AccentSoft,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "S-Health Active",
                                color = AccentSoft,
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else {
                            Text(
                                text = "S-Health Offline",
                                color = Color(0xFF64748B),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        IconButton(
                            onClick = { viewModel.manualRefreshSamsungHealth(context as android.app.Activity) },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("sh_manual_sync_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Manual Sync",
                                tint = Color(0xFF60A5FA),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    // Top alignment so the heart card aligns with the first metric row
                    // (Steps count) instead of being centered against the whole column.
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1.2f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4ADE80))
                            )
                            Column {
                                Text(
                                    text = tr("Steps count", userProfile.language),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                                Text(
                                    text = String.format(
                                        java.util.Locale.US,
                                        "%,d / %,d steps",
                                        periodSteps,
                                        periodTargetSteps
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                // Distance pulled from Samsung Health's
                                // ActivitySummaryType.TOTAL_DISTANCE, mesmo
                                // estilo cinza pequeno do TMB para sinalizar
                                // que é metadata sobre a métrica de cima.
                                if (periodDistanceMeters > 0f) {
                                    Text(
                                        text = String.format(
                                            java.util.Locale("pt", "PT"),
                                            "%.2f km",
                                            periodDistanceMeters / 1000f
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF64748B)
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF60A5FA))
                            )
                            Column {
                                Text(
                                    text = tr("Active Minutes", userProfile.language),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                                Text(
                                    text = "$totalActivityMinutes / $periodTargetActiveMinutes mins",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFEC4899))
                            )
                            Column {
                                Text(
                                    text = tr("Calories Burned", userProfile.language),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                                // Activity-only burn here on purpose: this is the
                                // movement goal ring (peer of Steps and Active
                                // Minutes), measured against targetActivityCalories
                                // which is also a movement target. TMB belongs in
                                // the headline "Burned" total above, not in the
                                // activity goal — adding it here would silently
                                // pad the goal and the user could "complete" it
                                // by sleeping.
                                Text(
                                    text = "$totalActivityCalories / $periodTargetActivityCalories kcal",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(0.8f)
                            .aspectRatio(1f)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        HeartProgressCard(
                            steps = periodSteps.toInt(),
                            targetSteps = periodTargetSteps,
                            activeMins = totalActivityMinutes,
                            targetMins = periodTargetActiveMinutes,
                            activityCals = totalActivityCalories,
                            targetCals = periodTargetActivityCalories,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Footer row: TMB on the left, "Last synced" on the right. Both
                // labelSmall in the same muted grey so they read as one strip of
                // metadata about where the numbers above came from. TMB is hidden
                // when neither Samsung Health nor a manual override has a value;
                // Last synced is hidden when SH is offline.
                val showBmr = bmrSnapshot != null
                val showLastSync = shStatus == SamsungHealthRepository.Status.AVAILABLE && shLastSyncMs > 0L
                if (showBmr || showLastSync) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = bmrSnapshot?.let { "${tr("TMB", userProfile.language)} ${it.value} kcal" } ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B),
                            modifier = Modifier.weight(1f)
                        )
                        if (showLastSync) {
                            Text(
                                text = "Last synced: " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(shLastSyncMs)),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF64748B),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }
        }

        // CUSTOM BAR CHART: Calories per day over past 8 days (rolling window
        // anchored on selectedDate). 8 dias em vez de 7 para incluir o
        // mesmo-dia-da-semana-anterior — útil para comparar p.ex. segundas a
        // segundas. Independente do toggle Day/Week/Month (Hugo, 2026-06-15).
        Column {
            Text(
                text = tr("Calories Consumed (Past 8 Days)", userProfile.language),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val dailyCalories = remember(foods, selectedDate, userProfile.language) {
                        val zoneId = java.time.ZoneId.systemDefault()
                        val locale = when (userProfile.language) {
                            "pt" -> java.util.Locale("pt", "PT")
                            "gu" -> java.util.Locale("gu", "IN")
                            else -> java.util.Locale.ENGLISH
                        }
                        // EEE → "Seg" / "Mon" / "સોમ" (3-letter, locale-aware)
                        val dowFmt = java.time.format.DateTimeFormatter.ofPattern("EEE", locale)
                        val dateFmt = SimpleDateFormat("dd/MM", Locale.US)
                        val selectedMs = selectedDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                        // Janela rolling de 8 dias: D-7, D-6, ..., D-1, D (selectedDate à direita).
                        (0..7).reversed().map { offset ->
                            val targetDay = selectedDate.minusDays(offset.toLong())
                            val targetMs = selectedMs - offset * 24L * 60 * 60 * 1000
                            val dowLabel = targetDay.format(dowFmt).trimEnd('.').replaceFirstChar { it.uppercase(locale) }
                            val dateLabel = dateFmt.format(Date(targetMs))
                            val daySum = foods.filter { isSameDay(it.timestamp, targetMs) }.sumOf { it.calories }
                            Triple(dowLabel, dateLabel, daySum)
                        }
                    }

                    val maxVal = (dailyCalories.maxOfOrNull { it.third } ?: 0).coerceAtLeast(1)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        dailyCalories.forEach { (dow, dateLabel, cals) ->
                            val fraction = cals.toFloat() / maxVal
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (cals > 0) {
                                    Text(
                                        text = "$cals",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = AccentSoft
                                    )
                                } else {
                                    Text("", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .width(12.dp)
                                        .height(85.dp)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(Color(0xFF334155))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(fraction.coerceIn(0f, 1f))
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(AccentSoft, AccentPrimary)
                                                )
                                            )
                                            .align(Alignment.BottomStart)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = dow,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                    color = Color(0xFFCBD5E1)
                                )
                                Text(
                                    text = dateLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Target-history audit dialog. Opened from inside the profile edit
        // dialog via "Ver Histórico de Metas" so the user can reconcile a
        // surprising past report against the timeline of target changes.
        var showTargetHistoryDialog by remember { mutableStateOf(false) }
        if (showTargetHistoryDialog) {
            TargetHistoryDialog(
                snapshots = targetSnapshots,
                language = userProfile.language,
                onDismiss = { showTargetHistoryDialog = false }
            )
        }

        // Profile Edit Dialog
        if (showProfileEditDialog) {
            Dialog(onDismissRequest = { showProfileEditDialog = false }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp, horizontal = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Header
                        Text(
                            text = tr("Edit Health Profile", userProfile.language),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr("Update client parameters and customize nutritionist-prescribed metabolic targets.", userProfile.language),
                            color = Color(0xFF94A3B8),
                            style = MaterialTheme.typography.bodySmall
                        )

                        Divider(color = Color(0xFF334155))

                        // Name
                        var editName by remember { mutableStateOf(userProfile.name) }
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text(tr("Client Name", userProfile.language)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentSoft,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedLabelColor = AccentSoft,
                                unfocusedLabelColor = Color(0xFF94A3B8)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("edit_profile_name")
                        )

                        // 2026-06-29 — Frente 1: gender / birthYear / activityLevel
                        // are now editable. Previously hard-baked to whatever the
                        // app was first installed with (Madhvi defaults: Female,
                        // 1980, Average daily activity). Hugo uses the same APK on
                        // the Pixel and was stuck with the Madhvi defaults because
                        // there was no UI path to change them — the system prompt
                        // then told the LLM the wrong gender / age every turn.
                        //
                        // Gender — dropdown with three options. Stored as String
                        // because the rest of the codebase (system-prompt
                        // template, BMR formulas) reads it as a free-form label.
                        var editGender by remember { mutableStateOf(userProfile.gender) }
                        var genderMenuOpen by remember { mutableStateOf(false) }
                        val genderOptions = listOf("Male", "Female", "Other")
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = editGender,
                                onValueChange = {},
                                label = { Text(tr("Gender", userProfile.language)) },
                                singleLine = true,
                                readOnly = true,
                                trailingIcon = {
                                    Icon(
                                        Icons.Rounded.ArrowDropDown,
                                        contentDescription = null,
                                        tint = AccentSoft,
                                        modifier = Modifier.clickable { genderMenuOpen = true }
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = AccentSoft,
                                    unfocusedBorderColor = Color(0xFF334155),
                                    focusedLabelColor = AccentSoft,
                                    unfocusedLabelColor = Color(0xFF94A3B8)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { genderMenuOpen = true }
                                    .testTag("edit_profile_gender")
                            )
                            DropdownMenu(
                                expanded = genderMenuOpen,
                                onDismissRequest = { genderMenuOpen = false },
                                modifier = Modifier.background(Color(0xFF1E293B))
                            ) {
                                genderOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option, color = Color.White) },
                                        onClick = {
                                            editGender = option
                                            genderMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }

                        // Birth year — plain numeric TextField. Validation at
                        // save time clamps to [1900, currentYear] so a typo
                        // like "190" or "20256" can't poison the persona.
                        var editBirthYear by remember { mutableStateOf(userProfile.birthYear.toString()) }
                        OutlinedTextField(
                            value = editBirthYear,
                            onValueChange = { v -> editBirthYear = v.filter { it.isDigit() }.take(4) },
                            label = { Text(tr("Birth Year", userProfile.language)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentSoft,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedLabelColor = AccentSoft,
                                unfocusedLabelColor = Color(0xFF94A3B8)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("edit_profile_birth_year")
                        )

                        // Activity level — dropdown with the five canonical
                        // labels the BMR-to-TDEE multiplier path expects.
                        // Keep the strings stable (English) because some
                        // downstream callers compare them by equals().
                        var editActivity by remember { mutableStateOf(userProfile.activityLevel) }
                        var activityMenuOpen by remember { mutableStateOf(false) }
                        val activityOptions = listOf(
                            "Sedentary",
                            "Light daily activity",
                            "Average daily activity",
                            "Active",
                            "Very active",
                        )
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = editActivity,
                                onValueChange = {},
                                label = { Text(tr("Activity Level", userProfile.language)) },
                                singleLine = true,
                                readOnly = true,
                                trailingIcon = {
                                    Icon(
                                        Icons.Rounded.ArrowDropDown,
                                        contentDescription = null,
                                        tint = AccentSoft,
                                        modifier = Modifier.clickable { activityMenuOpen = true }
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = AccentSoft,
                                    unfocusedBorderColor = Color(0xFF334155),
                                    focusedLabelColor = AccentSoft,
                                    unfocusedLabelColor = Color(0xFF94A3B8)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { activityMenuOpen = true }
                                    .testTag("edit_profile_activity")
                            )
                            DropdownMenu(
                                expanded = activityMenuOpen,
                                onDismissRequest = { activityMenuOpen = false },
                                modifier = Modifier.background(Color(0xFF1E293B))
                            ) {
                                activityOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option, color = Color.White) },
                                        onClick = {
                                            editActivity = option
                                            activityMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }

                        Divider(color = Color(0xFF334155))
                        Text(
                            text = tr("Prescription Targets", userProfile.language),
                            color = AccentSoft,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )

                        // Calorie target
                        var editKcal by remember { mutableStateOf(userProfile.targetKcal.toString()) }
                        OutlinedTextField(
                            value = editKcal,
                            onValueChange = { editKcal = it },
                            label = { Text(tr("Daily Energy Target (kcal)", userProfile.language)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentSoft,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedLabelColor = AccentSoft,
                                unfocusedLabelColor = Color(0xFF94A3B8)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("edit_profile_kcal")
                        )

                        // Protein Target
                        var editProtein by remember { mutableStateOf(userProfile.targetProtein.toString()) }
                        OutlinedTextField(
                            value = editProtein,
                            onValueChange = { editProtein = it },
                            label = { Text(tr("Protein Target (g)", userProfile.language)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentSoft,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedLabelColor = AccentSoft,
                                unfocusedLabelColor = Color(0xFF94A3B8)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("edit_profile_protein")
                        )

                        // Carbs target
                        var editCarbs by remember { mutableStateOf(userProfile.targetCarbs.toString()) }
                        OutlinedTextField(
                            value = editCarbs,
                            onValueChange = { editCarbs = it },
                            label = { Text(tr("Carbohydrates Limit (g)", userProfile.language)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentSoft,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedLabelColor = AccentSoft,
                                unfocusedLabelColor = Color(0xFF94A3B8)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("edit_profile_carbs")
                        )

                        // Fats Cap
                        var editFat by remember { mutableStateOf(userProfile.targetFat.toString()) }
                        OutlinedTextField(
                            value = editFat,
                            onValueChange = { editFat = it },
                            label = { Text(tr("Fats Cap (g)", userProfile.language)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentSoft,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedLabelColor = AccentSoft,
                                unfocusedLabelColor = Color(0xFF94A3B8)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("edit_profile_fat")
                        )

                        // Daily Steps Target
                        var editTargetSteps by remember { mutableStateOf(userProfile.targetSteps.toString()) }
                        OutlinedTextField(
                            value = editTargetSteps,
                            onValueChange = { editTargetSteps = it },
                            label = { Text(tr("Daily Steps Target", userProfile.language)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentSoft,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedLabelColor = AccentSoft,
                                unfocusedLabelColor = Color(0xFF94A3B8)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("edit_profile_target_steps")
                        )

                        // Daily Active Minutes Target
                        var editTargetActiveMins by remember { mutableStateOf(userProfile.targetActiveMinutes.toString()) }
                        OutlinedTextField(
                            value = editTargetActiveMins,
                            onValueChange = { editTargetActiveMins = it },
                            label = { Text(tr("Daily Active Minutes Target", userProfile.language)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentSoft,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedLabelColor = AccentSoft,
                                unfocusedLabelColor = Color(0xFF94A3B8)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("edit_profile_target_active_minutes")
                        )

                        // Daily Activity Calories Target
                        var editTargetActivityCals by remember { mutableStateOf(userProfile.targetActivityCalories.toString()) }
                        OutlinedTextField(
                            value = editTargetActivityCals,
                            onValueChange = { editTargetActivityCals = it },
                            label = { Text(tr("Daily Activity Calories Target", userProfile.language)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentSoft,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedLabelColor = AccentSoft,
                                unfocusedLabelColor = Color(0xFF94A3B8)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("edit_profile_target_activity_calories")
                        )

                        // Manual BMR/TMB override. Blank field = "no manual value" (0
                        // in storage). Samsung Health BodyComposition takes precedence
                        // when present, so this is only surfaced on the dashboard when
                        // SH has nothing or wasn't granted BODY_COMPOSITION.
                        var editBmr by remember {
                            mutableStateOf(if (userProfile.manualBmr > 0) userProfile.manualBmr.toString() else "")
                        }
                        OutlinedTextField(
                            value = editBmr,
                            onValueChange = { input -> editBmr = input.filter { it.isDigit() } },
                            label = { Text(tr("Basal Metabolic Rate (kcal)", userProfile.language)) },
                            placeholder = {
                                Text(
                                    tr("Auto from Samsung Health if available", userProfile.language),
                                    color = Color(0xFF64748B)
                                )
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentSoft,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedLabelColor = AccentSoft,
                                unfocusedLabelColor = Color(0xFF94A3B8)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("edit_profile_manual_bmr")
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        
                        // Application Language
                        var editLanguage by remember { mutableStateOf(userProfile.language) }
                        var langExpanded by remember { mutableStateOf(false) }
                        val languages = mapOf("en" to "English", "pt-PT" to "Portuguese (Portugal)", "gu" to "Gujarati")
                        
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = languages[editLanguage] ?: editLanguage,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(tr("App Language", userProfile.language)) },
                                trailingIcon = {
                                    IconButton(onClick = { langExpanded = !langExpanded }) {
                                        Icon(
                                            imageVector = Icons.Rounded.ArrowDropDown,
                                            contentDescription = tr("Select Language", userProfile.language),
                                            tint = Color.White
                                        )
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = AccentSoft,
                                    unfocusedBorderColor = Color(0xFF334155),
                                    focusedLabelColor = AccentSoft,
                                    unfocusedLabelColor = Color(0xFF94A3B8)
                                ),
                                modifier = Modifier.fillMaxWidth().clickable { langExpanded = true }
                            )
                            DropdownMenu(
                                expanded = langExpanded,
                                onDismissRequest = { langExpanded = false },
                                modifier = Modifier.background(Color(0xFF1E293B))
                            ) {
                                languages.forEach { (code, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name, color = Color.White) },
                                        onClick = {
                                            editLanguage = code
                                            langExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Hint about historical targets — kept short so the
                        // dialog doesn't grow too tall on Daily/Weekly screens.
                        Text(
                            text = tr(
                                "Ao gravar, a meta passa a vigorar a partir de hoje. Reports passados continuam a usar a meta que estava prescrita nessa altura.",
                                userProfile.language
                            ),
                            color = Color(0xFF64748B),
                            style = MaterialTheme.typography.labelSmall
                        )
                        OutlinedButton(
                            onClick = {
                                showProfileEditDialog = false
                                showTargetHistoryDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFBBF24))
                        ) {
                            Text(tr("Ver Histórico de Metas", userProfile.language))
                        }

                        // Bloco H — entry point into the proactive-notification
                        // settings sheet. Lives next to the other "secondary
                        // action" rows so it stays out of the way of the
                        // Save/Cancel CTA at the bottom.
                        var showNotificationSettings by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = { showNotificationSettings = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                tr("notification_preferences_button", userProfile.language),
                                color = AccentSoft
                            )
                        }
                        if (showNotificationSettings) {
                            NotificationSettingsScreen(
                                onDismiss = { showNotificationSettings = false }
                            )
                        }

                        // Controls Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showProfileEditDialog = false },
                                border = BorderStroke(1.dp, Color(0xFFEF4444)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(tr("Cancel", userProfile.language))
                            }

                            Button(
                                // Button click Action
                                onClick = {
                                    val finalKcal = editKcal.toIntOrNull() ?: 1800
                                    val finalProtein = editProtein.toDoubleOrNull() ?: 85.0
                                    val finalCarbs = editCarbs.toDoubleOrNull() ?: 220.0
                                    val finalFat = editFat.toDoubleOrNull() ?: 60.0
                                    val finalTargetSteps = editTargetSteps.toIntOrNull() ?: 6000
                                    val finalTargetActiveMins = editTargetActiveMins.toIntOrNull() ?: 90
                                    val finalTargetActivityCals = editTargetActivityCals.toIntOrNull() ?: 500
                                    // Manual BMR: empty field or non-positive → unset (0).
                                    // Stamp the save-time only when the value actually
                                    // changed, so unrelated saves don't reset the "last
                                    // updated" label shown next to the manual BMR.
                                    val finalManualBmr = editBmr.toIntOrNull()?.takeIf { it > 0 } ?: 0
                                    val finalManualBmrSavedAtMs = when {
                                        finalManualBmr == 0 -> 0L
                                        finalManualBmr != userProfile.manualBmr -> System.currentTimeMillis()
                                        else -> userProfile.manualBmrSavedAtMs
                                    }

                                    // 2026-06-29 — Frente 1: persist edited
                                    // gender / birthYear / activityLevel
                                    // instead of the maintained defaults.
                                    // birthYear is clamped to [1900, current].
                                    val parsedBirthYear = editBirthYear.toIntOrNull()
                                    val currentYear = java.time.LocalDate.now().year
                                    val finalBirthYear = (parsedBirthYear ?: userProfile.birthYear)
                                        .coerceIn(1900, currentYear)

                                    viewModel.saveUserProfile(
                                        context = context,
                                        profile = UserProfile(
                                            name = editName.trim().ifBlank { "Humano" },
                                            gender = editGender,
                                            birthYear = finalBirthYear,
                                            activityLevel = editActivity,
                                            targetKcal = finalKcal,
                                            targetProtein = finalProtein,
                                            targetCarbs = finalCarbs,
                                            targetFat = finalFat,
                                            targetSteps = finalTargetSteps,
                                            targetActiveMinutes = finalTargetActiveMins,
                                            targetActivityCalories = finalTargetActivityCals,
                                            manualBmr = finalManualBmr,
                                            manualBmrSavedAtMs = finalManualBmrSavedAtMs,
                                            language = editLanguage
                                        )
                                    )
                                    showProfileEditDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                                modifier = Modifier.weight(1f).testTag("save_profile_button")
                            ) {
                                Text(tr("Save Profile", userProfile.language), color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // 2026-07-01: macro breakdown popup. Tapping any of the 4 bar rows
        // above sets `breakdownFor`; dismissing the dialog (outside-tap, X,
        // back) clears it. Rendered inside the outer Column so the local
        // `aggregatedTargets`/`daysMultiplier`/`calorieTargetGoal` are in
        // scope — Dialog is a portal composable, so placement inside the
        // scroll container doesn't affect layout. See
        // plan-adding-a-new-radiant-candy.md for the honesty invariants.
        breakdownFor?.let { kind ->
            val proteinTarget = if (aggregatedTargets.days > 0)
                aggregatedTargets.protein
            else userProfile.targetProtein * daysMultiplier
            val carbsTarget = if (aggregatedTargets.days > 0)
                aggregatedTargets.carbs
            else userProfile.targetCarbs * daysMultiplier
            val fatTarget = if (aggregatedTargets.days > 0)
                aggregatedTargets.fat
            else userProfile.targetFat * daysMultiplier
            MacroBreakdownDialog(
                kind = kind,
                foods = filteredFoods,
                nonOverlappingManuals = nonOverlappingManuals,
                shWorkouts = shWorkouts,
                shAvailable = shStatus == com.example.data.health.SamsungHealthRepository.Status.AVAILABLE,
                periodDateRange = periodDateRange,
                zoneId = java.time.ZoneId.systemDefault(),
                target = when (kind) {
                    MacroKind.PROTEIN -> proteinTarget
                    MacroKind.CARBS -> carbsTarget
                    MacroKind.FAT -> fatTarget
                    MacroKind.CALORIES -> calorieTargetGoal.toDouble()
                },
                totalCarbs = totalCarbs,
                totalFreeSugars = totalFreeSugars,
                hasAnyFreeSugarsData = hasAnyFreeSugarsData,
                totalProtein = totalProtein,
                totalFat = totalFat,
                totalSatFat = totalSatFat,
                hasAnySatFatData = hasAnySatFatData,
                totalMealsKcal = totalCalories,
                calorieTargetGoal = calorieTargetGoal,
                language = userProfile.language,
                onEditMeal = { entryId ->
                    filteredFoods.firstOrNull { it.id == entryId }?.let { food ->
                        breakdownFor = null
                        // Pass the popup kind so the caller can re-open it if the
                        // user cancels/backs out of Edit Meal.
                        onEditMeal(food, kind)
                    }
                },
                onDismiss = { breakdownFor = null }
            )
        }
    }
}

@Composable
fun MacroProgressIndicator(
    name: String,
    actual: Double,
    target: Double,
    color: Color
) {
    // Single-segment look: fill clamped to 100% even when actual > target, so
    // an over-limit day reads as "full bar" instead of stretching the domain.
    // The header text "X.Xg / Yg (NN%)" still surfaces the over-limit state
    // numerically. The split-color overflow variant lives in git history; see
    // memory dashboard-traffic-light-bar-pattern.md for context.
    //
    // 2026-06-26: layout reordenado por pedido de UX. Antes era
    //   "Meta de Proteína (130g)        44.9g / 130g (34%)"
    // Agora é
    //   "Proteína (34%)                  44.9g / 130g"
    // — verbo de estado primeiro + percentagem colada ao título, denominador
    // limpo à direita. A cor da barra (laranja=alvo, roxo=teto, amarelo=teto)
    // chega para distinguir "atingir" vs "não ultrapassar".
    val percentage = if (target > 0.0) ((actual / target) * 100).toInt() else 0
    val baseEnd = if (target > 0.0)
        (actual / target).toFloat().coerceIn(0f, 1f)
    else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$name ($percentage%)",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${String.format("%.1f", actual)}g / ${target.toInt()}g",
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF334155))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(baseEnd)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }
    }
}

/**
 * Two-segment variant of [MacroProgressIndicator] used ONLY for the carbs
 * bar on the dashboard. The filled portion of the bar is split into:
 *  - [colorPrimary] for [actualPrimary] grams (dark violet — carbs from
 *    intact-matrix foods: whole fruit, whole grains, leguminosas, dairy),
 *  - [colorSecondary] for [actualSecondary] grams (light violet — WHO
 *    "free sugars": added sugars + sugars in honey/syrups/fruit juices).
 *
 * Layout: primary on the LEFT, secondary on the RIGHT. This ordering makes
 * the free-sugar tail read as "the more it extends rightward, the more
 * free sugar in today's carbs" — no red/green semaphore, still in the
 * violet family, and consistent with the legacy single-color bar's
 * left-anchored fill.
 *
 * The header (`Name (NN%)` + `X.Xg / Yg`) is identical to
 * [MacroProgressIndicator] so the card blends into the same row of macro
 * cards without visual drift. Total percentage/gram numbers still refer to
 * the SUM (primary + secondary) vs [target]; the split affects only the
 * bar rendering, not the header math.
 *
 * Called from the dashboard carbs slot when at least one food in the
 * window carries a `freeSugarsG` value. When ALL entries are legacy nulls,
 * the caller falls back to [MacroProgressIndicator]; when SOME are null
 * they contribute 0 to the secondary segment (conservative — unknown
 * free-sugar counts as "not shown as free sugar").
 */
@Composable
fun MacroProgressIndicatorSplit(
    name: String,
    actualPrimary: Double,
    actualSecondary: Double,
    target: Double,
    colorPrimary: Color,
    colorSecondary: Color
) {
    val total = actualPrimary + actualSecondary
    // Title shows the single total %: consumed ÷ target, matching the other
    // macro bars (e.g. "Gordura (64%)"). The two-segment split is shown as a
    // legend BELOW the bar (one % centered under each coloured segment).
    val percentage = if (target > 0.0) ((total / target) * 100).toInt() else 0
    // Per-segment share of what was CONSUMED (summing to 100). These feed the
    // below-bar legend, NOT the title. Truncation (.toInt(), i.e. floor) +
    // `100 - primaryPct` matches the breakdown popup's section headers
    // (MacroBreakdownDialog.kt ~line 241) so the bar legend and the popup
    // ("INSATURADA (68%)") always agree — both read the same food-level
    // sat-fat / free-sugar totals.
    val primaryPct = if (total > 0.0) ((actualPrimary / total) * 100.0).toInt() else 0
    val secondaryPct = if (total > 0.0) (100 - primaryPct) else 0
    // Compute each segment's fraction independently, then re-clamp their sum
    // to 1.0. This keeps the visible ratio primary:secondary intact even in
    // an over-limit day: an over-limit bar reads as "full" (like the legacy
    // one) with the correct split, not as a stretched domain.
    val rawPrimaryFrac = if (target > 0.0) (actualPrimary / target).toFloat() else 0f
    val rawSecondaryFrac = if (target > 0.0) (actualSecondary / target).toFloat() else 0f
    val rawSum = rawPrimaryFrac + rawSecondaryFrac
    val (primaryFrac, secondaryFrac) = if (rawSum <= 1f) {
        rawPrimaryFrac to rawSecondaryFrac
    } else {
        // Preserve ratio while clamping to [0, 1] total width.
        (rawPrimaryFrac / rawSum) to (rawSecondaryFrac / rawSum)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$name ($percentage%)",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${String.format("%.1f", total)}g / ${target.toInt()}g",
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Track + two contiguous segments. Row is used instead of two
            // absolute-positioned boxes so the segments never overlap and
            // both share the parent's clip shape. Note that only the
            // OUTER track has clip(CircleShape) — the internal segments
            // are square-edged so their meeting seam is a clean vertical
            // line rather than two overlapping rounded caps.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF334155))
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (primaryFrac > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(primaryFrac)
                                .background(colorPrimary)
                        )
                    }
                    if (secondaryFrac > 0f) {
                        // Width fraction of the REMAINING space so the two
                        // segments together fill exactly (primary+secondary)
                        // of the track. fillMaxWidth on a Row remainder
                        // reads the parent width, so we compute the
                        // secondary as a fraction of what's left after
                        // primary was drawn.
                        val remaining = 1f - primaryFrac
                        val secondaryOfRemaining =
                            if (remaining > 0f) (secondaryFrac / remaining).coerceIn(0f, 1f)
                            else 0f
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(secondaryOfRemaining)
                                .background(colorSecondary)
                        )
                    }
                }
            }
            // Below-bar legend: one partial % centered under each coloured
            // segment (e.g. "64%" under the solid unsaturated slice, "36%"
            // under the pale saturated tail). Slot widths mirror the bar's
            // own primaryFrac/secondaryFrac so each label sits under its
            // segment's centre; the trailing weighted Spacer soaks up the
            // empty track on an under-target day so the labels stay under the
            // coloured zone rather than being stretched across full width.
            // Same style as the "Consumo de Hoje" 71% legend (labelSmall /
            // 0xFF64748B).
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                if (primaryFrac > 0f) {
                    Box(
                        modifier = Modifier.weight(primaryFrac.coerceAtLeast(0.0001f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$primaryPct%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible
                        )
                    }
                }
                if (secondaryFrac > 0f) {
                    Box(
                        modifier = Modifier.weight(secondaryFrac.coerceAtLeast(0.0001f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$secondaryPct%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible
                        )
                    }
                }
                val consumedFrac = (primaryFrac + secondaryFrac).coerceAtMost(1f)
                val remainder = 1f - consumedFrac
                if (remainder > 0f) Spacer(modifier = Modifier.weight(remainder))
            }
        }
    }
}

// ==========================================
// FOOD CAPTURE SECTION
// ==========================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FoodCaptureSection(viewModel: ChatViewModel) {
    val userProfile by viewModel.userProfile.collectAsState()
    val pendingFoodEntry by viewModel.pendingFoodEntry.collectAsState()
    val summaryEdited by viewModel.summaryWasEditedAfterAnalysis.collectAsState()
    val context = LocalContext.current
    val isAnalyzing by viewModel.isAnalyzingFood.collectAsState()
    val analysisError by viewModel.analysisError.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var mealDescription by remember { mutableStateOf("") }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            // Phase 5 (2026-06-13): lossless byte-copy via FoodImageStore.
            // The previous pipeline decoded each URI into a Bitmap and
            // re-encoded JPEG q=90 (filesDir copy) + q=70 (Gemini upload),
            // destroying the fine text detail nutrition labels need. Now
            // we copy the original bytes verbatim to food_images/ and
            // forward those bytes (with their real mime) to Gemini.
            if (uris.size == 1) {
                val uri = uris.first()
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val persistedPath = FoodImageStore.persistFromUri(context, uri)
                    if (persistedPath != null) {
                        val pair = FoodImageStore.loadImageBytesAndMime(context, persistedPath)
                        if (pair != null) {
                            val (b64, mime) = pair
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                viewModel.analyzeAndLogFood(
                                    base64Image = b64,
                                    imagePath = persistedPath,
                                    promptHint = "User-uploaded gallery image",
                                    mimeType = mime,
                                    onSuccess = {},
                                    onFailure = { err ->
                                        android.util.Log.e(
                                            "FOOD_DIAG",
                                            "onFailure FIRED (gallery upload): reason='$err' " +
                                                "imagePath=${persistedPath.takeLast(40)}"
                                        )
                                        val fallbackEntry = FoodEntry(
                                            name = "Uploaded Meal (Manual Input pending)",
                                            calories = 320,
                                            protein = 15.0,
                                            carbs = 40.0,
                                            fat = 10.0,
                                            imagePath = persistedPath
                                        )
                                        viewModel.insertAndSetPendingFoodEntry(fallbackEntry)
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                // Multi-pick: persist sequentially (no decode = no OOM
                // pressure even with 5×5 MB photos), then hand the bundle
                // to the parallel Gemini fan-out.
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val photos = mutableListOf<PendingFoodPhoto>()
                    for (uri in uris) {
                        val persistedPath = FoodImageStore.persistFromUri(context, uri) ?: continue
                        val pair = FoodImageStore.loadImageBytesAndMime(context, persistedPath) ?: continue
                        val (b64, mime) = pair
                        photos += PendingFoodPhoto(base64 = b64, imagePath = persistedPath, mimeType = mime)
                    }
                    if (photos.isNotEmpty()) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            viewModel.analyzeFoodImagesParallel(
                                photos = photos,
                                promptHint = "User-uploaded gallery image (multi-pick session)"
                            )
                        }
                    }
                }
            }
        }
    }

    // Phase 5 (2026-06-13): camera state ancored across the camera-activity
    // round-trip. Same pattern as the chat path (see launchCamera helper).
    // The TakePicture contract writes the JPEG straight into the FileProvider
    // URI — zero re-encode, full sensor resolution. Replaces the legacy
    // TakePicturePreview path that returned a thumbnail Bitmap.
    var pendingFoodCameraFile by remember { mutableStateOf<java.io.File?>(null) }
    var pendingFoodCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val file = pendingFoodCameraFile
        val uri = pendingFoodCameraUri
        pendingFoodCameraFile = null
        pendingFoodCameraUri = null
        if (success && file != null && uri != null) {
            val finalUriString = uri.toString()
            // Mirror to DCIM/Camera/ — byte-copy, fire-and-forget, off the
            // main thread because it touches MediaStore.
            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                FoodImageStore.mirrorToPublicCamera(context, finalUriString)
                val pair = FoodImageStore.loadImageBytesAndMime(context, finalUriString)
                if (pair != null) {
                    val (b64, mime) = pair
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        viewModel.analyzeAndLogFood(
                            base64Image = b64,
                            imagePath = finalUriString,
                            promptHint = "User-captured camera image",
                            mimeType = mime,
                            onSuccess = {},
                            onFailure = { reason ->
                                android.util.Log.e(
                                    "FOOD_DIAG",
                                    "onFailure FIRED (camera capture): reason='$reason' " +
                                        "imagePath=${finalUriString.takeLast(40)}"
                                )
                                val fallbackEntry = FoodEntry(
                                    name = "Captured Meal (Manual Input pending)",
                                    calories = 340,
                                    protein = 18.0,
                                    carbs = 35.0,
                                    fat = 12.0,
                                    imagePath = finalUriString
                                )
                                viewModel.insertAndSetPendingFoodEntry(fallbackEntry)
                            }
                        )
                    }
                }
            }
        } else {
            // User cancelled or write failed — clean up the empty file.
            file?.delete()
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            launchCamera(context, cameraLauncher, dirName = "food_images") { f, u ->
                pendingFoodCameraFile = f
                pendingFoodCameraUri = u
            }
        }
    }

    // ── "Add another photo" launchers (multi-item session) ─────────────────
    // Distinct from the primary launchers above so the multi-pick / single-pick
    // distinction stays intact: these always APPEND a single new draft to the
    // current session via analyzeAndAppendFoodImage, instead of replacing it
    // (which would wipe the user's other items mid-session). Singletons opt
    // into multi-item by tapping the "+ Add photo" CTA inside the review.
    // Whether the next add-more gallery/camera launcher callback should
    // REPLACE the head session slot instead of appending a new draft.
    // Flipped on click by the Substituir/Tirar-de-novo buttons when the
    // session has a single item; the callback reads-and-resets it so a
    // subsequent tap starts clean. See plan
    // see-last-snap-looks-wobbly-quasar.md (2026-07-01).
    var replaceModeRequested by remember { mutableStateOf(false) }
    val addMoreGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        // Snapshot the flag on the main thread before hopping to IO so a
        // parallel tap can't race the branch.
        val wasReplace = replaceModeRequested
        replaceModeRequested = false
        if (uri != null) {
            // Phase 5: lossless byte-copy + verbatim Gemini upload.
            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val persistedPath = FoodImageStore.persistFromUri(context, uri)
                if (persistedPath != null) {
                    val pair = FoodImageStore.loadImageBytesAndMime(context, persistedPath)
                    if (pair != null) {
                        val (b64, mime) = pair
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (wasReplace) {
                                viewModel.analyzeAndReplaceFoodImage(
                                    base64Image = b64,
                                    imagePath = persistedPath,
                                    promptHint = "User-uploaded gallery image (replace head item)",
                                    mimeType = mime
                                )
                            } else {
                                viewModel.analyzeAndAppendFoodImage(
                                    base64Image = b64,
                                    imagePath = persistedPath,
                                    promptHint = "User-uploaded gallery image (extra item in session)",
                                    mimeType = mime
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // Phase 5: addMore camera state ancored separately so the two camera
    // sessions (single-shot path above + add-another path here) don't
    // clobber each other's pending file/uri.
    var pendingAddMoreCameraFile by remember { mutableStateOf<java.io.File?>(null) }
    var pendingAddMoreCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val addMoreCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val file = pendingAddMoreCameraFile
        val uri = pendingAddMoreCameraUri
        pendingAddMoreCameraFile = null
        pendingAddMoreCameraUri = null
        val wasReplace = replaceModeRequested
        replaceModeRequested = false
        if (success && file != null && uri != null) {
            val finalUriString = uri.toString()
            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                FoodImageStore.mirrorToPublicCamera(context, finalUriString)
                val pair = FoodImageStore.loadImageBytesAndMime(context, finalUriString)
                if (pair != null) {
                    val (b64, mime) = pair
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (wasReplace) {
                            viewModel.analyzeAndReplaceFoodImage(
                                base64Image = b64,
                                imagePath = finalUriString,
                                promptHint = "User-captured camera image (replace head item)",
                                mimeType = mime
                            )
                        } else {
                            viewModel.analyzeAndAppendFoodImage(
                                base64Image = b64,
                                imagePath = finalUriString,
                                promptHint = "User-captured camera image (extra item in session)",
                                mimeType = mime
                            )
                        }
                    }
                }
            }
        } else {
            file?.delete()
        }
    }
    val addMoreCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            launchCamera(context, addMoreCameraLauncher, dirName = "food_images") { f, u ->
                pendingAddMoreCameraFile = f
                pendingAddMoreCameraUri = u
            }
        } else {
            // Permission denied — user won't reach addMoreCameraLauncher this
            // round, so the read-and-reset there won't fire; do it here so
            // the next gallery tap doesn't inherit stale replace mode.
            replaceModeRequested = false
        }
    }

    if (pendingFoodEntry != null) {
        val entry = pendingFoodEntry!!
        val isSaving by viewModel.isSavingEntry.collectAsState()
        val saveError by viewModel.saveError.collectAsState()
        val lastAnalysis by viewModel.lastAnalyzedResult.collectAsState()
        // Match the chip to the entry actually being edited; if the user is
        // editing an older meal from History, the most recent analysis on the
        // VM may belong to a different photo — only show the chip when the
        // result and the pending entry agree on the meal name. 2026-06-19.
        // Returns the matching FoodAnalysisResult (not just .confidence) so
        // we can also surface the escalation badge when the result came from
        // the automatic flash→pro re-pass.
        val analysisForEntry = remember(lastAnalysis, entry.name) {
            lastAnalysis
                ?.takeIf { it.name?.equals(entry.name, ignoreCase = true) == true }
        }
        val confidenceForEntry = analysisForEntry?.confidence

        var editName by remember(entry.id, entry.timestamp, entry.name) { mutableStateOf(entry.name) }
        var editSummary by remember(entry.id, entry.timestamp, entry.lastAiAnalyzedSummary) { mutableStateOf(entry.fullSummary.sanitizeAiNewlines()) }
        var editCalories by remember(entry.id, entry.timestamp, entry.calories) { mutableStateOf(entry.calories.toString()) }
        var editProtein by remember(entry.id, entry.timestamp) { mutableStateOf(roundedGramsValue(entry.protein)) }
        var editCarbs by remember(entry.id, entry.timestamp) { mutableStateOf(roundedGramsValue(entry.carbs)) }
        var editFat by remember(entry.id, entry.timestamp) { mutableStateOf(roundedGramsValue(entry.fat)) }
        var selectedCategory by remember(entry.id, entry.timestamp, entry.mealCategory) { mutableStateOf(entry.mealCategory) }

        val parsedCals = editCalories.toIntOrNull() ?: entry.calories
        // 2026-08-16 — normalize comma→dot before parse. On pt-PT devices the
        // Decimal keyboard emits ',' so "21,6".toDoubleOrNull() was null →
        // fell back to entry.protein → edit silently discarded.
        val parsedProt = editProtein.replace(',', '.').toDoubleOrNull() ?: entry.protein
        val parsedCarbs = editCarbs.replace(',', '.').toDoubleOrNull() ?: entry.carbs
        val parsedFat = editFat.replace(',', '.').toDoubleOrNull() ?: entry.fat

        LaunchedEffect(editName, editSummary, parsedCals, parsedProt, parsedCarbs, parsedFat, selectedCategory) {
            viewModel.updatePendingFoodEntry(
                name = editName,
                calories = parsedCals,
                protein = parsedProt,
                carbs = parsedCarbs,
                fat = parsedFat,
                fullSummary = editSummary,
                mealCategory = selectedCategory
            )
        }

        // ── Unsaved-changes guard for system back ──
        // Snapshot of the entry the user landed on (existing meal from History, or
        // a fresh AI analysis of a just-captured photo). Compared against the live
        // edit fields so we only prompt the user when something actually changed —
        // back without edits exits silently.
        val initialEntry by remember(entry.id, entry.timestamp) { mutableStateOf(entry) }
        val isDirty = initialEntry.name != editName ||
            initialEntry.fullSummary != editSummary ||
            initialEntry.calories != parsedCals ||
            initialEntry.protein != parsedProt ||
            initialEntry.carbs != parsedCarbs ||
            initialEntry.fat != parsedFat ||
            initialEntry.mealCategory != selectedCategory ||
            initialEntry.timestamp != entry.timestamp
        var showDiscardDialog by remember(entry.id, entry.timestamp) { mutableStateOf(false) }

        BackHandler(enabled = !isSaving) {
            if (isDirty) {
                showDiscardDialog = true
            } else {
                // No edits → behave like the existing "Close & Discard" button.
                // Clearing pendingFoodSession triggers the LaunchedEffect at the
                // top of ChatScreen() that scrolls back to originTab (e.g. History).
                viewModel.rejectPendingFoodEntry()
            }
        }

        if (showDiscardDialog) {
            AlertDialog(
                onDismissRequest = { showDiscardDialog = false },
                containerColor = Color(0xFF0F172A),
                titleContentColor = Color.White,
                textContentColor = Color.White,
                title = { Text(tr("Unsaved changes", userProfile.language)) },
                text = { Text(tr("You have unsaved changes. What would you like to do?", userProfile.language)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDiscardDialog = false
                        val imgPath = pendingFoodEntry?.imagePath
                        viewModel.confirmAndSaveFoodEntry(
                            loadReanalysisImage = imgPath?.let { path ->
                                { com.example.ui.FoodImageStore.loadImageBytesAndMime(context, path) }
                            }
                        )
                    }) {
                        Text(
                            tr("Save", userProfile.language),
                            color = AccentSoft,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            showDiscardDialog = false
                            viewModel.rejectPendingFoodEntry()
                        }) {
                            Text(
                                tr("Discard", userProfile.language),
                                color = Color(0xFFEF4444)
                            )
                        }
                        TextButton(onClick = { showDiscardDialog = false }) {
                            Text(
                                tr("Cancel", userProfile.language),
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Multi-item session strip ──
            // Visible only when the session holds 2+ drafts (multi-pick or
            // "Add another photo"). Each thumbnail-card represents an
            // individual FoodEntry draft. Tapping a non-head card promotes
            // it to head so the edit dialog below targets it. The X removes
            // an item from the session without affecting the others.
            val sessionItems by viewModel.pendingFoodSession.collectAsState()
            val perItemErrors by viewModel.perItemAnalysisErrors.collectAsState()
            if (sessionItems.size >= 2) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = tr("Items in this meal", userProfile.language) + " (${sessionItems.size}/5)",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(sessionItems.size) { idx ->
                                val itemAtIdx = sessionItems[idx]
                                val isHead = idx == 0
                                val hasError = itemAtIdx.imagePath != null && itemAtIdx.imagePath in perItemErrors
                                Box(
                                    modifier = Modifier
                                        .size(width = 88.dp, height = 100.dp)
                                ) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable(enabled = !isHead) {
                                                viewModel.focusPendingFoodSessionItem(idx)
                                            },
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(
                                            if (isHead) 2.dp else 1.dp,
                                            if (hasError) Color(0xFFEF4444)
                                            else if (isHead) AccentPrimary else Color(0xFF334155)
                                        ),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                                    ) {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .weight(1f)
                                            ) {
                                                FoodImagePreview(
                                                    imagePath = itemAtIdx.imagePath,
                                                    modifier = Modifier.fillMaxSize(),
                                                    language = userProfile.language,
                                                    isThumbnail = true
                                                )
                                            }
                                            Text(
                                                text = "${itemAtIdx.calories} kcal",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    // Remove (X) button top-right
                                    IconButton(
                                        onClick = {
                                            viewModel.clearPerItemAnalysisError(itemAtIdx.imagePath)
                                            viewModel.removeFromPendingFoodSession(idx)
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(22.dp)
                                            .background(Color(0xFF0F172A), CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = tr("Remove item of food", userProfile.language),
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Meal name moved up here (above the photo) so the user can
            // validate the headline of the AI analysis at a glance — if the
            // dish name is wrong, they know to edit it before scrolling
            // through macros. Replaces the previously empty Column that used
            // to sit in this slot after the title moved into ScreenHeader.
            // 2026-06-19: see plan i-neew-you-to-dazzling-moon.md.
            OutlinedTextField(
                value = editName,
                onValueChange = { editName = it },
                label = { Text(tr("Meal Name", userProfile.language)) },
                modifier = Modifier.fillMaxWidth().testTag("edit_meal_name_input"),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = AccentPrimary, unfocusedBorderColor = Color(0xFF334155)
                )
            )

            FoodImagePreview(
                imagePath = entry.imagePath,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 280.dp),
                language = userProfile.language,
                isThumbnail = false,
                onClick = entry.imagePath?.let { path ->
                    {
                        val rawUri = Uri.parse(path)
                        val shareUri = if (path.startsWith("file://")) {
                            val file = java.io.File(rawUri.path!!)
                            androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "com.example.fileprovider",
                                file
                            )
                        } else {
                            rawUri
                        }
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(shareUri, "image/*")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    }
                }
            )

            // Summary-Driven Editor Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

                    // AI confidence chip — surfaces the model's self-rated
                    // confidence (low/medium/high) so the user knows whether
                    // to trust the macros or scrutinize the description.
                    // Only rendered when the most recent analysis result
                    // belongs to the entry currently being edited (see
                    // confidenceForEntry above). 2026-06-19, plan
                    // i-neew-you-to-dazzling-moon.md.
                    confidenceForEntry?.let { conf ->
                        ConfidenceChip(
                            level = conf,
                            language = userProfile.language,
                            escalated = analysisForEntry?.escalated == true,
                            modelName = analysisForEntry?.analysisModel ?: entry.analysisModel
                        )
                    }

                    // Edit Summary
                    OutlinedTextField(
                        value = editSummary,
                        onValueChange = {
                            editSummary = it
                            // 2026-06-18 — gate the streaming re-analysis path:
                            // confirmAndSaveFoodEntry() only fires the bubble
                            // when the user actually edited the summary after
                            // the initial analysis. Pure macro/category edits
                            // skip re-analysis (insert-direct, no bubble).
                            if (it != entry.fullSummary) viewModel.markSummaryEdited()
                        },
                        label = { Text(tr("Detailed Meal Summary", userProfile.language)) },
                        placeholder = { Text(tr("Describe what you ate...", userProfile.language)) },
                        modifier = Modifier.fillMaxWidth().testTag("edit_meal_summary_input"),
                        minLines = 2,
                        maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = AccentPrimary, unfocusedBorderColor = Color(0xFF334155)
                        )
                    )
                    Text(
                        text = tr("Editing this description triggers AI nutrient recalibration upon saving.", userProfile.language),
                        fontSize = 11.sp,
                        color = Color(0xFF38BDF8),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.padding(start = 2.dp)
                    )

                    // Meal Category Selector
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = tr("Meal Category", userProfile.language),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            com.example.data.local.MealCategory.values().forEach { category ->
                                val isSelected = selectedCategory == category
                                val categoryColor = when (category) {
                                    com.example.data.local.MealCategory.BREAKFAST -> Color(0xFF3B82F6)
                                    com.example.data.local.MealCategory.LUNCH -> AccentPrimary
                                    com.example.data.local.MealCategory.DINNER -> Color(0xFFF59E0B)
                                    com.example.data.local.MealCategory.SNACK -> Color(0xFFEC4899)
                                    com.example.data.local.MealCategory.DESSERT -> Color(0xFFD946EF)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) categoryColor.copy(alpha = 0.2f) else Color(0xFF0F172A))
                                        .border(1.dp, if (isSelected) categoryColor else Color(0xFF334155), RoundedCornerShape(8.dp))
                                        .clickable { selectedCategory = category },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = mealCategoryShortLabel(category, userProfile.language),
                                        color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Numeric stats
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editCalories,
                            onValueChange = { editCalories = it },
                            label = { Text(tr("Energy (kcal)", userProfile.language)) },
                            modifier = Modifier.weight(1.1f).testTag("edit_meal_calories_input"),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentPrimary, unfocusedBorderColor = Color(0xFF334155)
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editProtein,
                            onValueChange = { editProtein = it },
                            label = { Text(tr("Protein (g)", userProfile.language)) },
                            modifier = Modifier.weight(1f).testTag("edit_meal_protein_input"),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFF97316), unfocusedBorderColor = Color(0xFF334155)
                            )
                        )
                        OutlinedTextField(
                            value = editCarbs,
                            onValueChange = { editCarbs = it },
                            label = { Text(tr("Carbs (g)", userProfile.language)) },
                            modifier = Modifier.weight(1f).testTag("edit_meal_carbs_input"),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF8B5CF6), unfocusedBorderColor = Color(0xFF334155)
                            )
                        )
                        OutlinedTextField(
                            value = editFat,
                            onValueChange = { editFat = it },
                            label = { Text(tr("Fat (g)", userProfile.language)) },
                            modifier = Modifier.weight(1f).testTag("edit_meal_fat_input"),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFEAB308), unfocusedBorderColor = Color(0xFF334155)
                            )
                        )
                    }

                    // Progress indicators — macro bars first (with optional
                    // per-ingredient hints from the LLM breakdown), then the
                    // applied-habits chip, then the date picker row right
                    // before the CTAs. This ordering matches the
                    // 2026-06-15 redesign Hugo asked for.
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(tr("Macronutrients Detailed Log:", userProfile.language), color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)

                    val storedIngredients = remember(entry.ingredientsJson) { parseIngredients(entry.ingredientsJson) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MacroBarWithIngredients(
                            label = tr("Protein", userProfile.language),
                            value = entry.protein,
                            color = Color(0xFFF97316),
                            unit = "g",
                            ingredientHint = formatIngredientHint(storedIngredients, Macro.PROTEIN)
                        )
                        MacroBarWithIngredients(
                            label = tr("Carbohydrates", userProfile.language),
                            value = entry.carbs,
                            color = Color(0xFF8B5CF6),
                            unit = "g",
                            ingredientHint = formatIngredientHint(storedIngredients, Macro.CARBS)
                        )
                        // Free-sugars breakdown surfaces only when the LLM
                        // returned a number for this entry (or on a manual
                        // edit that lowered `carbs` — the ViewModel clamps
                        // the stored value against the new carbs before we
                        // read it here). Reads as a discreet one-liner
                        // under the carbs bar, not a chip / not a bar. The
                        // heuristic `topFreeSugarSourceHint` names the most
                        // likely ingredient culprit; falls back to no
                        // "· sobretudo de …" tail when nothing matches.
                        val freeSug = entry.freeSugarsG
                        if (freeSug != null && entry.carbs > 0.0) {
                            val clampedFreeSug = freeSug.coerceIn(0.0, entry.carbs)
                            val source = topFreeSugarSourceHint(storedIngredients)
                            Text(
                                text = formatFreeSugarsLine(
                                    freeSugarsG = clampedFreeSug,
                                    totalCarbsG = entry.carbs,
                                    sourceIngredient = source,
                                    language = userProfile.language
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(start = 100.dp)
                            )
                        }
                        MacroBarWithIngredients(
                            label = tr("Fats", userProfile.language),
                            value = entry.fat,
                            color = Color(0xFFEAB308),
                            unit = "g",
                            ingredientHint = formatIngredientHint(storedIngredients, Macro.FAT)
                        )
                    }

                    val habitsByIdEditor by viewModel.habitsById.collectAsState()
                    AppliedHabitsChip(
                        habitIds = entry.appliedHabitIds,
                        habitsById = habitsByIdEditor,
                        language = userProfile.language
                    )

                    // Date Selection Input Row — moved here (after the habits
                    // chip) so the picker sits next to the CTAs. The
                    // SimpleDateFormat now uses `localeForLanguage(...)` so the
                    // weekday/month abbreviations match the active UI language
                    // instead of always rendering as Locale.US.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F172A))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                            .clickable {
                                val calendar = Calendar.getInstance().apply { timeInMillis = entry.timestamp }
                                val datePickerDialog = android.app.DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        calendar.set(Calendar.YEAR, year)
                                        calendar.set(Calendar.MONTH, month)
                                        calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)

                                        android.app.TimePickerDialog(
                                            context,
                                            { _, hourOfDay, minute ->
                                                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                                                calendar.set(Calendar.MINUTE, minute)
                                                viewModel.updatePendingFoodEntryTimestamp(calendar.timeInMillis)
                                            },
                                            calendar.get(Calendar.HOUR_OF_DAY),
                                            calendar.get(Calendar.MINUTE),
                                            true
                                        ).show()
                                    },
                                    calendar.get(Calendar.YEAR),
                                    calendar.get(Calendar.MONTH),
                                    calendar.get(Calendar.DAY_OF_MONTH)
                                )
                                datePickerDialog.datePicker.firstDayOfWeek = java.util.Calendar.MONDAY
                                datePickerDialog.show()
                             }
                            .padding(12.dp)
                            .testTag("log_date_picker_trigger"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.EditCalendar,
                                    contentDescription = tr("Edit Date", userProfile.language),
                                    tint = AccentSoft,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = tr("Log Date & Time", userProfile.language),
                                    color = Color(0xFF94A3B8),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = tr("Choose Date", userProfile.language),
                                color = Color(0xFF3B82F6),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        val sdf = SimpleDateFormat("EEEE, d MMM yyyy  •  HH:mm", localeForLanguage(userProfile.language))
                        Text(
                            text = sdf.format(Date(entry.timestamp)),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 22.dp)
                        )
                    }
                }
            }

            if (saveError != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        saveError!!,
                        color = Color(0xFFFECACA),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // CTAs
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── Photo actions row. Semantics flip on session size:
                // • 1 item  → "Replace photo" / "Retake photo": swap the
                //   head slot's image and re-run analysis. Fixes the
                //   "add picture does nothing" report (2026-07-01, plan
                //   see-last-snap-looks-wobbly-quasar.md).
                // • 2–4 items → "+ Pick Photo" / "+ Take Photo": append a
                //   new draft to the multi-item session, unchanged.
                // Hidden once the session hits 5 (cap).
                val addAnotherEnabled = sessionItems.size < 5 && !isAnalyzing && !isSaving
                val isReplaceMode = sessionItems.size == 1
                if (addAnotherEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                replaceModeRequested = isReplaceMode
                                addMoreGalleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Icon(Icons.Rounded.FileUpload, contentDescription = null, tint = Color(0xFF38BDF8))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (isReplaceMode) tr("Replace photo", userProfile.language)
                                else "+ " + tr("Pick Photo", userProfile.language),
                                color = Color(0xFFE2E8F0),
                                fontSize = 13.sp
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                replaceModeRequested = isReplaceMode
                                // Phase 5: TakePicture FileProvider path needs
                                // CAMERA permission. Same dance as the chat
                                // attach sheet: check first, request only if
                                // missing, dispatch the launcher on grant.
                                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.CAMERA
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    launchCamera(context, addMoreCameraLauncher, dirName = "food_images") { f, u ->
                                        pendingAddMoreCameraFile = f
                                        pendingAddMoreCameraUri = u
                                    }
                                } else {
                                    addMoreCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = Color(0xFF38BDF8))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (isReplaceMode) tr("Retake photo", userProfile.language)
                                else "+ " + tr("Take Photo", userProfile.language),
                                color = Color(0xFFE2E8F0),
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        // 2026-06-22 — re-load the original meal photo from
                        // disk so the streaming re-analysis path can pair it
                        // with the user's edited description. Without the
                        // image, the Pro escalation is a no-op (same text
                        // both models would see). Text-only entries leave
                        // imagePath null → no loader, no image attached, no
                        // escalation. Loader runs off the main thread inside
                        // the VM coroutine.
                        val imgPath = pendingFoodEntry?.imagePath
                        viewModel.confirmAndSaveFoodEntry(
                            loadReanalysisImage = imgPath?.let { path ->
                                { com.example.ui.FoodImageStore.loadImageBytesAndMime(context, path) }
                            }
                        )
                    },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("accept_analysis_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentPrimary,
                        contentColor = Color.White
                    )
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(color = Color(0xFF0F172A), modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("Analyzing changes...", userProfile.language), fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Rounded.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("Accept & Save Meal", userProfile.language), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                // 2026-06-18 — re-analysis feedback bubble. Visible when the
                // user edited fullSummary and tapped Save: the orchestrator
                // streams phase events so the bubble shows "A reanalisar a
                // refeição…" + a cancel button right under the CTA.
                // 2026-07-10 — ALSO visible during a photo-replace re-analysis
                // (isAnalyzing): "Substituir foto"/"Tirar de novo" fire the
                // orchestrator too, and without this the button Row just
                // vanishes (addAnotherEnabled=false) leaving a silent gap —
                // the user thought "nada aconteceu". The bubble now covers
                // both the save-reanalysis and the photo-replace windows,
                // including the seconds spent retrying a 429.
                run {
                    val phaseKey by viewModel.foodAnalysisPhase.collectAsState()
                    val isReanalyzing = (isSaving || isAnalyzing) && phaseKey != null
                    androidx.compose.animation.AnimatedVisibility(visible = isReanalyzing) {
                        TypingIndicatorBubble(
                            phaseText = tr(
                                phaseKey ?: "phase_reanalyzing_meal",
                                userProfile.language
                            ),
                            onCancel = { viewModel.cancelFoodAnalysis() }
                        )
                    }
                }

                Button(
                    onClick = { viewModel.rejectPendingFoodEntry() },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("reject_analysis_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFFEF4444)),
                    border = BorderStroke(1.dp, Color(0xFF7F1D1D))
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    // Persisted row + no fresh edits → plain "Fechar" (nothing
                    // to discard). Otherwise the destructive "Fechar e Descartar"
                    // keeps its warning weight.
                    val nothingToDiscard = pendingFoodEntry?.let { it.id != 0L && !summaryEdited } == true
                    Text(
                        tr(if (nothingToDiscard) "Close" else "Close & Discard", userProfile.language),
                        fontWeight = FontWeight.Bold
                    )
                }

                // Edit log — most-recent first, shown below Fechar
                val editLog = remember(pendingFoodEntry?.editLogJson) {
                    com.example.data.local.decodeEditLog(pendingFoodEntry?.editLogJson)
                }
                if (editLog.isNotEmpty()) {
                    val dateFmt = remember { SimpleDateFormat("dd-MM-yy", java.util.Locale.getDefault()) }
                    val timeFmt = remember { SimpleDateFormat("HH'h'mm", java.util.Locale.getDefault()) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        editLog.forEach { entry ->
                            val d = java.util.Date(entry.epochMs)
                            val typeLabel = when (entry.type) {
                                com.example.data.local.FoodEditType.ADDED  -> "Adicionado"
                                com.example.data.local.FoodEditType.AUTO   -> "Atualiz. Auto"
                                com.example.data.local.FoodEditType.MANUAL -> "Atualiz. Manual"
                            }
                            // Append the LLM behind the write (e.g. "· gemini-3.5-flash").
                            // Null for purely manual edits and pre-field entries — those
                            // show the type label alone rather than a blank suffix.
                            val rightLabel = entry.model
                                ?.takeIf { it.isNotBlank() }
                                ?.let { "$typeLabel · $it" }
                                ?: typeLabel
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${dateFmt.format(d)}  ${timeFmt.format(d)}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                Text(
                                    rightLabel,
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // (Removido o título+subtítulo "Food tracker / Snap meals..." —
            // duplicava o pill seleccionado por cima e o ScreenHeader.)

            // Viewfinder Layout Frame
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 280.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E293B))
                    .border(
                        BorderStroke(
                            2.dp,
                            Brush.linearGradient(listOf(AccentSoft, Color(0xFF60A5FA)))
                        ),
                        RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize().padding(24.dp)
                ) {
                    if (isAnalyzing) {
                        // 2026-06-18 — meal-analysis feedback engine.
                        // FoodAnalysisProgressCard reads the orchestrator's
                        // phase + progress StateFlows so the user sees
                        // "A identificar o prato…" / chips of ingredients
                        // appearing as the SSE stream lands, instead of a
                        // 5-15 s silent spinner.
                        val phaseKey by viewModel.foodAnalysisPhase.collectAsState()
                        val progress by viewModel.foodAnalysisProgress.collectAsState()
                        com.example.ui.components.FoodAnalysisProgressCard(
                            phaseKey = phaseKey,
                            progress = progress,
                            language = userProfile.language,
                            onCancel = { viewModel.cancelFoodAnalysis() }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            OutlinedTextField(
                                value = mealDescription,
                                onValueChange = { mealDescription = it },
                                placeholder = { Text(tr("Describe your meal...", userProfile.language), color = Color(0xFF94A3B8)) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = AccentSoft,
                                    focusedBorderColor = AccentSoft,
                                    unfocusedBorderColor = Color.Gray,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxSize()
                            )
                            IconButton(
                                onClick = {
                                    if (mealDescription.isNotBlank()) {
                                        viewModel.analyzeAndLogFood(
                                            base64Image = null,
                                            imagePath = null,
                                            promptHint = "Analyzing described meal: $mealDescription",
                                            onSuccess = {
                                                mealDescription = ""
                                            },
                                            onFailure = { err ->
                                                val fallbackEntry = FoodEntry(
                                                    name = "Described Meal",
                                                    calories = 300,
                                                    protein = 10.0,
                                                    carbs = 30.0,
                                                    fat = 10.0,
                                                    imagePath = null
                                                )
                                                viewModel.setPendingFoodEntry(fallbackEntry)
                                            }
                                        )
                                    }
                                },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            ) {
                                Icon(Icons.Rounded.Send, contentDescription = "Send", tint = AccentSoft)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            tr("Or tap buttons below to take photo or select file", userProfile.language),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Interactive primary action triggers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 2026-07-20 — botões maiores para preencher o espaço vazio
                // por baixo do viewfinder (o pai é uma Column com
                // verticalScroll, logo `weight` não resolveria; usamos altura
                // fixa grande + ícone-sobre-texto centrado para lerem como
                // cartões de acção grandes em vez de pills esticadas).
                Button(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(140.dp)
                        .testTag("upload_image_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6), contentColor = Color.White)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Rounded.FileUpload,
                            contentDescription = tr("Gallery", userProfile.language),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(tr("Pick Photo", userProfile.language), fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        // Phase 5: see addMore camera button for the same
                        // permission/launchCamera pattern.
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.CAMERA
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            launchCamera(context, cameraLauncher, dirName = "food_images") { f, u ->
                                pendingFoodCameraFile = f
                                pendingFoodCameraUri = u
                            }
                        } else {
                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(140.dp)
                        .testTag("capture_image_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.White)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Rounded.PhotoCamera,
                            contentDescription = tr("Camera", userProfile.language),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(tr("Take Photo", userProfile.language), fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Detailed error reporting
            if (analysisError != null && pendingFoodEntry == null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D).copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, Color(0xFFF87171)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(tr("Analysis Error Details", userProfile.language), fontWeight = FontWeight.Bold, color = Color(0xFFF87171))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(analysisError!!, color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

sealed class TimelineItem {
    abstract val timestamp: Long
    data class Food(val food: FoodEntry) : TimelineItem() {
        override val timestamp: Long get() = food.timestamp
    }
    data class Activity(val activity: ActivityEntry) : TimelineItem() {
        override val timestamp: Long get() = activity.timestamp
    }
}

// ==========================================
// TAB 3: HISTORY
// ==========================================
/**
 * Normaliza uma string para pesquisa: minúsculas + remoção de diacríticos
 * (NFD + strip de marcas de combinação). Faz "café" ≡ "cafe" e
 * "refeição" ≡ "refeicao", para a pesquisa do Histórico não falhar por acentos.
 */
private fun String.foldAccents(): String =
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()

/** Match de refeição: nome + ingredientes (JSON). [q] já vem foldAccents(). */
private fun matchesFood(food: FoodEntry, q: String): Boolean =
    food.name.foldAccents().contains(q) ||
        (food.ingredientsJson?.foldAccents()?.contains(q) == true)

/** Match de atividade: nome + sourceLabel. [q] já vem foldAccents(). */
private fun matchesActivity(activity: ActivityEntry, q: String): Boolean =
    activity.name.foldAccents().contains(q) ||
        (activity.sourceLabel?.foldAccents()?.contains(q) == true)

@Composable
fun HistoryTab(
    foods: List<FoodEntry>,
    activities: List<ActivityEntry>,
    viewModel: ChatViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    initialFilter: String = "All",
    onFilterChange: (String) -> Unit = {},
    isVisible: Boolean = true,
    onNavigateToTab: (AppTab, CaptureMode) -> Unit = { _, _ -> }
) {
    val userProfile by viewModel.userProfile.collectAsState()
    val habitsByIdHistory by viewModel.habitsById.collectAsState()
    val now = System.currentTimeMillis()
    val filteredFoods = remember(foods, initialFilter) {
        when (initialFilter) {
            "Daily" -> foods.filter { isSameDay(it.timestamp, now) }
            "Weekly" -> foods.filter { now - it.timestamp <= 7L * 24 * 60 * 60 * 1000 }
            "Monthly" -> foods.filter { now - it.timestamp <= 30L * 24 * 60 * 60 * 1000 }
            else -> foods
        }
    }

    val filteredActivities = remember(activities, initialFilter) {
        when (initialFilter) {
            "Daily" -> activities.filter { isSameDay(it.timestamp, now) }
            "Weekly" -> activities.filter { now - it.timestamp <= 7L * 24 * 60 * 60 * 1000 }
            "Monthly" -> activities.filter { now - it.timestamp <= 30L * 24 * 60 * 60 * 1000 }
            else -> activities
        }
    }

    // 2026-08-16 (Hugo) — o filtro de tipo (All Logs / Refeições / Atividades)
    // foi removido; a lista é sempre "todos" (refeições + atividades misturadas)
    // e a filtragem passa a ser feita por uma caixa de pesquisa em tempo real.
    val combinedList = remember(filteredFoods, filteredActivities) {
        val list = mutableListOf<TimelineItem>()
        list.addAll(filteredFoods.map { TimelineItem.Food(it) })
        list.addAll(filteredActivities.map { TimelineItem.Activity(it) })
        list.sortByDescending { it.timestamp }
        list
    }

    // Pesquisa em tempo real: filtra a partir de >= 3 letras. Procura no nome +
    // ingredientes das refeições e no nome + sourceLabel das atividades, com
    // normalização de acentos ("cafe" encontra "café").
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // 2026-08-16 (Hugo) — ao fazer slide para outra tab (ou abrir a captura de
    // refeição), o teclado da caixa de pesquisa ficava preso por cima do ecrã
    // de destino. Quando o Histórico deixa de ser a página visível, largamos o
    // foco e escondemos o teclado. Mesmo padrão de gate `isVisible` do ChatTab.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(isVisible) {
        if (!isVisible) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    val query = searchQuery.trim()
    val searchedList = remember(combinedList, query) {
        if (query.length < 3) {
            combinedList
        } else {
            val q = query.foldAccents()
            combinedList.filter { item ->
                when (item) {
                    is TimelineItem.Food -> matchesFood(item.food, q)
                    is TimelineItem.Activity -> matchesActivity(item.activity, q)
                }
            }
        }
    }

    // Totais (kcal + P/C/F) da seleção actual — só refeições contribuem.
    val totalKcal = remember(searchedList) {
        searchedList.filterIsInstance<TimelineItem.Food>().sumOf { it.food.calories }
    }
    val totalProtein = remember(searchedList) {
        searchedList.filterIsInstance<TimelineItem.Food>().sumOf { it.food.protein }
    }
    val totalCarbs = remember(searchedList) {
        searchedList.filterIsInstance<TimelineItem.Food>().sumOf { it.food.carbs }
    }
    val totalFat = remember(searchedList) {
        searchedList.filterIsInstance<TimelineItem.Food>().sumOf { it.food.fat }
    }
    val mealCount = remember(searchedList) {
        searchedList.count { it is TimelineItem.Food }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        com.example.ui.components.ScreenHeader(
            title = tr("History", userProfile.language),
            subtitle = tr("Review meals and activity", userProfile.language)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {

        // Timeline Filter Options Bar — pill row uniforme (gradient violeta + glow / outline)
        com.example.ui.components.SegmentedPillRow(
            options = listOf("All", "Daily", "Weekly", "Monthly"),
            selected = initialFilter,
            onSelect = onFilterChange,
            labelFor = { tr(it, userProfile.language) },
            modifier = Modifier.padding(vertical = 12.dp)
        )

        // Search box — filtra a lista em tempo real (>= 3 letras). Substitui o
        // antigo filtro de tipo All Logs / Refeições / Atividades. BasicTextField
        // (em vez de OutlinedTextField) para controlar a altura — a versão M3 tem
        // ~56dp fixos e ocupava demasiado espaço; esta fica compacta (36dp) com
        // margens internas mínimas.
        BasicTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            singleLine = true,
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 14.sp
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(AccentSoft),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                tr("Search meals and activities", userProfile.language),
                                color = Color(0xFF64748B),
                                fontSize = 14.sp,
                                lineHeight = 14.sp,
                                maxLines = 1
                            )
                        }
                        innerTextField()
                    }
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = tr("Clear", userProfile.language),
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { searchQuery = "" }
                        )
                    }
                }
            }
        )

        // Totais da seleção actual (período + pesquisa) — kcal + P/C/F. Só
        // aparece em Diário/Semanal/Mensal: em "Todos" somaria todo o histórico,
        // um número sem significado prático. Requer >= 1 refeição no resultado.
        if (mealCount > 0 && initialFilter != "All") {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1E293B),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        append("$totalKcal kcal")
                        pop()
                        append(
                            "   •   P ${totalProtein.roundToInt()}g" +
                                "   C ${totalCarbs.roundToInt()}g" +
                                "   F ${totalFat.roundToInt()}g"
                        )
                    },
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }

        if (searchedList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(
                        imageVector = if (query.length >= 3) Icons.Rounded.Search else Icons.Rounded.Fastfood,
                        contentDescription = null,
                        tint = Color(0xFF334155),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val noLogsText = if (query.length >= 3) {
                        tr("No results found", userProfile.language)
                    } else {
                        tr("No logged entries in range", userProfile.language)
                    }
                    Text(noLogsText, color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold)
                    Text(
                        text = tr("Snap pictures or describe your meal in the capture tab to start logging!", userProfile.language),
                        color = Color(0xFF475569),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(searchedList, key = { item ->
                    when (item) {
                        is TimelineItem.Food -> "food_${item.food.id}"
                        is TimelineItem.Activity -> "activity_${item.activity.id}"
                    }
                }) { item ->
                    when (item) {
                        is TimelineItem.Food -> {
                            val entry = item.food
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("food_item_card_${entry.id}")
                                    .clickable {
                                        viewModel.setEditingFoodEntry(entry)
                                        onNavigateToTab(AppTab.ACTIVITY, CaptureMode.FOOD)
                                    },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                border = BorderStroke(1.dp, Color(0xFF334155))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (entry.imagePath != null) {
                                        FoodImagePreview(
                                            imagePath = entry.imagePath,
                                            modifier = Modifier.size(56.dp),
                                            language = userProfile.language,
                                            isThumbnail = true
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(AccentPrimary.copy(alpha = 0.1f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val vectorIcon = when (entry.mealCategory) {
                                                com.example.data.local.MealCategory.BREAKFAST -> Icons.Rounded.BreakfastDining
                                                com.example.data.local.MealCategory.LUNCH -> Icons.Rounded.SetMeal
                                                com.example.data.local.MealCategory.DINNER -> Icons.Rounded.Restaurant
                                                com.example.data.local.MealCategory.SNACK -> Icons.Rounded.LocalCafe
                                                com.example.data.local.MealCategory.DESSERT -> Icons.Rounded.Cake
                                            }
                                            Icon(imageVector = vectorIcon, contentDescription = null, tint = AccentSoft)
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                                        // 2026-06-22 — kcal em branco e ligeiramente maior do que
                                        // os macros, para destacar a métrica principal da refeição
                                        // ao olhar de relance. Os macros (P/C/F) continuam em
                                        // cinza menor para hierarquia clara.
                                        Text(
                                            text = androidx.compose.ui.text.buildAnnotatedString {
                                                pushStyle(
                                                    androidx.compose.ui.text.SpanStyle(
                                                        color = Color.White,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                )
                                                append("${entry.calories} kcal")
                                                pop()
                                                append("  •  P: ${String.format("%.1f", entry.protein)}g C: ${String.format("%.1f", entry.carbs)}g F: ${String.format("%.1f", entry.fat)}g")
                                            },
                                            color = Color(0xFF94A3B8),
                                            fontSize = 12.sp
                                        )
                                        val dateStr = remember(entry.timestamp, userProfile.language) {
                                            // Prefix the abbreviated weekday so the row reads
                                            // "Sun, 07 Jun, 14:30" (or "dom, 07 jun, 14:30" in
                                            // PT). Locale follows userProfile.language so
                                            // EEE/MMM render in the user's tongue. Hugo wants
                                            // 24-hour time (HH:mm, no a/p) and the trailing
                                            // dots that ICU adds after pt abbreviations
                                            // (`dom.`, `jun.`) stripped — see formatHistoryDate.
                                            formatHistoryDate(entry.timestamp, userProfile.language)
                                        }
                                        Text(dateStr, color = Color(0xFF64748B), fontSize = 10.sp)
                                        // ── Multi-item meal badge ──
                                        // Logged in the same capture session (multi-pick or
                                        // "+ Add another photo"), so we show a tiny chip
                                        // with the sibling count. Pre-v11 entries and
                                        // singletons have groupId=null and skip this.
                                        if (entry.groupId != null) {
                                            val groupCount = remember(foods, entry.groupId) {
                                                foods.count { it.groupId == entry.groupId }
                                            }
                                            if (groupCount >= 2) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(Color(0xFFD946EF).copy(alpha = 0.15f))
                                                            .border(
                                                                BorderStroke(1.dp, Color(0xFFD946EF).copy(alpha = 0.4f)),
                                                                RoundedCornerShape(6.dp)
                                                            )
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = "🔗 " + tr("Items in this meal", userProfile.language) + " ($groupCount)",
                                                            color = Color(0xFFE9D5FF),
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        if (!entry.appliedHabitIds.isNullOrEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            AppliedHabitsChip(
                                                habitIds = entry.appliedHabitIds,
                                                habitsById = habitsByIdHistory,
                                                language = userProfile.language,
                                                compact = true
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { viewModel.deleteFood(entry) },
                                        modifier = Modifier.testTag("delete_meal_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = tr("Remove item of food", userProfile.language),
                                            tint = Color(0xFFEF4444)
                                        )
                                    }
                                }
                            }
                        }
                        is TimelineItem.Activity -> {
                            val entry = item.activity
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("activity_item_card_${entry.id}")
                                    .clickable {
                                        viewModel.setEditingActivitySession(entry.sessionId)
                                        onNavigateToTab(AppTab.ACTIVITY, CaptureMode.ACTIVITY)
                                    },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                border = BorderStroke(1.dp, Color(0xFF334155))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF3B82F6).copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(imageVector = Icons.Rounded.DirectionsRun, contentDescription = null, tint = Color(0xFF60A5FA))
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                                        Text(
                                            text = "${entry.durationMinutes} ${tr("min", userProfile.language)}  •  -${entry.caloriesBurned} kcal",
                                            color = Color(0xFF60A5FA),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        val dateStr = remember(entry.timestamp, userProfile.language) {
                                            formatHistoryDate(entry.timestamp, userProfile.language)
                                        }
                                        Text(dateStr, color = Color(0xFF64748B), fontSize = 10.sp)
                                    }

                                    IconButton(
                                        onClick = { viewModel.deleteActivity(entry) },
                                        modifier = Modifier.testTag("delete_activity_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = tr("Remove activity log", userProfile.language),
                                            tint = Color(0xFFEF4444)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        } // end inner padding(16) Column
    }
}

// ==========================================
// TAB 4: CHAT (Original Portal Experience nested)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTab(
    viewModel: ChatViewModel,
    isVisible: Boolean = true,
    // Stronger truth than `isVisible` (which is currentPage-based and flips at
    // ~50% of a settle): true only when the pager has SETTLED on Chat and is
    // not scrolling. Used solely to gate the search overlay's auto-focus so the
    // IME can't rise over Painel during a Chat→Painel settle. See the overlay's
    // LaunchedEffect for the matching post-delay re-check.
    isSettledOnChat: Boolean = isVisible
) {
    val userProfile by viewModel.userProfile.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    // Phase 4 fix (2026-06-13): NOT rememberDrawerState. Material3's
    // rememberDrawerState wraps DrawerState in rememberSaveable with a Saver
    // that persists currentValue, so after process recreation / config
    // change the saver can restore DrawerValue.Open. The first frame paints
    // the open sheet before any LaunchedEffect coroutine resumes to snap it
    // shut. Hugo saw the drawer open by itself in Phase 4 smoke tests.
    // Using plain `remember` constructs a fresh DrawerState every time
    // composition starts cold — there is no use case for a persistent
    // half-open drawer, and every legit programmatic open
    // (the hamburger button) is short-lived and user-initiated.
    val drawerState = remember { DrawerState(initialValue = DrawerValue.Closed) }
    val context = LocalContext.current

    // 2026-06-15: long-press menu now opens the ModalBottomSheet exclusively;
    // text selection is opt-in via the "Select text" action. Only one bubble
    // can be in selection mode at a time — hoist the id here so siblings can
    // mutually exclude each other and we have a single point to clear it
    // (back press, tap on empty timeline). NOT rememberSaveable: this is
    // ephemeral UI state, dropping it on config change is desirable.
    var selectedMessageId by remember { mutableStateOf<Long?>(null) }

    // 2026-06-29 — Frente 2: state for the Memory dialog (opens via drawer
    // "Memory" button, lists confirmed + pending user_insights with
    // soft-delete affordance). Ephemeral, intentionally NOT rememberSaveable
    // so config-change closes the dialog rather than restoring it. */
    var showMemoryDialog by remember { mutableStateOf(false) }

    // HorizontalPager mantém esta tab composta durante (e por vezes após) a
    // animação de mudança de page. Se o drawer ficasse aberto, o ModalDrawerSheet
    // (300dp, fundo opaco) seria desenhado por cima da tab agora visível.
    // snapTo (não close()): close() é uma animação de ~250 ms — durante esse
    // tempo o sheet continua a pintar 300 dp opacos por cima da tab activa.
    // snapTo colapsa no mesmo frame. Verificar currentValue (em vez de isOpen)
    // cobre também o caso de o drawer estar a meio de animar para Open.
    //
    // Phase 4 fix v2 (2026-06-13): also snap-shut when becoming visible, not
    // just when leaving. Hugo confirmed the drawer opens deterministically
    // every time the Chat tab becomes visible — neither programmatic open
    // (only the hamburger button calls .open()) nor the rememberSaveable
    // Saver explained it. Closing on every visibility transition is the
    // belt-and-braces fix until the underlying ModalNavigationDrawer M3
    // behaviour is understood.
    LaunchedEffect(isVisible) {
        if (drawerState.currentValue != DrawerValue.Closed) {
            drawerState.snapTo(DrawerValue.Closed)
        }
    }

    // Phase 4 fix v2 (2026-06-13): defensive snapTo on every recomposition
    // pass that happens while the tab is visible. The cost is one extra
    // currentValue read per recomposition; the benefit is that any
    // ModalNavigationDrawer-internal state transition (which seems to
    // happen on first composition of the visible tab on some Compose
    // versions) is reverted within the same frame.
    LaunchedEffect(Unit) {
        if (drawerState.currentValue != DrawerValue.Closed) {
            drawerState.snapTo(DrawerValue.Closed)
        }
    }

    // Hardware/gesture-back closes the drawer when it's open. The parent
    // HorizontalPager swallows pointer events for the drawer scrim on some
    // devices, so a guaranteed exit path matters. O gate `isVisible` evita
    // que a Chat (composta off-screen) intercepte back presses destinados a
    // outra tab.
    BackHandler(enabled = isVisible && drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }

    // 2026-06-15: when a bubble is in selection mode, back press exits the
    // mode without leaving the screen. Registered AFTER the drawer handler
    // so it gets priority (Compose BackHandler is LIFO: the last enabled
    // callback wins). Both are gated by `isVisible` so off-screen Chat tabs
    // don't intercept back events meant for the visible tab.
    BackHandler(enabled = isVisible && selectedMessageId != null) {
        selectedMessageId = null
    }

    val sessions by viewModel.sessions.collectAsState()
    val selectedSessionId by viewModel.selectedSessionId.collectAsState()
    val activeMessages by viewModel.activeMessages.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()

    // 2026-06-29 — Frente A (proactivity inline) DISABLED per Hugo's directive.
    // The unprompted-on-tab-resume bubble feature was introduced in v166 and
    // confused both users on first launch (Madhvi saw an unexpected
    // ActivitySpike message after the v151→v179 jump on the S25; Hugo flagged
    // it as "aparece aleatoriamente sem necessidade"). The trigger evaluation,
    // the orchestrator's `handleProactive`, and the VM's
    // [maybeFireProactiveOnChatTabResumed] are intentionally kept dormant so a
    // future opt-in toggle can re-enable them without rewriting the pipeline.
    // OS-level push notifications (`ProactiveNudgeWorker`) are unaffected and
    // keep firing on the user's existing `notification_pref` settings.
    //
    // val isImeOpen alias for the IME visibility insets — declared further
    // below near the input bar; left as-is.
    val inputText by viewModel.inputText.collectAsState()
    val isTyping by viewModel.isTyping.collectAsState()
    val typingPhase by viewModel.typingPhase.collectAsState()
    // Build 2 / Fase 1 (2026-06-27): live token-by-token stream from the
    // orchestrator. Null/blank while the LLM is still in the
    // pre-stream phases (analyzing, querying records, composing). Once
    // bytes start arriving, this StateFlow accumulates them; we render
    // them in an in-list bubble pinned at index 0 with the top-anchor
    // pattern below.
    val streamingText by viewModel.streamingText.collectAsState()
    val attachedChatImageBase64 by viewModel.attachedChatImageBase64.collectAsState()
    val attachedChatImagePaths by viewModel.attachedChatImagePaths.collectAsState()
    // Phase 3 (#4): observed in [ChatTab] so the input bar at both call
    // sites (empty-state + populated-state) can morph into edit mode.
    val editingMessageId by viewModel.editingMessageId.collectAsState()
    val isEditing = editingMessageId != null

    // Persist the active session id whenever it changes, so a cold restart
    // resumes the same conversation. Reads back via loadUserProfile().
    LaunchedEffect(selectedSessionId) {
        if (selectedSessionId != null) {
            viewModel.rememberSelectedSession(context)
        }
    }

    // Phase C: multi-image picker. Up to 5 images per message — copied to
    // filesDir/chat_images/ inside the callback (bytes are still readable
    // because the temporary content:// grant is alive at this point) so the
    // path can later survive process death.
    //
    // Phase 3 (#7): lossless byte-copy via [ChatImageStore.persistFromUri].
    // The previous pipeline decoded each picker URI into a Bitmap and
    // re-encoded JPEG q=90, which destroyed the fine text detail Hugo
    // depends on for nutrition-label OCR. The original gallery file is
    // already a valid JPEG/HEIC — we just copy its bytes verbatim.
    val chatGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val maxAllowed = (5 - viewModel.attachedChatImagePaths.value.size).coerceAtLeast(0)
        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            uris.take(maxAllowed).forEach { uri ->
                val persisted = ChatImageStore.persistFromUri(context, uri)
                if (persisted != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        viewModel.appendChatImagePath(persisted)
                    }
                }
            }
        }
    }

    var showConfigDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<ChatSession?>(null) }
    // 2026-06-15: cross-conversation FTS4 search overlay. Toggled by the
    // header search icon. The overlay covers the chat surface but lives
    // inside the same Column above the InputBar so the InputBar stays
    // hidden while searching (pesquisar e escrever ao mesmo tempo é
    // confuso e a procurar não há sessão activa para a barra fazer sentido).
    var showSearchOverlay by remember { mutableStateOf(false) }
    // HorizontalPager keeps this tab composed off-screen (±1 neighbour). If the
    // search overlay is left open, its auto-focus LaunchedEffect fires
    // requestFocus() while Chat is NOT the visible page, raising the IME over
    // whatever tab the user is actually on (Hugo saw the keyboard appear on the
    // Painel). Same class as the drawer-opens-by-itself bug above — reset the
    // overlay whenever this tab stops being visible, mirroring the drawer
    // snapTo(Closed) on isVisible transition.
    LaunchedEffect(isVisible) {
        if (!isVisible && showSearchOverlay) {
            showSearchOverlay = false
            viewModel.clearSearchQuery()
        }
    }
    BackHandler(enabled = isVisible && showSearchOverlay) {
        showSearchOverlay = false
        viewModel.clearSearchQuery()
    }

    // 2026-06-15 jump-to-message highlight: when a search-result tap lands
    // the user on a specific message, we set this state to that message id
    // for ~1.6 s. The MessageBubbleItem's wrapper Box reads it and renders
    // a soft accent border around the bubble during that interval, so the
    // user sees WHICH message matched the search at a glance.
    var highlightedMessageId by remember { mutableStateOf<Long?>(null) }
    // Phase 3 (#6): camera capture path. The TakePicture contract writes the
    // JPEG directly into the FileProvider-backed URI we hand it, so there's
    // no Bitmap intermediate and no JPEG re-encode — the photo arrives at
    // the model with the same fidelity as the source sensor write.
    //
    // [pendingCameraFile] / [pendingCameraUri] survive the round-trip to
    // the camera Activity via remember; if the system tears us down, the
    // ActivityResultContract internals re-deliver. On a `success=false`
    // result (user cancelled or a write failure) we delete the empty file
    // so we don't accumulate zero-byte JPEGs in chat_images/.
    var pendingCameraFile by remember { mutableStateOf<java.io.File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showAttachSheet by remember { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val file = pendingCameraFile
        val uri = pendingCameraUri
        pendingCameraFile = null
        pendingCameraUri = null
        if (success && file != null && uri != null) {
            // Defensive re-check of the 5-image cap — the bottom sheet
            // already gates this but a fast double-tap during a permission
            // grant could race past it.
            if (viewModel.attachedChatImagePaths.value.size < 5) {
                // BUGFIX 2026-06-22: store the `file://` URI to the on-disk
                // copy, NOT the `content://` FileProvider URI we hand to the
                // camera. `Uri.parse(content://...).path` returns
                // `/chat_images/<name>` (i.e. the authority's exposed path)
                // instead of `/data/data/.../files/chat_images/<name>` — so
                // `resolveImagePayload` later did `File(p).readBytes()` on a
                // path that does not exist and silently dropped the image
                // from the Gemini request. The TakePicture contract has
                // already written the bytes into [file]; persist its
                // `file://` URI so downstream readers (and the chat
                // thumbnail) can open it with the absolute path.
                viewModel.appendChatImagePath(android.net.Uri.fromFile(file).toString())
            } else {
                file.delete()
            }
        } else {
            file?.delete()
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            launchCamera(context, cameraLauncher) { f, u ->
                pendingCameraFile = f
                pendingCameraUri = u
            }
        }
    }
    // Hoisted callback so both ChatInputBar call sites (empty-state and
    // populated-state) reuse the same chooser without duplicating the
    // permission/launcher dance.
    val onAttachClick: () -> Unit = { showAttachSheet = true }

    // 2026-06-15: ScreenHeader was previously inside Scaffold's topBar, which
    // sits INSIDE ModalNavigationDrawer — so the drawer sheet covered the
    // hamburger button when open. Hoisting the header above the drawer keeps
    // the hamburger always visible (and clickable as a toggle), and limits
    // the drawer + scrim to the area below it, which is exactly what Hugo
    // asked for. The Column glues the two stacked regions together; the
    // ModalNavigationDrawer takes Modifier.weight(1f) to consume the rest.
    Column(modifier = Modifier.fillMaxSize()) {
        com.example.ui.components.ScreenHeader(
            title = tr("Chat", userProfile.language),
            subtitle = activeSession?.title?.takeIf { it.isNotBlank() }
                ?: tr("Conversations with the diet companion", userProfile.language),
            leadingSlot = {
                // Hamburger toggle (Phase 4 fix v2 2026-06-13): closes when
                // open, opens when closed. Icon swaps to Close while open
                // for visual feedback.
                IconButton(onClick = {
                    coroutineScope.launch {
                        if (drawerState.isOpen) drawerState.close()
                        else drawerState.open()
                    }
                }) {
                    Icon(
                        imageVector = if (drawerState.isOpen) {
                            Icons.Rounded.Close
                        } else {
                            Icons.Rounded.Menu
                        },
                        contentDescription = tr("Conversations", userProfile.language),
                        tint = Color.White
                    )
                }
            },
            trailingSlot = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Search icon (2026-06-15): opens a full-screen search
                    // overlay that runs FTS4 over every conversation.
                    // Toggling it closed clears the query so the next open
                    // starts fresh.
                    IconButton(onClick = {
                        showSearchOverlay = !showSearchOverlay
                        if (!showSearchOverlay) viewModel.clearSearchQuery()
                    }) {
                        Icon(
                            imageVector = if (showSearchOverlay) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = tr("Search", userProfile.language),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { viewModel.createSession() }) {
                        Icon(
                            imageVector = Icons.Rounded.AddCircle,
                            contentDescription = tr("New Workspace", userProfile.language),
                            tint = AccentSoft,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    if (activeSession != null) {
                        IconButton(onClick = { showConfigDialog = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = tr("Settings", userProfile.language),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = {
                            viewModel.clearHistory(activeSession!!.id)
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = tr("Clear Session", userProfile.language),
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        )

    ModalNavigationDrawer(
        modifier = Modifier.weight(1f),
        drawerState = drawerState,
        // Drawer só abre via hamburger button (Icons.Rounded.Menu); a
        // edge-swipe-from-left ficaria em conflito com a swipe-between-tabs
        // do HorizontalPager.
        //
        // gesturesEnabled = drawerState.isOpen (não `false` fixo): com `false`
        // o scrim ainda consome eventos mas o onDismissRequest interno do
        // Material3 vira no-op — verificado por screenrecord, ver memória
        // [[drawer-bleed-fix-pattern]]. Resultado: tap fora da gaveta deixa
        // de fechar (regressão notada em 2026-06-15).
        //
        // Drawer aberto  → true  → scrim-tap fecha (e não há swipe-to-open
        //                          a competir porque já está aberto).
        // Drawer fechado → false → o pager continua a apanhar a swipe
        //                          horizontal para mudar de tab.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            if (!isVisible) {
                // Belt-and-braces: mesmo que o drawerState fique aberto durante
                // um frame após sair desta tab (race entre snapTo e a recomposição
                // do pager), não há conteúdo opaco para vazar para a tab vizinha.
                ModalDrawerSheet(
                    modifier = Modifier.fillMaxHeight().width(0.dp),
                    drawerContainerColor = Color.Transparent,
                    drawerContentColor = Color.Transparent
                ) {}
            } else {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp),
                drawerContainerColor = Color(0xFF0F172A),
                drawerContentColor = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header redundante removido — o ScreenHeader da tab já mostra
                    // "Chat / <session subtitle>" por cima do drawer. Scrim-tap e
                    // back-press continuam a fechar o drawer.

                    Button(
                        onClick = {
                            // Build 1.5 (2026-06-27): no dialog — the
                            // session-type picker was removed and the
                            // optional custom-guidelines field is a power
                            // user feature better surfaced from the
                            // existing session "Edit" affordance. Tapping
                            // here creates a fresh session immediately
                            // with the unified Nutrition+Training prompt.
                            viewModel.createSession()
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_chat_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Icon(imageVector = Icons.Rounded.Add, contentDescription = tr("New", userProfile.language))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("New Consultation", userProfile.language), fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 2026-06-29 — Frente 2 MemoryScreen entry point.
                    // Surfaces the user_insights table so Hugo can audit
                    // / soft-delete facts that the assistant claims to
                    // remember. Click → opens a dedicated screen as an
                    // overlay (no separate route — keeps drawer in scope).
                    OutlinedButton(
                        onClick = {
                            showMemoryDialog = true
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_memory_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = AccentSoft,
                        ),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Icon(imageVector = Icons.Rounded.Star, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            tr("Memory", userProfile.language),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = tr("Consultation Channels", userProfile.language),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    if (sessions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No active chats.\nPress New above!",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF64748B)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(sessions, key = { it.id }) { s ->
                                val isSelected = s.id == selectedSessionId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) Color(0xFF1E293B)
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            viewModel.selectSession(s.id)
                                            coroutineScope.launch { drawerState.close() }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Rounded.ChatBubble
                                        else Icons.Rounded.ChatBubbleOutline,
                                        contentDescription = tr("Discussion", userProfile.language),
                                        tint = if (isSelected) AccentSoft else Color(0xFF94A3B8),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = s.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = String.format(tr("Engine: %s", userProfile.language), s.model.substringAfterLast(".")),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF64748B),
                                            maxLines = 1
                                        )
                                    }

                                    if (isSelected) {
                                        IconButton(
                                            onClick = { showRenameDialog = s },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = tr("Rename", userProfile.language),
                                                modifier = Modifier.size(14.dp),
                                                tint = Color(0xFF3B82F6)
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteSession(s) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Delete,
                                                contentDescription = tr("Delete", userProfile.language),
                                                modifier = Modifier.size(14.dp),
                                                tint = Color(0xFFEF4444)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    ) {
        Scaffold(
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            // 2026-06-15: topBar removed — ScreenHeader now lives outside the
            // ModalNavigationDrawer (in the parent Column) so the drawer sheet
            // and scrim only cover the area below the hamburger button.
            containerColor = Color(0xFF0B0F19)
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 2026-06-18 ROUND 5: with windowSoftInputMode adjustResize
                    // on the manifest AND the outer bottomBar gated on
                    // WindowInsets.isImeVisible (line ~301), the outer
                    // Scaffold's innerPadding.bottom already collapses to just
                    // the IME inset when the keyboard is open. The nested
                    // Scaffold here uses contentWindowInsets = (0,0,0,0) so it
                    // doesn't re-consume anything; this Box just relays the
                    // already-correct paddingValues. Adding .imePadding() here
                    // double-consumes the IME inset and produces a visible
                    // dead band above the keyboard
                    // (cf. [[scaffold-imepadding-double-trap]] and
                    //  [[scaffold-bottombar-ime-gate]]).
                    .padding(paddingValues)
            ) {
                if (showSearchOverlay) {
                    // Cross-conversation search panel. Full-bleed over the
                    // chat surface so the user can scroll long result lists
                    // without competing with the input bar. Closing via the
                    // header X (or BackHandler) exits and restores the chat.
                    ChatSearchOverlay(
                        viewModel = viewModel,
                        userProfileLang = userProfile.language,
                        // Gate auto-focus on the SETTLED-on-Chat signal, not the
                        // eager currentPage-based isVisible — otherwise a mid-settle
                        // currentPage==CHAT reading arms the delayed requestFocus()
                        // and the keyboard rises over Painel.
                        isVisible = isSettledOnChat,
                        onResultClick = { sessionId, messageId ->
                            // Closes itself, switches the active session, and
                            // arms a one-shot "jump to message" latch the
                            // chat list will consume on its next composition.
                            showSearchOverlay = false
                            viewModel.jumpToMessage(sessionId, messageId)
                        },
                        onClose = {
                            showSearchOverlay = false
                            viewModel.clearSearchQuery()
                        }
                    )
                } else if (viewModel.isApiKeyMissing) {
                    ApiKeyMissingLayout(lang = userProfile.language)
                } else if (activeSession == null) {
                    // Phase A: ChatViewModel auto-creates a session whenever
                    // the list is empty, so this branch is only ever a brief
                    // (sub-frame) transient between launch and the first
                    // emission. Show the suggestions skeleton instead of the
                    // old NoActiveSessionLayout — by the next recomposition we
                    // already have an active session.
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 2026-06-18: BottomCenter (was Center) — Hugo wants the
                        // suggestions card hugging the input bar, not floating
                        // mid-screen with a void between the card and the
                        // keyboard. Vazio passa para o topo, abaixo do header.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            EmptyChatSuggestionsLayout(
                                systemInstruction = "",
                                lang = userProfile.language
                            )
                        }
                        ChatInputBar(
                            inputText = inputText,
                            onTextChanged = { viewModel.updateInputText(it) },
                            // Phase 3 (#4): in edit mode, the Save button
                            // routes through confirmEditAndResubmit which
                            // cascades a delete + replays sendMessage with
                            // the new text and the carried image payload.
                            onSendClicked = {
                                if (isEditing) {
                                    viewModel.confirmEditAndResubmit()
                                } else {
                                    viewModel.sendMessage()
                                }
                            },
                            isTyping = isTyping,
                            attachedImageBase64 = attachedChatImageBase64,
                            onAttachImageClicked = onAttachClick,
                            onRemoveAttachedImage = { viewModel.attachChatImage(null) },
                            lang = userProfile.language,
                            attachedImagePaths = attachedChatImagePaths,
                            onRemoveAttachedImageAt = { idx -> viewModel.removeChatImagePathAt(idx) },
                            isEditing = isEditing,
                            onCancelEdit = { viewModel.cancelEditing() },
                            voiceMode = viewModel.voiceMode.collectAsState().value,
                            onToggleVoiceMode = { viewModel.toggleVoiceMode() },
                            speakingMessageId = viewModel.speakingMessageId.collectAsState().value,
                            onReadLastAssistant = { viewModel.speakLastAssistantMessage() }
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val listState = rememberLazyListState()
                        val haptic = LocalHapticFeedback.current
                        val coroutineScope = rememberCoroutineScope()
                        // 2026-06-18: tap-on-message-area to dismiss the
                        // keyboard. The pointerInput on the Box below picks
                        // this up only when the IME is open AND no bubble is
                        // selected — bubble long-press / tap survive
                        // unchanged because each bubble's
                        // Modifier.combinedClickable is a child consumer and
                        // wins over the parent's detectTapGestures.
                        val keyboardController = LocalSoftwareKeyboardController.current
                        @OptIn(ExperimentalLayoutApi::class)
                        val isImeOpen = WindowInsets.isImeVisible

                        // 2026-06-15 redesign: bottom-up rendering via
                        // `reverseLayout = true`. This is how WhatsApp /
                        // Telegram / Signal / Messages all render chat
                        // timelines, and it eliminates an entire category of
                        // bugs that the previous top-anchored approach kept
                        // re-introducing:
                        //
                        //   - "Open session lands on first message"
                        //   - "Scroll to bottom FAB lands mid-list"
                        //   - LaunchedEffect races vs. Room's initial empty
                        //     emit (see [[room-flow-empty-first-emission-trap]])
                        //   - Trailing Spacer + viewport math + snapshotFlow
                        //     two-pass dance for the FAB
                        //
                        // With reverseLayout=true:
                        //   * The list grows visually upward; index 0 is the
                        //     LAST (most recent) message, anchored to the
                        //     bottom of the viewport. So the natural rest
                        //     state IS "bottom" — no LaunchedEffect needed
                        //     for initial scroll, no race vs. Flow emits.
                        //   * "Scroll to bottom" becomes literally
                        //     `animateScrollToItem(0)` — one line, no
                        //     viewport math, no two-pass strategy.
                        //   * `firstVisibleItemIndex == 0` is the canonical
                        //     "user is at the bottom" check; we use a small
                        //     tolerance (≤2) to absorb the typing-indicator
                        //     bubble flicker without re-showing the FAB.
                        //   * Items pass `activeMessages.asReversed()` (a
                        //     view, not a copy — O(1)) so the natural list
                        //     order is preserved at the source of truth and
                        //     the VM/Room layer doesn't need to know about
                        //     the rendering choice.

                        // Haptic notification on new message. Hugo's rule
                        // (2026-06-14): the user owns the scroll position.
                        // We only buzz a soft tick to signal "something
                        // arrived". Skip rules:
                        //   - First composition (previousSize == 0) — that's
                        //     the initial Room emit, not a real new message.
                        //   - Last message is from the user — they just
                        //     tapped Send and don't need a duplicate tick.
                        //   - Size shrunk or stayed equal — only growth via
                        //     the tail counts.
                        var previousSize by remember(activeSession?.id) { mutableStateOf(0) }
                        LaunchedEffect(activeMessages.size) {
                            val prev = previousSize
                            val now = activeMessages.size
                            if (prev > 0 && now > prev) {
                                val last = activeMessages.lastOrNull()
                                if (last != null && last.sender != "user") {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                            previousSize = now
                        }

                        // 2026-06-18 (revised v2): auto-scroll only when the
                        // user just sent. Hugo's rule (Image 3 → Image 4
                        // regression report): when the assistant's answer
                        // lands, the viewport must NOT move. The user's
                        // reading position is sacred; the new answer grows
                        // below the fold and the user scrolls down manually
                        // when he wants to read it. The "scroll-to-bottom"
                        // FAB (further down in this Box) is the explicit way
                        // to jump to the tail of the latest answer.
                        //
                        // Cases that still land on bottom *without* this
                        // effect doing anything:
                        //   - App reopens on Chat tab: reverseLayout=true
                        //     anchors the LazyColumn at index 0 by
                        //     construction (its natural rest state).
                        //   - Switching session via the drawer: the list is
                        //     recomposed against a new activeMessages, the
                        //     state resets to index 0 the same way.
                        //   - FAB tap: animateScrollToItem(0) below.
                        var lastScrollAnchorMessageId by remember(activeSession?.id) {
                            mutableStateOf<Long?>(null)
                        }
                        val density = LocalDensity.current
                        // 2026-07-03 — viewport height in dp, derived from
                        // the existing `listState.layoutInfo`. Consumed by
                        // the `post_send_filler` item at the tail of the
                        // LazyColumn so that `scrollToItem(userMsg)` on
                        // send can hoist the just-sent user bubble to the
                        // viewport TOP (ChatGPT-style). Without a real
                        // trailing item, `scrollToItem` silently clamps at
                        // the content end and the bubble stays at the
                        // bottom on short chats — see plan
                        // `see-last-snap-need-linear-sloth.md`.
                        //
                        // derivedStateOf keeps recomposition surface tiny:
                        // the value only invalidates when the viewport
                        // px height actually changes (rotation, drawer,
                        // IME). Same pattern used by the step-3 anchor
                        // effect at L4720. Guard on `> 0.dp` handles the
                        // very first frame before layoutInfo has any
                        // viewport data.
                        val viewportHeightDp by remember(listState, density) {
                            derivedStateOf {
                                val info = listState.layoutInfo
                                val px = info.viewportEndOffset - info.viewportStartOffset
                                if (px <= 0) 0.dp else with(density) { px.toDp() }
                            }
                        }

                        // 2026-07-04 — DYNAMIC filler using the last
                        // message row as the natural bottom anchor.
                        // The timestamp inside each `MessageBubbleItem`
                        // is the visible "end of that message"; the
                        // LazyColumn item key `"msg_$lastId"` is the
                        // stable, self-updating bookmark that always
                        // points at whatever message currently sits
                        // at the tail (user msg during typing, AI
                        // reply after it persists).
                        //
                        // Formula:
                        //   spanPx  = (lastEntry.offset + lastEntry.size) - userEntry.offset
                        //   needed  = viewportPx - spanPx - topGap
                        //   filler  = max(0, needed.toDp())
                        //
                        // Result:
                        //   * Short reply → filler makes up the deficit
                        //     so scrollToItem(userMsg, -12dp) un-clamps.
                        //   * Tall reply → spanPx >= viewport, filler
                        //     collapses to 0. Zero wasted space below
                        //     the tail once the reply itself provides
                        //     the room.
                        //   * Timestamp / edited marker / proposal
                        //     cards / receipts attached to the last
                        //     message are all included in
                        //     `lastEntry.size` — no special-casing.
                        val lastUserIdForFiller = remember(activeMessages) {
                            activeMessages.lastOrNull { it.sender == "user" }?.id
                        }
                        val lastMessageIdForFiller = remember(activeMessages) {
                            activeMessages.lastOrNull()?.id
                        }
                        val fillerHeightDp by remember(
                            listState,
                            density,
                            lastUserIdForFiller,
                            lastMessageIdForFiller,
                        ) {
                            derivedStateOf {
                                val userId = lastUserIdForFiller ?: return@derivedStateOf 0.dp
                                val lastId = lastMessageIdForFiller ?: return@derivedStateOf 0.dp
                                val info = listState.layoutInfo
                                val viewportPx = info.viewportEndOffset - info.viewportStartOffset
                                if (viewportPx <= 0) return@derivedStateOf 0.dp
                                val visible = info.visibleItemsInfo
                                val userEntry = visible.firstOrNull { it.key == "msg_$userId" }
                                val lastEntry = visible.firstOrNull { it.key == "msg_$lastId" }
                                if (userEntry == null || lastEntry == null) {
                                    // Safety net — before layout settles
                                    // (or when the user has scrolled far
                                    // from the pin), overshoot with a
                                    // full viewport so scrollToItem
                                    // never clamps. The pin effect will
                                    // re-derive after the scroll
                                    // completes; filler will then
                                    // shrink to its true value on the
                                    // next frame.
                                    return@derivedStateOf with(density) { viewportPx.toDp() }
                                }
                                val topGap = with(density) { 12.dp.roundToPx() }
                                val spanPx = (lastEntry.offset + lastEntry.size) - userEntry.offset
                                val neededPx = viewportPx - spanPx - topGap
                                if (neededPx <= 0) 0.dp else with(density) { neededPx.toDp() }
                            }
                        }
                        // 2026-07-03 — keyed on the LAST USER msg id, not
                        // `activeMessages.lastOrNull()?.id`. Root cause of
                        // "envio → ecrã fica preso na minha msg, nunca
                        // revela o início da bolha da IA" (Hugo, since
                        // day one): the effect ran three sequential
                        // steps (scroll to user msg → wait isTyping →
                        // scroll to AI bubble). For fast Gemini Flash
                        // replies, the orchestrator's
                        // `chatDao.insertMessage(assistantMsg)`
                        // (ChatOrchestrator.kt:732) fires BEFORE step 3
                        // runs. Room Flow bumps `activeMessages` →
                        // `lastOrNull()?.id` changes to the assistant
                        // msg id → LaunchedEffect **cancels the running
                        // coroutine** and relaunches with `sender ==
                        // "assistant"`, which fails the guard and does
                        // nothing. Step 3 (`scrollToItem(aiTarget, 0)`)
                        // never runs.
                        //
                        // Fix: key on the last USER msg id. Assistant
                        // persistence doesn't change this, so the
                        // coroutine keeps running through step 3 even
                        // if the reply lands before we're done
                        // anchoring. `remember(activeMessages)` recomputes
                        // whenever the list reference changes (which is
                        // on every Room emit — the list is not the
                        // same instance after mutation).
                        val lastUserMsgId = remember(activeMessages) {
                            activeMessages.lastOrNull { it.sender == "user" }?.id
                        }

                        // 2026-07-04 — Chat scroll model. See memory
                        // [[chat-user-bubble-top-pin-2026-07-03]] for
                        // the full history and rationale. Summary:
                        //   1. Persistent viewport-height `post_send_filler`
                        //      trailing item (gated on
                        //      `lastScrollAnchorMessageId != null`,
                        //      NOT on `isTyping`) so `scrollToItem`
                        //      never clamps.
                        //   2. Pin the user msg to viewport top on the
                        //      lastUserMsgId change (send moment).
                        //   3. Re-pin on the isTyping true→false
                        //      transition (LazyColumn's key-based
                        //      preservation loses the anchor when
                        //      the streaming_bubble key disappears).
                        //
                        // Both pin sites use the same index-compute
                        // helper `userMsgLazyIndex` (mirrors
                        // `chatTimelineRows`) and the same suspend
                        // helper `pinToTop` (one frame wait via
                        // `withFrameNanos`, single `scrollToItem`,
                        // one-line debug log). This keeps the two
                        // effects tiny and eliminates the drift-prone
                        // `visibleItemsInfo` fallback path that the
                        // earlier iterations of this code went
                        // through.
                        val topGapPx = remember(density) {
                            with(density) { 12.dp.roundToPx() }
                        }

                        LaunchedEffect(lastUserMsgId) {
                            val userId = lastUserMsgId ?: return@LaunchedEffect
                            if (lastScrollAnchorMessageId == userId) return@LaunchedEffect
                            lastScrollAnchorMessageId = userId
                            val target = userMsgLazyIndex(activeMessages, userId)
                            if (target < 0) return@LaunchedEffect
                            pinUserMsgToTop(listState, target, topGapPx, tag = "send", userId = userId)
                        }

                        val currentIsTyping = isTyping
                        LaunchedEffect(lastUserMsgId, currentIsTyping) {
                            if (currentIsTyping) return@LaunchedEffect
                            val userId = lastUserMsgId ?: return@LaunchedEffect
                            val target = userMsgLazyIndex(activeMessages, userId)
                            if (target < 0) return@LaunchedEffect
                            pinUserMsgToTop(listState, target, topGapPx, tag = "end-of-turn", userId = userId)
                        }


                        // 2026-06-15 jump-to-message latch from the search
                        // overlay. When the user taps a search result the
                        // ViewModel stores the target message id; we read it
                        // here, scroll to that message, briefly highlight it,
                        // and clear the latch. The reverseLayout means the
                        // index in the LazyColumn is `(size-1) - chronoIndex`
                        // (newest is row 0). The highlight is a Compose-only
                        // state — MessageBubbleItem reads it via the bridge
                        // helper hugoSearchHighlightId below.
                        val pendingJumpId by viewModel.pendingJumpToMessageId.collectAsState()
                        LaunchedEffect(pendingJumpId, activeMessages.size) {
                            val target = pendingJumpId ?: return@LaunchedEffect
                            // Wait until the active session's messages have
                            // actually loaded for the target session — the
                            // jump may have switched session and the new list
                            // hasn't arrived yet.
                            val chronoIdx = activeMessages.indexOfFirst { it.id == target }
                            if (chronoIdx < 0) return@LaunchedEffect
                            val reverseIdx = (activeMessages.size - 1) - chronoIdx
                            // Clamp to a sane range and scroll a little
                            // higher than the bottom so the bubble lands in
                            // the middle of the viewport (more obvious match
                            // visual than "just visible at the bottom edge").
                            listState.animateScrollToItem(reverseIdx.coerceAtLeast(0))
                            highlightedMessageId = target
                            kotlinx.coroutines.delay(1600L)
                            highlightedMessageId = null
                            viewModel.consumeJumpRequest()
                        }

                        if (activeMessages.isEmpty()) {
                            // 2026-06-18: switched from
                            // Column.weight(1f).verticalScroll().Center
                            // to Box.weight(1f) with contentAlignment.
                            // The verticalScroll-inside-weight(1f) combo
                            // was confusing the layout pass — when the
                            // soft keyboard opened, the inner suggestions
                            // ended up rendering with stale measurements
                            // and the ChatInputBar floated mid-screen
                            // instead of hugging the top of the keyboard.
                            // Box.weight(1f) gives us a stable container
                            // and Alignment.Center keeps the visual style.
                            //
                            // 2026-06-18 (later): switched Center →
                            // BottomCenter so the suggestions card sits
                            // immediately above the input bar with no
                            // empty gap between them. Hugo's preference;
                            // see the screenshot in the redesign chat.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                EmptyChatSuggestionsLayout(
                                    systemInstruction = activeSession?.systemInstruction ?: "",
                                    lang = userProfile.language
                                )
                            }
                        } else {
                            // Box wrapper so the "scroll to bottom" FAB can
                            // overlay the LazyColumn at BottomEnd without
                            // affecting layout flow.
                            //
                            // 2026-06-15: when a bubble is in selection mode,
                            // a tap on empty timeline area (around bubbles, on
                            // the FAB clear zone, etc.) exits the mode. The
                            // pointerInput is gated on `selectedMessageId !=
                            // null` so it only intercepts when needed; when no
                            // bubble is selected the modifier is a no-op and
                            // taps fall through to children unchanged. The key
                            // for `pointerInput` includes `selectedMessageId`
                            // so the lambda re-binds whenever selection state
                            // flips, ensuring the latest setter is captured.
                            //
                            // 2026-06-18: when the IME is open and no bubble
                            // is selected, a single tap anywhere on the
                            // message area dismisses the keyboard. Long-press
                            // on bubbles is preserved because each bubble's
                            // own combinedClickable consumes the gesture
                            // first (child wins). Selection mode keeps
                            // priority — its tap-to-deselect runs first.
                            // Build 2 v9 (2026-06-28): back to the
                            // simple Box-with-weight wrapper. The v7
                            // Column-wrapper hack (LazyColumn + sibling
                            // streaming bubble) was needed only because
                            // reverseLayout=true forced the bubble out
                            // of the list to avoid bottom-anchor
                            // pushback. With reverseLayout=false the
                            // streaming bubble lives back inside the
                            // LazyColumn as its last item — no extra
                            // wrapper needed.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .pointerInput(selectedMessageId, isImeOpen) {
                                        when {
                                            selectedMessageId != null -> {
                                                detectTapGestures(onTap = {
                                                    selectedMessageId = null
                                                })
                                            }
                                            isImeOpen -> {
                                                    detectTapGestures(onTap = {
                                                        keyboardController?.hide()
                                                    })
                                                }
                                            }
                                        }
                                ) {
                                // Build 2 v9 (2026-06-28): switched to a
                                // normal LazyColumn (reverseLayout =
                                // false, the Compose default). Reason:
                                // the previous reverseLayout=true layout
                                // fought the streaming bubble's growth
                                // in every possible direction — every
                                // workaround (v2..v8) hit a different
                                // wall (focal point drift, jitter,
                                // bottom-anchor pushback, end-of-message
                                // anchoring). Standard top-to-bottom
                                // layout + `scrollToItem(lastIndex)`
                                // on the messages.size key is the
                                // pattern ChatGPT/Gemini apps actually
                                // use, and it makes the streaming
                                // bubble grow naturally downward
                                // without any anchor surprises.
                                //
                                // [chatTimelineRows] now returns the
                                // list in chronological order (oldest
                                // first); the LazyColumn renders top
                                // to bottom and the most recent
                                // message ends up at the bottom of
                                // the viewport — which is where the
                                // streaming bubble grows from too.
                                val timelineRows = remember(activeMessages) {
                                    chatTimelineRows(activeMessages)
                                }
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    items(
                                        timelineRows,
                                        key = { row ->
                                            when (row) {
                                                is ChatTimelineRow.Header -> "header_${row.epochDay}"
                                                is ChatTimelineRow.Msg -> "msg_${row.message.id}"
                                            }
                                        }
                                    ) { row ->
                                        when (row) {
                                            is ChatTimelineRow.Header -> {
                                                DayHeaderChip(
                                                    epochDay = row.epochDay,
                                                    lang = userProfile.language
                                                )
                                            }
                                            is ChatTimelineRow.Msg -> {
                                                val msg = row.message
                                                // Wrap the bubble in a Box so we can
                                                // paint a brief search-jump accent
                                                // around it without modifying the
                                                // MessageBubbleItem internals.
                                                val isHighlighted = highlightedMessageId == msg.id
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .then(
                                                            if (isHighlighted) {
                                                                Modifier
                                                                    .clip(RoundedCornerShape(12.dp))
                                                                    .background(Color(0x33FACC15))
                                                                    .padding(2.dp)
                                                            } else Modifier
                                                        )
                                                ) {
                                                    MessageBubbleItem(
                                                        message = msg,
                                                        lang = userProfile.language,
                                                        viewModel = viewModel,
                                                        isSelectionActive = selectedMessageId == msg.id,
                                                        onEnterSelection = { selectedMessageId = msg.id },
                                                        onExitSelection = { selectedMessageId = null }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Build 2 v9 (2026-06-28): streaming
                                    // bubble + typing indicator live AT
                                    // THE END of the items list, so they
                                    // render visually BELOW the most
                                    // recent persisted message. Compose
                                    // grows them downward naturally
                                    // (no reverseLayout to fight). The
                                    // scroll-on-size-change effect below
                                    // pins the view to the start of the
                                    // assistant's reply when it begins.
                                    if (isTyping) {
                                        val live = streamingText
                                        if (!live.isNullOrBlank()) {
                                            item(key = "streaming_bubble") {
                                                StreamingAssistantBubble(
                                                    text = live,
                                                    onCancel = { viewModel.cancelSendJob() },
                                                )
                                            }
                                        } else {
                                            item(key = "typing_indicator") {
                                                TypingIndicatorBubble(
                                                    phaseText = typingPhase,
                                                    // 2026-06-29 — always pass onCancel while
                                                    // typing, not only when phaseText is
                                                    // non-null. The previous guard hid the X
                                                    // for the brief window between phase
                                                    // updates, which Hugo read as "the X
                                                    // doesn't work" (it was just absent).
                                                    onCancel = { viewModel.cancelSendJob() },
                                                )
                                            }
                                        }
                                    }

                                    // 2026-07-03 — bottom filler so
                                    // `scrollToItem(userMsgTarget)` at
                                    // L4649 can hoist the just-sent user
                                    // bubble to the viewport TOP
                                    // (ChatGPT-style). Height = one full
                                    // viewport; overshoot is safe because
                                    // scrollToItem uses offset 0. Only
                                    // emitted while the assistant is
                                    // generating (isTyping=true); once
                                    // HandleEvent.Done fires and the real
                                    // assistant bubble persists as a
                                    // normal Msg row, the spacer vanishes
                                    // and the list rests naturally above
                                    // ChatInputBar. See plan
                                    // `see-last-snap-need-linear-sloth.md`.
                                    // 2026-07-04 v3: filler stays present
                                    // AS LONG AS the last user msg is
                                    // still what we pin to (i.e., no new
                                    // send has occurred). Previously
                                    // gated on `isTyping` only, which
                                    // removed the filler the moment the
                                    // reply persisted → `scrollToItem`
                                    // clamped because there was no room
                                    // below the user msg for the AI
                                    // reply + breathing space →
                                    // viewport drifted to previous
                                    // content. Keeping the filler
                                    // after the reply persists ensures
                                    // `scrollToItem(userMsg, -12dp)`
                                    // ALWAYS un-clamps. Filler
                                    // disappears when the user sends
                                    // again (new anchor id) or opens
                                    // a different session.
                                    if (lastScrollAnchorMessageId != null && fillerHeightDp > 0.dp) {
                                        item(key = "post_send_filler") {
                                            Spacer(Modifier.height(fillerHeightDp))
                                        }
                                    }
                                }

                                // Build 2 v9 (2026-06-28): instant
                                // (non-animated) scrollToItem on every
                                // activeMessages.size change. Fires
                                // exactly twice per turn:
                                //
                                //   1. User sends → list grows by 1.
                                //      We scroll to the new bottom so
                                //      the user's question lands at
                                //      the bottom of the viewport,
                                //      with the typing indicator /
                                //      streaming bubble appearing
                                //      just below it (visually).
                                //
                                //   2. Assistant's reply persists →
                                //      list grows by 1 again. We
                                //      scroll to the new bottom — but
                                //      because the assistant's bubble
                                //      can be MUCH taller than the
                                //      viewport, scrollToItem with
                                //      offset 0 lands the TOP of the
                                //      bubble at the viewport top
                                //      (the LazyColumn shows from the
                                //      start of the item downward).
                                //      User reads from the beginning.
                                //
                                // 2026-06-29 — Hugo reported that the
                                // typing bubble still appears below the
                                // fold sometimes. Root cause: a fixed
                                // 48ms delay races against the layout
                                // pass and a plain `scrollToItem` only
                                // moves the TOP of the item into the
                                // viewport — when the prior message is
                                // taller than the viewport, the typing
                                // bubble itself may end up below the
                                // input bar. New strategy:
                                //   - `snapshotFlow` until the new last
                                //     item is in `visibleItemsInfo`
                                //     (layout actually measured it).
                                //   - `scrollToItem(lastIndex)` first.
                                //   - Then `scrollBy(positiveDelta)`
                                //     to push the BOTTOM of the last
                                //     item up to the bottom of the
                                //     viewport (only if delta > 0 — so
                                //     short bubbles stay anchored at
                                //     top, tall bubbles align at
                                //     bottom). This guarantees the
                                //     typing indicator is the very
                                //     last pixel visible.
                                // Tag CHAT_SCROLL for live diagnostics.
                                suspend fun scrollLastItemFullyVisible(reason: String) {
                                    val target = listState.layoutInfo.totalItemsCount - 1
                                    if (target < 0) {
                                        android.util.Log.d("CHAT_SCROLL", "$reason no items, skipping")
                                        return
                                    }
                                    // 2026-06-29 (round 4): switched strategy.
                                    // Previous rounds calculated `overflow` and
                                    // called `scrollBy(overflow)`, but a row
                                    // with lazy children (markdown reflow,
                                    // ChartCard, ProposalCards) keeps growing
                                    // after the measure → user lands mid-row.
                                    // New approach: scrollToItem to bring the
                                    // row into composition, then HAMMER
                                    // scrollBy with a value larger than any
                                    // realistic remaining content (1 million
                                    // px). LazyListState clamps the scroll to
                                    // the max content offset internally, so
                                    // we always end up at the absolute
                                    // bottom of the LAST item — no matter
                                    // what its final height turns out to be.
                                    //
                                    // The loop with delay survives the
                                    // multi-frame reflow case: after each
                                    // hammer-scroll we wait one frame, then
                                    // check if more content appeared below
                                    // (which would have shifted the bottom).
                                    runCatching { listState.scrollToItem(target) }
                                    val measured0 = kotlinx.coroutines.withTimeoutOrNull(500L) {
                                        androidx.compose.runtime.snapshotFlow {
                                            listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.index == target }
                                        }.filterNotNull().first()
                                    }
                                    if (measured0 == null) {
                                        android.util.Log.d(
                                            "CHAT_SCROLL",
                                            "$reason target=$target measure timed out (item likely gone)"
                                        )
                                        return
                                    }
                                    val viewportStart = listState.layoutInfo.viewportStartOffset
                                    android.util.Log.d(
                                        "CHAT_SCROLL",
                                        "$reason target=$target initialRowBottom=${measured0.offset + measured0.size} viewportEnd=${listState.layoutInfo.viewportEndOffset} viewportStart=$viewportStart"
                                    )
                                    // Hammer with a huge scrollBy. LazyListState
                                    // clamps internally so we land exactly at
                                    // canScrollForward = false.
                                    repeat(4) { iter ->
                                        runCatching { listState.scrollBy(1_000_000f) }
                                        kotlinx.coroutines.delay(32)
                                        val info = listState.layoutInfo
                                        val canScrollForward = listState.canScrollForward
                                        val last = info.visibleItemsInfo.lastOrNull()
                                        android.util.Log.d(
                                            "CHAT_SCROLL",
                                            "$reason iter=${iter + 1} canScrollForward=$canScrollForward lastIdx=${last?.index} lastRowBottom=${last?.let { it.offset + it.size }} viewportEnd=${info.viewportEndOffset}"
                                        )
                                        if (!canScrollForward) {
                                            // Reached the absolute bottom of
                                            // the content; nothing more to do.
                                            return
                                        }
                                    }
                                }

                                // 2026-07-02 (plan verifica-o-chat…) —
                                // REMOVED: `LaunchedEffect(activeMessages.size)`
                                // and `LaunchedEffect(isTyping, streamingText
                                // != null, isImeOpen)`, both of which called
                                // `scrollLastItemFullyVisible` at end-of-turn.
                                //
                                // Symptom Hugo reported: send a follow-up
                                // question, start reading the reply from the
                                // top, and when the streaming ends the
                                // viewport jumps to the absolute bottom of
                                // the just-persisted bubble, yanking the
                                // reader off the first lines.
                                //
                                // Cause: `ChatOrchestrator.handle` inserts
                                // the persisted assistant message in the DB
                                // BEFORE emitting `HandleEvent.Done`
                                // (ChatOrchestrator.kt:732 → :749). So Room
                                // Flow bumps `activeMessages.size` → Effect A
                                // fired hammer scroll; then the VM cleared
                                // `_streamingText` on Done → Effect B fired
                                // hammer scroll again. Two hammers per turn.
                                //
                                // Fix: don't force scroll at end-of-turn.
                                // The user's reading position at the top of
                                // the new reply is sacred. If they want to
                                // reach the tail (proposals, timestamps),
                                // the "scroll to bottom" FAB below is the
                                // explicit affordance — it appears
                                // automatically when the last item's bottom
                                // edge is off the viewport.
                                //
                                // NOTE on the ex-Effect-B "IME re-pin" trap
                                // (v173 status bubble hidden below the fold):
                                // that trap only mattered because the app
                                // used to force the user to the bottom; the
                                // status bubble had to stay in view because
                                // the viewport was pinned there. Now that
                                // the viewport is frozen where the user
                                // wants it, the status bubble being off-
                                // screen is expected — the FAB replaces
                                // that re-pin behavior. See memory
                                // [[chat-end-of-stream-hammer-scroll-trap]].
                                //
                                // 2026-07-02 (iteração 4 do plan verifica-o-
                                // chat…) — the typing indicator bring-into-
                                // view logic moved UP to the send-anchor
                                // effect (search: "iteração 4" above). A
                                // separate `LaunchedEffect(isTyping)` here
                                // raced the send-anchor effect and read
                                // stale `layoutInfo`. Now the send-anchor
                                // effect handles both user msg + typing
                                // indicator sequentially in a single
                                // deterministic coroutine.

                                // "Scroll to bottom" FAB. Visible whenever
                                // the very last pixel of the latest message
                                // is NOT pinned to the bottom of the viewport
                                // — i.e. the user has scrolled up OR the
                                // latest message is taller than the viewport
                                // and its tail is hidden below the fold.
                                //
                                // With reverseLayout=false (default), the
                                // last item in the list is rendered at the
                                // bottom of the viewport. "At bottom" means
                                // the last item is among the visible ones
                                // AND its bottom edge is within the
                                // viewport.
                                val isAtBottom by remember {
                                    derivedStateOf {
                                        val info = listState.layoutInfo
                                        val visible = info.visibleItemsInfo
                                        if (visible.isEmpty()) {
                                            true
                                        } else {
                                            val last = visible.last()
                                            // 2026-06-29 (round 2): "at bottom"
                                            // requires (a) the last item index
                                            // to be the last in the list AND
                                            // (b) that item's bottom edge to
                                            // actually fit inside the viewport.
                                            // The previous check only required
                                            // ONE visible pixel of the last
                                            // row, so the FAB hid the moment
                                            // the row's top entered the view —
                                            // even if the entire body was
                                            // still below the fold. Hugo
                                            // flagged this with the screenshot
                                            // where the FAB disappeared on the
                                            // "Resumo Nutricional" reply even
                                            // though half of it was unreachable.
                                            val rowBottom = last.offset + last.size
                                            last.index == info.totalItemsCount - 1 &&
                                                rowBottom <= info.viewportEndOffset
                                        }
                                    }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = !isAtBottom,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 16.dp, bottom = 8.dp),
                                    enter = androidx.compose.animation.fadeIn(),
                                    exit = androidx.compose.animation.fadeOut()
                                ) {
                                    SmallFloatingActionButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                // 2026-06-29 — bug fix:
                                                // animateScrollToItem(lastIdx)
                                                // alone lands the TOP of the
                                                // last bubble at the top of
                                                // the viewport. When the last
                                                // bubble is taller than the
                                                // viewport (long replies like
                                                // "Resumo Nutricional"), the
                                                // tail stays off-screen and
                                                // Hugo had to tap the FAB
                                                // 3× before reaching the
                                                // bottom. The shared helper
                                                // does scrollToItem + a
                                                // measured scrollBy(overflow)
                                                // so the LAST PIXEL of the
                                                // last bubble pins to the
                                                // bottom of the viewport on
                                                // a single tap.
                                                scrollLastItemFullyVisible("FAB tap")
                                            }
                                        },
                                        containerColor = Color(0xFF1E293B),
                                        contentColor = AccentSoft
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.KeyboardArrowDown,
                                            contentDescription = tr("Scroll to bottom", userProfile.language)
                                        )
                                    }
                                }
                            }
                        }

                        ChatInputBar(
                            inputText = inputText,
                            onTextChanged = { viewModel.updateInputText(it) },
                            onSendClicked = {
                                if (isEditing) {
                                    viewModel.confirmEditAndResubmit()
                                } else {
                                    viewModel.sendMessage()
                                }
                            },
                            isTyping = isTyping,
                            attachedImageBase64 = attachedChatImageBase64,
                            onAttachImageClicked = onAttachClick,
                            onRemoveAttachedImage = { viewModel.attachChatImage(null) },
                            lang = userProfile.language,
                            attachedImagePaths = attachedChatImagePaths,
                            onRemoveAttachedImageAt = { idx -> viewModel.removeChatImagePathAt(idx) },
                            isEditing = isEditing,
                            onCancelEdit = { viewModel.cancelEditing() },
                            voiceMode = viewModel.voiceMode.collectAsState().value,
                            onToggleVoiceMode = { viewModel.toggleVoiceMode() },
                            speakingMessageId = viewModel.speakingMessageId.collectAsState().value,
                            onReadLastAssistant = { viewModel.speakLastAssistantMessage() }
                        )
                    }
                }
            }
        }
    }
    } // 2026-06-15: closes the parent Column that holds [ScreenHeader] +
      // [ModalNavigationDrawer], so the drawer sheet starts below the header.

    // Phase 3 (#6): attach picker. Replaces the previous direct-launch
    // chatGalleryLauncher.launch(...) with a 2-option chooser. Galeria
    // path is unchanged from before — Câmara dispatches through a
    // permission check + cameraLauncher (pre-built above so it survives
    // recomposition). The sheet itself is style-matched to the long-press
    // sheet (same containerColor and ActionRow component).
    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            containerColor = Color(0xFF0F172A)
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                ActionRow(
                    icon = Icons.Rounded.PhotoLibrary,
                    label = tr("Gallery", userProfile.language),
                    onClick = {
                        showAttachSheet = false
                        chatGalleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
                ActionRow(
                    icon = Icons.Rounded.PhotoCamera,
                    label = tr("Camera", userProfile.language),
                    onClick = {
                        showAttachSheet = false
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.CAMERA
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            launchCamera(context, cameraLauncher) { f, u ->
                                pendingCameraFile = f
                                pendingCameraUri = u
                            }
                        } else {
                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    // Modal dialogue rename
    if (showRenameDialog != null) {
        val s = showRenameDialog!!
        var renameText by remember { mutableStateOf(s.title) }

        Dialog(onDismissRequest = { showRenameDialog = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(tr("Rename Thread", userProfile.language), fontWeight = FontWeight.Bold, color = Color.White)
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AccentSoft
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showRenameDialog = null }) { Text(tr("Dismiss", userProfile.language), color = Color(0xFF94A3B8)) }
                        Button(
                            onClick = {
                                if (renameText.isNotBlank()) {
                                    viewModel.renameSession(s.id, renameText.trim())
                                }
                                showRenameDialog = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                        ) { Text(tr("Save", userProfile.language)) }
                    }
                }
            }
        }
    }

    // Model configurations tuning dialog
    if (showConfigDialog && activeSession != null) {
        val s = activeSession!!
        var draftModel by remember { mutableStateOf(s.model) }
        var draftInstruction by remember { mutableStateOf(s.systemInstruction) }

        Dialog(onDismissRequest = { showConfigDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(tr("Session Tuning Node", userProfile.language), fontWeight = FontWeight.Bold, color = Color.White)
                    Divider(color = Color(0xFF334155))

                    Text(tr("System Prompt", userProfile.language), color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(
                        value = draftInstruction,
                        onValueChange = { draftInstruction = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AccentSoft
                        ),
                        placeholder = { Text(tr("Act as a strict nutritionist assistant...", userProfile.language), fontSize = 12.sp, color = Color(0xFF64748B)) }
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showConfigDialog = false }) { Text(tr("Cancel", userProfile.language), color = Color(0xFF94A3B8)) }
                        Button(
                            onClick = {
                                viewModel.updateActiveSessionParams("gemini-3.5-flash", draftInstruction)
                                showConfigDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                        ) { Text(tr("Save changes", userProfile.language)) }
                    }
                }
            }
        }
    }

    // Session creator dialog REMOVED in Build 1.5b (2026-06-27): with the
    // session-type picker gone (single unified persona), the dialog only
    // surfaced an empty "Diretrizes Base" textbox that no one filled in.
    // Both entry points (+ in the header, "New Consultation" in the
    // drawer) now call viewModel.createSession() directly. Power-users
    // who want per-session custom guidelines can still set them through
    // the existing session Edit affordance.

    // 2026-06-29 — Frente Memory C4 entry. The drawer's "Memory" button
    // flips `showMemoryDialog`; the dialog itself lives here so it
    // overlays the chat without leaving the tab. State + dismiss callback
    // wire directly to the same `viewModel` the surrounding screen uses
    // (`allActiveInsights` flow, confirm/discard handlers).
    if (showMemoryDialog) {
        MemoryDialog(
            viewModel = viewModel,
            onDismiss = { showMemoryDialog = false },
        )
    }
}

// SessionTypeChips composable removed in Build 1.5b (2026-06-27): the
// 3-way session-type picker (personal / nutritionist / personal_trainer)
// is no longer surfaced anywhere in the UI — every session opens with
// the unified Nutrition+Training persona. ChatSession.sessionType field
// is kept for back-compat with sessions already in the DB.

// ==========================================
// TAB 5: REPORT
// ==========================================
enum class ActivityType(val displayKey: String, val color: Color) {
    RUNNING("Running", Color(0xFFEF4444)),
    WALKING("Walking", AccentPrimary),
    CYCLING("Cycling", Color(0xFF3B82F6)),
    SWIMMING("Swimming", Color(0xFF06B6D4)),
    STRENGTH("Strength / Gym", Color(0xFFF59E0B)),
    YOGA("Yoga / Stretch", Color(0xFF8B5CF6)),
    OTHER("Other / General", Color(0xFF94A3B8))
}

fun getActivityType(name: String): ActivityType {
    val lower = name.lowercase()
    return when {
         lower.contains("run") || lower.contains("jog") || lower.contains("corrida") || lower.contains("correr") -> ActivityType.RUNNING
         lower.contains("walk") || lower.contains("step") || lower.contains("caminh") || lower.contains("passo") -> ActivityType.WALKING
         lower.contains("cycl") || lower.contains("bik") || lower.contains("cicl") || lower.contains("bicic") || lower.contains("pedal") -> ActivityType.CYCLING
         lower.contains("swim") || lower.contains("natac") || lower.contains("nadar") -> ActivityType.SWIMMING
         lower.contains("gym") || lower.contains("lift") || lower.contains("weight") || lower.contains("strength") || lower.contains("treino") || lower.contains("muscul") || lower.contains("peso") || lower.contains("hiit") || lower.contains("crossfit") -> ActivityType.STRENGTH
         lower.contains("yoga") || lower.contains("stretch") || lower.contains("pilates") || lower.contains("along") -> ActivityType.YOGA
         else -> ActivityType.OTHER
    }
}

fun createFallbackActivityFromDescription(desc: String): com.example.data.local.ActivityEntry {
    val lower = desc.lowercase()
    val digits = Regex("\\d+").find(desc)?.value?.toIntOrNull()
    val duration = digits ?: 45
    
    val (name, factor) = when {
        lower.contains("run") || lower.contains("jog") || lower.contains("corrida") || lower.contains("correr") -> Pair("Running", 10.0)
        lower.contains("walk") || lower.contains("step") || lower.contains("caminh") || lower.contains("passo") -> Pair("Walking", 4.0)
        lower.contains("cycl") || lower.contains("bik") || lower.contains("cicl") || lower.contains("bicic") || lower.contains("pedal") -> Pair("Cycling", 8.0)
        lower.contains("swim") || lower.contains("natac") || lower.contains("nadar") -> Pair("Swimming", 9.0)
        lower.contains("gym") || lower.contains("lift") || lower.contains("weight") || lower.contains("strength") || lower.contains("treino") || lower.contains("muscul") || lower.contains("peso") || lower.contains("hiit") || lower.contains("crossfit") -> Pair("Strength / Gym", 6.0)
        lower.contains("yoga") || lower.contains("stretch") || lower.contains("pilates") || lower.contains("along") -> Pair("Yoga / Stretch", 3.0)
        else -> {
            val words = desc.split("\\s+".toRegex()).take(3).joinToString(" ")
            val capName = if (words.length > 25) words.substring(0, 22) + "..." else words
            Pair(if (capName.isNotBlank()) capName else "General Activity", 5.0)
        }
    }
    val cals = (duration * factor).toInt()
    return com.example.data.local.ActivityEntry(
        name = name,
        durationMinutes = duration,
        caloriesBurned = cals
    )
}

@Composable
fun DateSelectorBar(
    selectedDate: LocalDate,
    period: String,
    language: String,
    onDateChanged: (LocalDate) -> Unit
) {
    val context = LocalContext.current
    
    val dateDisplayLabel = remember(selectedDate, period, language) {
        val todayLocal = LocalDate.now(java.time.ZoneId.systemDefault())
        when (period) {
            "Daily" -> {
                if (selectedDate == todayLocal) {
                    "${tr("Today", language)}, ${selectedDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))}"
                } else if (selectedDate == todayLocal.minusDays(1)) {
                    "${tr("Yesterday", language)}, ${selectedDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))}"
                } else {
                    selectedDate.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", java.util.Locale.US))
                }
            }
            "Weekly" -> {
                val monday = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong())
                val sunday = monday.plusDays(6)
                val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)
                "${monday.format(formatter)} - ${sunday.format(formatter)}"
            }
            else -> {
                val formatter = java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.US)
                selectedDate.format(formatter)
            }
        }
    }

    val showDatePicker = {
        val dialog = android.app.DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                onDateChanged(LocalDate.of(year, month + 1, dayOfMonth))
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth
        )
        dialog.datePicker.firstDayOfWeek = java.util.Calendar.MONDAY
        dialog.show()
    }

    Card(
        modifier = Modifier.fillMaxWidth().height(42.dp).testTag("date_selector_bar"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                modifier = Modifier.size(32.dp),
                onClick = {
                    val newDate = when (period) {
                        "Daily" -> selectedDate.minusDays(1)
                        "Weekly" -> selectedDate.minusWeeks(1)
                        else -> selectedDate.minusMonths(1)
                    }
                    onDateChanged(newDate)
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = tr("Previous Period", language),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { showDatePicker() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.EditCalendar,
                    contentDescription = tr("Select Date", language),
                    tint = AccentPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = dateDisplayLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }

            IconButton(
                modifier = Modifier.size(32.dp),
                onClick = {
                    val newDate = when (period) {
                        "Daily" -> selectedDate.plusDays(1)
                        "Weekly" -> selectedDate.plusWeeks(1)
                        else -> selectedDate.plusMonths(1)
                    }
                    onDateChanged(newDate)
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowForward,
                    contentDescription = tr("Next Period", language),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun NutritionPieChart(
    protein: Double,
    carbs: Double,
    fat: Double,
    lang: String,
    modifier: Modifier = Modifier
) {
    val totalMacros = protein + carbs + fat
    if (totalMacros == 0.0) {
        Box(
            modifier = modifier
                .height(130.dp)
                .background(Color(0xFF1E293B), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(tr("No macro ratio data logged in this range", lang), color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        }
        return
    }

    val proteinAngle = (protein / totalMacros * 360f).toFloat()
    val carbsAngle = (carbs / totalMacros * 360f).toFloat()
    val fatAngle = (fat / totalMacros * 360f).toFloat()

    Card(
        modifier = modifier.height(130.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Canvas(modifier = Modifier.size(54.dp)) {
                var startAngle = -90f
                drawArc(
                    color = Color(0xFFF97316), // Protein
                    startAngle = startAngle,
                    sweepAngle = proteinAngle,
                    useCenter = true
                )
                startAngle += proteinAngle

                drawArc(
                    color = Color(0xFF8B5CF6), // Carbs
                    startAngle = startAngle,
                    sweepAngle = carbsAngle,
                    useCenter = true
                )
                startAngle += carbsAngle

                drawArc(
                    color = Color(0xFFEAB308), // Fat
                    startAngle = startAngle,
                    sweepAngle = fatAngle,
                    useCenter = true
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(tr("Macronutrients", lang), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                val protPercent = (protein / totalMacros * 100).toInt()
                val carbsPercent = (carbs / totalMacros * 100).toInt()
                val fatPercent = 100 - protPercent - carbsPercent
                TextLegendItem(color = Color(0xFFF97316), percentage = protPercent, label = tr("Protein", lang))
                TextLegendItem(color = Color(0xFF8B5CF6), percentage = carbsPercent, label = tr("Carbs", lang))
                TextLegendItem(color = Color(0xFFEAB308), percentage = Math.max(0, fatPercent), label = tr("Fats", lang))
            }
        }
    }
}

@Composable
fun ActivityPieChart(
    activities: List<ActivityEntry>,
    lang: String,
    modifier: Modifier = Modifier
) {
    val totalBurned = activities.sumOf { it.caloriesBurned }
    if (totalBurned == 0) {
        Box(
            modifier = modifier
                .height(130.dp)
                .background(Color(0xFF1E293B), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(tr("No activity logs registered in this window.", lang), color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        }
        return
    }

    val grouped = remember(activities) {
        ActivityType.values().map { type ->
            type to activities.filter { getActivityType(it.name) == type }.sumOf { it.caloriesBurned }
        }.filter { it.second > 0 }
    }

    Card(
        modifier = modifier.height(130.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Canvas(modifier = Modifier.size(54.dp)) {
                var startAngle = -90f
                grouped.forEach { (type, cals) ->
                    val sweep = (cals.toFloat() / totalBurned.toFloat() * 360f)
                    drawArc(
                        color = type.color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = true
                    )
                    startAngle += sweep
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(tr("Activities", lang), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                // 2026-06-26: mostrar todas as categorias com dados em vez de
                // colapsar as ≥3ª em "Others". Antes só `take(2)` tinha
                // legenda própria — Hugo queixou-se que a pie só mostrava
                // Corrida + Outra/Geral mesmo quando havia Bicicleta/Ginásio.
                // O card tem 130dp de altura e 4dp de spacing → cabem ~5
                // linhas confortavelmente; take(5) evita overflow visual em
                // semanas excepcionais com 6+ categorias distintas.
                grouped.take(5).forEach { (type, cals) ->
                    val percent = (cals.toDouble() / totalBurned * 100).toInt()
                    TextLegendItem(color = type.color, percentage = percent, label = tr(type.displayKey, lang))
                }
                if (grouped.size > 5) {
                    val remainingCals = grouped.drop(5).sumOf { it.second }
                    val percent = (remainingCals.toDouble() / totalBurned * 100).toInt()
                    TextLegendItem(color = Color(0xFF64748B), percentage = percent, label = tr("Others", lang))
                }
            }
        }
    }
}

// =============================================================
// Variant of ActivityPieChart that consumes the merged report
// rows (manuals + SH workouts + SH ambient residual). Lets the
// Reports tab render Samsung Health activity even when the user
// has zero manual log entries — same data the totals header is
// already summing.
// =============================================================
private fun ReportActivityRow.toActivityType(): ActivityType = when (source) {
    ReportActivityRow.Source.SAMSUNG_HEALTH_DAILY -> ActivityType.OTHER
    else -> getActivityType(name)
}

@Composable
fun ActivityPieChartFromRows(
    rows: List<ReportActivityRow>,
    lang: String,
    modifier: Modifier = Modifier
) {
    val totalBurned = rows.sumOf { it.caloriesBurned }
    if (totalBurned == 0) {
        Box(
            modifier = modifier
                .height(130.dp)
                .background(Color(0xFF1E293B), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(tr("No activity logs registered in this window.", lang), color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        }
        return
    }

    val grouped = remember(rows) {
        ActivityType.values().map { type ->
            type to rows.filter { it.toActivityType() == type }.sumOf { it.caloriesBurned }
        }.filter { it.second > 0 }
    }

    Card(
        modifier = modifier.height(130.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Canvas(modifier = Modifier.size(54.dp)) {
                var startAngle = -90f
                grouped.forEach { (type, cals) ->
                    val sweep = (cals.toFloat() / totalBurned.toFloat() * 360f)
                    drawArc(
                        color = type.color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = true
                    )
                    startAngle += sweep
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(tr("Activities", lang), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                // Ver comentário gémeo em ActivityPieChart (take(5)).
                grouped.take(5).forEach { (type, cals) ->
                    val percent = (cals.toDouble() / totalBurned * 100).toInt()
                    TextLegendItem(color = type.color, percentage = percent, label = tr(type.displayKey, lang))
                }
                if (grouped.size > 5) {
                    val remainingCals = grouped.drop(5).sumOf { it.second }
                    val percent = (remainingCals.toDouble() / totalBurned * 100).toInt()
                    TextLegendItem(color = Color(0xFF64748B), percentage = percent, label = tr("Others", lang))
                }
            }
        }
    }
}

// =============================================================
// HeartProgressCard — 3 corações concêntricos preenchidos
// proporcionalmente a (valor / goal). Estilo Samsung Health.
// =============================================================
private fun heartPath(size: Size): Path = Path().apply {
    val w = size.width
    val h = size.height
    // Cubic-bezier heart, normalized so the bottom tip is at (w/2, h*0.92)
    // and the top dip between lobes is at (w/2, h*0.30).
    moveTo(w / 2f, h * 0.92f)
    cubicTo(
        w * -0.10f, h * 0.60f,
        w * 0.10f, h * 0.05f,
        w / 2f, h * 0.30f
    )
    cubicTo(
        w * 0.90f, h * 0.05f,
        w * 1.10f, h * 0.60f,
        w / 2f, h * 0.92f
    )
    close()
}

@Composable
private fun HeartProgressLayer(
    progress: Float,
    color: Color,
    sizeFraction: Float,
    strokeWidthDp: Float = 14f,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val side = size.minDimension * sizeFraction
        val offX = (size.width - side) / 2f
        val offY = (size.height - side) / 2f
        val path = heartPath(Size(side, side))
        val sweep = progress.coerceIn(0f, 1f)
        val strokePx = strokeWidthDp.dp.toPx()
        val ribbonStroke = Stroke(
            width = strokePx,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round
        )
        translate(offX, offY) {
            // 1) Faded full-perimeter ribbon (always visible)
            drawPath(path, color = color.copy(alpha = 0.20f), style = ribbonStroke)
            // 2) Progress segment along the heart perimeter
            if (sweep > 0f) {
                val pm = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
                val total = pm.length
                if (total > 0f) {
                    val segment = Path()
                    pm.getSegment(0f, total * sweep, segment, true)
                    drawPath(segment, color = color, style = ribbonStroke)
                }
            }
        }
    }
}

@Composable
fun HeartProgressCard(
    steps: Int,
    targetSteps: Int,
    activeMins: Int,
    targetMins: Int,
    activityCals: Int,
    targetCals: Int,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Outermost layer: steps (green ribbon)
        HeartProgressLayer(
            progress = steps.toFloat() / targetSteps.coerceAtLeast(1),
            color = Color(0xFF4ADE80),
            sizeFraction = 1.0f,
            strokeWidthDp = 14f,
            modifier = Modifier.fillMaxSize()
        )
        // Middle layer: active minutes (blue ribbon)
        HeartProgressLayer(
            progress = activeMins.toFloat() / targetMins.coerceAtLeast(1),
            color = Color(0xFF60A5FA),
            sizeFraction = 0.74f,
            strokeWidthDp = 12f,
            modifier = Modifier.fillMaxSize()
        )
        // Innermost layer: activity calories (pink ribbon)
        HeartProgressLayer(
            progress = activityCals.toFloat() / targetCals.coerceAtLeast(1),
            color = Color(0xFFEC4899),
            sizeFraction = 0.48f,
            strokeWidthDp = 10f,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun ActivitySummaryTable(
    activities: List<ActivityEntry>,
    lang: String,
    modifier: Modifier = Modifier
) {
    val totalBurned = activities.sumOf { it.caloriesBurned }
    val totalDuration = activities.sumOf { it.durationMinutes }
    
    val grouped = remember(activities) {
        ActivityType.values().map { type ->
            val match = activities.filter { getActivityType(it.name) == type }
            val cals = match.sumOf { it.caloriesBurned }
            val duration = match.sumOf { it.durationMinutes }
            Triple(type, cals, duration)
        }.filter { it.second > 0 || it.third > 0 }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Activity Summary Table", lang), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            
            HorizontalDivider(color = Color(0xFF334155))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(tr("Activity Type", lang), color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f))
                Text(tr("Stats", lang), color = AccentSoft, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f), textAlign = TextAlign.End)
            }

            HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.5f))

            if (grouped.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tr("No exercises logged for this period.", lang), color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                grouped.forEach { (type, cals, duration) ->
                    ActivityTableRowItem(
                        name = tr(type.displayKey, lang),
                        valStr = "$cals kcal (${duration} ${tr("min", lang)})",
                        iconColor = type.color
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.5f))

            // Totals
            ActivityTableRowItem(
                name = tr("Total Energy Burned", lang),
                valStr = "$totalBurned kcal",
                iconColor = Color(0xFFEF4444)
            )
            ActivityTableRowItem(
                name = tr("Total Workout Time", lang),
                valStr = "$totalDuration min",
                iconColor = AccentPrimary
            )
            ActivityTableRowItem(
                name = tr("Workouts Count", lang),
                valStr = "${activities.size} ${tr("sessions", lang)}",
                iconColor = Color(0xFF3B82F6)
            )
        }
    }
}

@Composable
fun ActivityTableRowItem(name: String, valStr: String, iconColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(6.dp).background(iconColor, CircleShape))
            Text(name, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        Text(valStr, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f), textAlign = TextAlign.End)
    }
}

// =============================================================
// Variant of ActivitySummaryTable that consumes the merged report
// rows. Same shape as the manual-only table, but rows from
// Samsung Health workouts & ambient residual are included so the
// per-type breakdown matches the totals already shown in the
// Reports header.
// =============================================================
@Composable
fun ActivitySummaryTableFromRows(
    rows: List<ReportActivityRow>,
    lang: String,
    modifier: Modifier = Modifier,
    /** Soma das `distanceMeters` do `ShDailyActivity` no período. Quando
     *  `null` ou ≤ 0 a linha "Total Distance" é omitida — para callers que
     *  não têm acesso a Samsung Health, ou períodos sem distância. */
    distanceMeters: Float? = null
) {
    // DEPRECATED 2026-06-22: this wrapper is no longer used. On-screen and
    // share-PNG paths both go through ActivityReportCard now. Kept only as a
    // compile-time placeholder so an accidental external caller still gets a
    // valid Card; pending deletion in a future cleanup.
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        ActivitySummaryTableBody(
            rows = rows,
            lang = lang,
            totalBurnedKcal = rows.sumOf { it.caloriesBurned },
            distanceMeters = distanceMeters
        )
    }
}

/**
 * Inner column of [ActivitySummaryTableFromRows] / [ActivityReportCard].
 *
 * Layout (2026-06-22 redesign):
 *   1. "Resumo de Atividade" subtitle.
 *   2. **Totals first**: Total Queimado (highlighted) + Tempo Total + Distância
 *      (when present) + Sessões.
 *   3. Divider.
 *   4. "Distribuição por tipo" subtitle.
 *   5. Per-type rows.
 *
 * The `totalBurnedKcal` parameter is required and represents the value shown
 * in the headline "Total Queimado" row. Callers pass the figure that excludes
 * TMB (workouts + ambient) so the user reads "actividade física pura" without
 * resting metabolism muddying the number.
 */
/**
 * Per-bucket accumulator used by [ActivitySummaryTableBody]. Groups every
 * [ReportActivityRow] under its coarse [ActivityType] but also carries the
 * distinct fine-grained modalities (Pilates, Weight machine, ...) that live
 * inside that bucket for the current window.
 */
private data class BucketBreakdown(
    val type: ActivityType,
    val calories: Int,
    val duration: Int,
    /** Distinct sourceLabels present in this bucket, in encounter order. Null
     *  when no row in this bucket carried a sourceLabel — the renderer then
     *  falls back to `type.displayKey`. */
    val modalities: List<String>?
)

@Composable
private fun ActivitySummaryTableBody(
    rows: List<ReportActivityRow>,
    lang: String,
    totalBurnedKcal: Int,
    distanceMeters: Float? = null
) {
    val totalDuration = rows.sumOf { it.durationMinutes }

    val grouped = remember(rows) {
        ActivityType.values().map { type ->
            val match = rows.filter { it.toActivityType() == type }
            val cals = match.sumOf { it.caloriesBurned }
            val duration = match.sumOf { it.durationMinutes }
            // Collect the specific modalities that live inside this bucket
            // (Pilates, Weight machine, …) in encounter order, deduped. The
            // per-row `name` field also prefers user customTitle, which we
            // do NOT want here — this is a "what did you actually do"
            // breakdown, not "how did you label it". Falls back to null when
            // no row in this bucket carried a sourceLabel (legacy data /
            // manual quick-log path), which the renderer then reads as
            // "use the bucket displayKey" — matches pre-refactor behaviour.
            val modalities = match
                .mapNotNull { it.sourceLabel?.takeIf { s -> s.isNotBlank() } }
                .distinct()
                .takeIf { it.isNotEmpty() }
            BucketBreakdown(type, cals, duration, modalities)
        }.filter { it.calories > 0 || it.duration > 0 }
    }

    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr("reports.activity.summarySection", lang),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )

        HorizontalDivider(color = Color(0xFF334155))

        // Column headers — kept for visual symmetry with the Meals table.
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(tr("Metric Dimension", lang), color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f))
            Text(tr("Total Sum", lang), color = AccentSoft, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f), textAlign = TextAlign.End)
        }

        HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.5f))

        // Totals FIRST. "Total Queimado" gets the highlighted row treatment so
        // the eye lands on it without a separate LeadKpi block.
        TableRowItemHighlighted(
            name = tr("Total Energy Burned", lang),
            valStr = "$totalBurnedKcal kcal",
            iconColor = Color(0xFFEF4444),
            valueWeight = 1.5f
        )
        ActivityTableRowItem(
            name = tr("Total Workout Time", lang),
            valStr = "$totalDuration min",
            iconColor = AccentPrimary
        )
        if (distanceMeters != null && distanceMeters > 0f) {
            ActivityTableRowItem(
                name = tr("Total Distance", lang),
                valStr = String.format(
                    java.util.Locale("pt", "PT"),
                    "%.2f km",
                    distanceMeters / 1000f
                ),
                iconColor = Color(0xFF06B6D4)
            )
        }
        ActivityTableRowItem(
            name = tr("Workouts Count", lang),
            valStr = "${rows.size} ${tr("sessions", lang)}",
            iconColor = Color(0xFF3B82F6)
        )

        HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.5f))

        // Per-type breakdown SECOND.
        Text(
            tr("reports.activity.byTypeSection", lang),
            color = AccentSoft,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall
        )

        if (grouped.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(tr("No exercises logged for this period.", lang), color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            grouped.forEach { b ->
                // Per-bucket subtitle: prefer the concrete modalities the user
                // actually did (e.g. "Pilates" instead of "Yoga / Alongamento")
                // — see [[samsung-health-exercise-type-mapping]]. Multiple
                // distinct modalities in the same bucket are joined by ", ":
                //   { Pilates }              -> "Pilates"
                //   { Pilates, Yoga }        -> "Pilates, Yoga"
                //   { Weight machine, Push-ups, Squats } -> "Weight machine, Push-ups, Squats"
                // Falls back to the bucket displayKey only when nothing in the
                // bucket carried a sourceLabel (legacy manual entries), which
                // keeps pre-refactor behaviour for old data.
                val label = b.modalities?.joinToString(", ") ?: tr(b.type.displayKey, lang)
                ActivityTableRowItem(
                    name = label,
                    valStr = "${b.calories} kcal (${b.duration} ${tr("min", lang)})",
                    iconColor = b.type.color
                )
            }
        }
    }
}

// =============================================================
// Reports tab — redesigned wrappers around the existing detail
// tables. The wrappers add a `LeadKpi` highlight at the top and
// a collapsible "Evolução" stacked-bar chart at the bottom. The
// share-PNG path still goes through `NutritionTable` /
// `ActivitySummaryTableFromRows` directly so the partilhada
// image stays byte-identical to today. 2026-06-22.
// =============================================================

// 2026-06-22 — formatDeltaKcal / toneForDelta / deltaTextOrNull helpers
// removed: they powered the LeadKpi block that has since been retired. The
// `reports.lead.delta.*` i18n keys are kept in I18n.kt for possible future
// reuse.

/**
 * Collapsible "Evolução" section header + body. Hidden entirely when the
 * caller passes [visible] = false (e.g. timescale == "Daily"). Default
 * collapsed; tap header to expand.
 */
@Composable
private fun EvolutionSection(
    title: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (!visible) return
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.5f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp
                else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = AccentSoft
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

/**
 * Build [com.example.ui.components.BarBucket]s for the Meals chart. Each day
 * maps to three macro-kcal segments (protein/carbs/fat) so the bar's total
 * height equals the day's total kcal. Empty days → empty segment list →
 * chart paints the empty-day pill.
 */
private fun mealBucketsToBars(
    buckets: List<DailyMacroBucket>,
    lang: String
): List<com.example.ui.components.BarBucket> {
    val proteinColor = Color(0xFFF97316)
    val carbsColor = Color(0xFF8B5CF6)
    val fatColor = Color(0xFFEAB308)
    return buckets.map { b ->
        if (b.kcal == 0) {
            com.example.ui.components.BarBucket(
                date = b.date,
                segments = emptyList(),
                contentDescription = "${b.date}: ${tr("reports.lead.noData", lang)}"
            )
        } else {
            com.example.ui.components.BarBucket(
                date = b.date,
                segments = listOf(
                    com.example.ui.components.BarSegment(b.protein * 4.0, proteinColor),
                    com.example.ui.components.BarSegment(b.carbs * 4.0, carbsColor),
                    com.example.ui.components.BarSegment(b.fat * 9.0, fatColor)
                ),
                contentDescription = "${b.date}: ${b.kcal} kcal, " +
                    "${String.format("%.0f", b.protein)} g ${tr("Protein", lang)}, " +
                    "${String.format("%.0f", b.carbs)} g ${tr("Carbs", lang)}, " +
                    "${String.format("%.0f", b.fat)} g ${tr("Fats", lang)}"
            )
        }
    }
}

/**
 * Build [com.example.ui.components.BarBucket]s for the Activity chart. Each
 * day's segments are ordered: workout/manual kcal grouped by ActivityType (in
 * enum order for a stable palette across days), then SH ambient residual in
 * slate-500, then BMR in violet-ink (translucent) so the user can see the
 * resting-burn band but it doesn't compete with active burn.
 */
/**
 * Build [com.example.ui.components.BarBucket]s for the Activity chart. Each
 * day's segments are ordered: workout/manual kcal grouped by ActivityType (in
 * enum order for a stable palette across days), then SH ambient residual in
 * slate-500.
 *
 * BMR is intentionally EXCLUDED from the chart (and from the activity card's
 * headline value) — it's resting metabolism, not physical activity. The chart
 * shows what the user actually moved for. 2026-06-22.
 */
private fun activityBucketsToBars(
    buckets: List<DailyActivityBucket>,
    lang: String
): List<com.example.ui.components.BarBucket> {
    val ambientColor = Color(0xFF64748B)        // slate-500
    return buckets.map { b ->
        // Activity-only total (workouts + ambient, NO BMR). This is what the
        // bar height should represent.
        val activeKcal = b.totalKcal - b.bmrKcal
        if (activeKcal <= 0) {
            com.example.ui.components.BarBucket(
                date = b.date,
                segments = emptyList(),
                contentDescription = "${b.date}: ${tr("reports.lead.noData", lang)}"
            )
        } else {
            val typeSegments = ActivityType.values()
                .mapNotNull { t ->
                    val kcal = b.byType[t] ?: 0
                    if (kcal <= 0) null else com.example.ui.components.BarSegment(kcal.toDouble(), t.color)
                }
            val segments = buildList {
                addAll(typeSegments)
                if (b.shAmbientKcal > 0) add(com.example.ui.components.BarSegment(b.shAmbientKcal.toDouble(), ambientColor))
            }
            com.example.ui.components.BarBucket(
                date = b.date,
                segments = segments,
                contentDescription = "${b.date}: $activeKcal kcal, ${b.durationMinutes} min"
            )
        }
    }
}

/**
 * Pick a localised column label for the chart's X axis. For Weekly (≤14
 * buckets) use the short weekday in the user's locale (`EEE`). For longer
 * ranges (Monthly) use day-of-month and thin out every 3rd day.
 */
private fun pickColumnLabel(
    buckets: List<com.example.ui.components.BarBucket>,
    lang: String
): (LocalDate) -> String {
    val locale = localeForLanguage(lang)
    return if (buckets.size <= 14) {
        val weeklyFmt = java.time.format.DateTimeFormatter.ofPattern("EEE", locale)
        val labeller: (LocalDate) -> String = { date -> weeklyFmt.format(date) }
        labeller
    } else {
        val monthlyFmt = java.time.format.DateTimeFormatter.ofPattern("d", locale)
        val firstDay = buckets.firstOrNull()?.date
        val labeller: (LocalDate) -> String = { date ->
            val idx = if (firstDay != null) java.time.temporal.ChronoUnit.DAYS.between(firstDay, date).toInt() else 0
            if (idx % 3 == 0) monthlyFmt.format(date) else ""
        }
        labeller
    }
}

/**
 * Card-level wrapper for the Meals report block. Composition top-to-bottom:
 *   1. Lead KPI — Net Energy Balance vs the period-aggregated kcal target.
 *   2. Existing detail table (extracted [NutritionTableBody]).
 *   3. Optional collapsible "Evolução" stacked-bar chart (Weekly/Monthly).
 *
 * Caller passes the already-computed scalars and per-day bucket list — this
 * composable does NOT re-derive aggregates so totals never drift from the
 * detail rows.
 */
@Composable
fun MealsReportCard(
    macroBuckets: List<DailyMacroBucket>,
    targetsByDay: Map<LocalDate, com.example.data.local.TargetSnapshot>,
    consumedCalories: Int,
    protein: Double,
    carbs: Double,
    fat: Double,
    /** Total free sugars (grams) inside `carbs`. Null when the window has no
     *  meal with the estimate (all-legacy day) — the table then renders the
     *  legacy single "Total Carbohydrates" row without a sub-line. */
    freeSugarsG: Double?,
    /** Total saturated fat (grams) inside `fat`. Null when the window has no
     *  meal with the estimate — the table then renders the legacy single
     *  "Fat" row without a saturated/unsaturated split. */
    saturatedFatG: Double?,
    mealCount: Int,
    timescale: String,
    lang: String,
    modifier: Modifier = Modifier,
    /** Period-summed targets. Non-null + `days > 0` → the macro rows colour
     *  their dots by verdict and show `/ alvo`. Null (default) → legacy fixed
     *  colours + grams only (keeps PNG/other callers green). */
    aggregatedTargets: com.example.data.targets.TargetResolver.AggregatedTargets? = null,
    /** When false, the collapsible "Evolução" section is hidden entirely.
     *  Off-screen capture (share-PNG) passes `false` so the partilhada image
     *  stays compact; on-screen leaves the default `true`. */
    showEvolution: Boolean = true
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 2026-06-26 plan: this card is now intake-only. Burned/Balance
            // and body composition moved to the dedicated `OverviewReportCard`
            // at the bottom of the tab. The macro stacked-bar evolution
            // graphic stays here — it visualises *what was eaten*, which is
            // squarely an intake concern.
            NutritionTableBody(
                consumedCalories = consumedCalories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                freeSugarsG = freeSugarsG,
                saturatedFatG = saturatedFatG,
                mealCount = mealCount,
                aggregatedTargets = aggregatedTargets,
                lang = lang
            )
            EvolutionSection(
                title = tr("reports.evolution.title", lang),
                visible = showEvolution && timescale != "Daily",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                val bars = remember(macroBuckets, lang) { mealBucketsToBars(macroBuckets, lang) }
                val targetPerDay = remember(targetsByDay) {
                    targetsByDay.mapValues { it.value.targetKcal.toDouble() }
                }
                val meanTarget = remember(targetPerDay) {
                    val nz = targetPerDay.values.filter { it > 0.0 }
                    if (nz.isEmpty()) 0.0 else nz.average()
                }
                com.example.ui.components.StackedDayBarChart(
                    buckets = bars,
                    targetPerDay = targetPerDay,
                    columnLabel = pickColumnLabel(bars, lang),
                    emptyPlaceholder = tr("reports.evolution.empty", lang)
                )
                if (meanTarget > 0.0) {
                    com.example.ui.components.StackedDayBarChartTargetCaption(
                        text = tr("reports.evolution.targetLine", lang) + ": ${meanTarget.toInt()} kcal"
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Card-level wrapper for the Activity report block. Composition mirrors
 * [MealsReportCard]:
 *   1. Lead KPI — Total Energy Burned vs the aggregated activityCalories
 *      target. A caption discloses BMR is included.
 *   2. Existing detail table (extracted [ActivitySummaryTableBody]).
 *   3. Optional collapsible "Evolução" stacked-bar chart by activity type.
 */
@Composable
fun ActivityReportCard(
    activityBuckets: List<DailyActivityBucket>,
    targetsByDay: Map<LocalDate, com.example.data.local.TargetSnapshot>,
    rows: List<ReportActivityRow>,
    distanceMeters: Float?,
    /** Total burned for the period EXCLUDING TMB — workouts + ambient SH only.
     *  Caller computes `burnedCalories - aggregatedBmrReport.kcal.toInt()`.
     *  Displayed in the "Total Queimado" headline row. */
    activityOnlyKcal: Int,
    timescale: String,
    lang: String,
    modifier: Modifier = Modifier,
    showEvolution: Boolean = true
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ActivitySummaryTableBody(
                rows = rows,
                lang = lang,
                totalBurnedKcal = activityOnlyKcal,
                distanceMeters = distanceMeters
            )
            EvolutionSection(
                title = tr("reports.evolution.title", lang),
                visible = showEvolution && timescale != "Daily",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                val bars = remember(activityBuckets, lang) { activityBucketsToBars(activityBuckets, lang) }
                // Activity target line — daily activityCalories target from the
                // versioned snapshots.
                val targetPerDay = remember(targetsByDay) {
                    targetsByDay.mapValues { it.value.targetActivityCalories.toDouble() }
                }
                val meanTarget = remember(targetPerDay) {
                    val nz = targetPerDay.values.filter { it > 0.0 }
                    if (nz.isEmpty()) 0.0 else nz.average()
                }
                com.example.ui.components.StackedDayBarChart(
                    buckets = bars,
                    targetPerDay = targetPerDay,
                    columnLabel = pickColumnLabel(bars, lang),
                    emptyPlaceholder = tr("reports.evolution.empty", lang)
                )
                if (meanTarget > 0.0) {
                    com.example.ui.components.StackedDayBarChartTargetCaption(
                        text = tr("reports.evolution.targetLine", lang) + ": ${meanTarget.toInt()} kcal"
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Third report card — "Balanço". Redesigned 2026-07-20 into two readable
 * sections so the user can answer the two questions this card is for:
 *
 *   1. ENERGIA — Consumido vs Gasto (Atividade + TMB) → Saldo.
 *      The old layout only showed the *outflows* (activity, TMB) plus a bare
 *      net number, so the consumed side was invisible and the row read as
 *      "broken". Now the intake row leads, the two burn rows subtract from
 *      it, and the signed Saldo is the highlighted synthesis.
 *
 *   2. QUALIDADE DOS MACRONUTRIENTES — protein / carbs / fat, each judged
 *      against the period target (aggregatedTargetsReport). Carbs carry a
 *      free-sugars sub-line and fat a saturated sub-line, each with its own
 *      verdict: free sugars are "high" above 10% of consumed carbs, saturated
 *      fat above 1/3 of consumed fat (common WHO / dietary guidance — the
 *      thresholds live in [macroVerdict]/[subMacroVerdict], not scattered).
 *
 * Intake calories are shown here as the lead of the energy section; the
 * detailed macro *grams* table still lives in "Resumo de Alimentação" above.
 * This card is the synthesis: is the day in balance, and are the macros the
 * right shape.
 */
@Composable
fun OverviewReportCard(
    consumedCalories: Int,
    /** Physical activity only (workouts + ambient SH). Matches the
     *  "Total Exercício" row on the Atividade card so the two numbers
     *  visibly agree. TMB is shown separately below. */
    activityKcal: Int,
    /** Resting metabolism aggregated over the period. */
    bmrKcal: Int,
    /** consumed − (activity + bmr). Highlighted as the headline of the
     *  energy section; it's what the user actually wants to see. */
    netCalories: Int,
    timescale: String,
    lang: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = tr("Overall Summary", lang),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )

                // ── ENERGIA ─────────────────────────────────────────────────
                // 2026-07-20 — the macro-quality section was removed: the macro
                // grams + verdict now live only in the Resumo de Alimentação
                // card (they were duplicated here). Balanço is energy-only.
                Text(
                    text = tr("Energy", lang).uppercase(),
                    color = AccentSoft,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.5.sp
                )
                Divider(color = Color(0xFF334155).copy(alpha = 0.5f))
                // Intake leads — the number the old card hid. Then the two
                // burn rows subtract from it, then the signed synthesis.
                TableRowItem(name = tr("Consumed", lang), valStr = "$consumedCalories kcal", iconColor = Color(0xFF22C55E))
                TableRowItem(name = tr("Spent — Activity", lang), valStr = "-$activityKcal kcal", iconColor = Color(0xFFEF4444))
                TableRowItem(name = tr("Spent — BMR", lang), valStr = "-$bmrKcal kcal", iconColor = Color(0xFF3B82F6))
                run {
                    // Signed Saldo + word so "+120 kcal excedente" / "-300 kcal
                    // défice" reads without the user decoding the sign.
                    val word = when {
                        netCalories > 0 -> tr("surplus", lang)
                        netCalories < 0 -> tr("deficit", lang)
                        else -> tr("balanced", lang)
                    }
                    val signed = if (netCalories > 0) "+$netCalories" else "$netCalories"
                    TableRowItemHighlighted(
                        name = tr("Net Energy Balance", lang),
                        valStr = "$signed kcal · $word",
                        iconColor = AccentPrimary,
                        valueWeight = 1.4f
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Verdict for a macronutrient quality row. Drives dot colour + short label.
 * Direction of "good" differs per macro (protein wants ≥target; free sugars /
 * saturated fat want to stay low), so the verdict is computed by the specific
 * `*Verdict` helper below — this enum is just the rendered outcome.
 */
private enum class MacroVerdict { GOOD, WATCH, BAD, NEUTRAL }

private fun MacroVerdict.color(): Color = when (this) {
    MacroVerdict.GOOD -> Color(0xFF22C55E)
    MacroVerdict.WATCH -> Color(0xFFEAB308)
    MacroVerdict.BAD -> Color(0xFFEF4444)
    MacroVerdict.NEUTRAL -> Color(0xFF64748B)
}

/** Protein: hitting/exceeding target is good. ≥90% good, 70–90% watch, <70% bad-low. */
private fun proteinVerdict(g: Double, target: Double): MacroVerdict {
    if (target <= 0.0) return MacroVerdict.NEUTRAL
    val r = g / target
    return when {
        r >= 0.90 -> MacroVerdict.GOOD
        r >= 0.70 -> MacroVerdict.WATCH
        else -> MacroVerdict.BAD
    }
}

/** Total carbs/fat vs target — informational band. 90–110% good, else watch. */
private fun totalVerdict(g: Double, target: Double): MacroVerdict {
    if (target <= 0.0) return MacroVerdict.NEUTRAL
    val r = g / target
    return if (r in 0.90..1.10) MacroVerdict.GOOD else MacroVerdict.WATCH
}

/** Free sugars: high above 10% of consumed carbs, watch 7–10%, good ≤7% (WHO). */
private fun freeSugarsVerdict(freeSugars: Double, carbs: Double): MacroVerdict {
    if (carbs <= 0.0) return MacroVerdict.NEUTRAL
    val share = freeSugars / carbs
    return when {
        share > 0.10 -> MacroVerdict.BAD
        share > 0.07 -> MacroVerdict.WATCH
        else -> MacroVerdict.GOOD
    }
}


/** Saturated fat: high above 1/3 of consumed fat, watch 1/4–1/3, good ≤1/4. */
private fun saturatedVerdict(saturated: Double, fat: Double): MacroVerdict {
    if (fat <= 0.0) return MacroVerdict.NEUTRAL
    val share = saturated / fat
    return when {
        share > 1.0 / 3.0 -> MacroVerdict.BAD
        share > 1.0 / 4.0 -> MacroVerdict.WATCH
        else -> MacroVerdict.GOOD
    }
}

/**
 * Fourth report card — "Composição Corporal". Surfaces the latest weight,
 * lean body mass, muscle mass and body-fat percentage, plus the weight
 * evolution chart (3 weeks or 3 months depending on [timescale]). Hidden
 * entirely when there are no readings at all.
 *
 * Body comp lives in its own card (not folded under Resumo) so the user
 * can see the weight chart without scrolling past energy numbers — and
 * the energy and body-comp concerns stay independent (energy is daily
 * accounting, weight is multi-week trend).
 */
@Composable
fun BodyCompositionCard(
    latestBodyComp: com.example.data.local.BodyCompositionSnapshotEntity?,
    /** Snapshots ascending by source timestamp. Empty list hides the
     *  whole card (no point showing rows of nulls). */
    bodyCompHistory: List<com.example.data.local.BodyCompositionSnapshotEntity>,
    timescale: String,
    lang: String,
    zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    modifier: Modifier = Modifier,
    showEvolution: Boolean = true
) {
    if (latestBodyComp == null && bodyCompHistory.isEmpty()) return
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = tr("Body Composition", lang),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Divider(color = Color(0xFF334155))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Metric Dimension", lang), color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f))
                    Text(tr("Total Sum", lang), color = AccentSoft, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
                Divider(color = Color(0xFF334155).copy(alpha = 0.5f))

                val weightKg = latestBodyComp?.weightKg
                val bodyFatPct = latestBodyComp?.bodyFatPct
                val muscleMassKg = latestBodyComp?.muscleMassKg
                val totalBodyWaterKg = latestBodyComp?.totalBodyWaterKg
                val ptLoc = java.util.Locale("pt", "PT")
                // Row order mirrors the Evolução chart: weight → lean → muscle
                // → fat → water. Each row shows kg AND % (Hugo, 2026-08-19).
                // Muscle/water are stored in kg (converted from the scale's %
                // at read time); we derive the % back here as kg/weight×100.
                // Fat is derived from bodyFatPct (a genuine %).
                if (weightKg != null) {
                    TableRowItem(
                        name = tr("Current Weight", lang),
                        valStr = String.format(ptLoc, "%.1f kg", weightKg),
                        iconColor = Color(0xFFA78BFA) // violet — matches the Peso line in the Evolução chart
                    )
                }
                if (weightKg != null && bodyFatPct != null) {
                    val fatFreeKg = weightKg * (1f - bodyFatPct / 100f)
                    val leanPct = 100f - bodyFatPct
                    TableRowItem(
                        name = tr("Lean Body Mass", lang),
                        valStr = String.format(ptLoc, "%.1f kg (%.1f%%)", fatFreeKg, leanPct),
                        iconColor = Color(0xFF10B981)
                    )
                }
                if (muscleMassKg != null && weightKg != null) {
                    val musclePct = muscleMassKg / weightKg * 100f
                    TableRowItem(
                        name = tr("Muscle Mass", lang),
                        valStr = String.format(ptLoc, "%.1f kg (%.1f%%)", muscleMassKg, musclePct),
                        iconColor = Color(0xFF22D3EE)
                    )
                }
                // Massa Gorda em kg (peso × %/100) — mesma métrica factual que
                // a série "Massa gorda" do gráfico e o KPI, para a tabela e o
                // gráfico não divergirem (% vs kg confundia; 2026-07-20).
                if (bodyFatPct != null && weightKg != null) {
                    val fatMassKg = weightKg * bodyFatPct / 100f
                    TableRowItem(
                        name = tr("Fat Mass", lang),
                        valStr = String.format(ptLoc, "%.1f kg (%.1f%%)", fatMassKg, bodyFatPct),
                        iconColor = Color(0xFFF59E0B)
                    )
                }
                // Água no corpo — armazenada em kg (convertida do % da balança
                // na leitura); mostramos kg e a % derivada.
                if (totalBodyWaterKg != null && weightKg != null) {
                    val waterPct = totalBodyWaterKg / weightKg * 100f
                    TableRowItem(
                        name = tr("Body Water", lang),
                        valStr = String.format(ptLoc, "%.1f kg (%.1f%%)", totalBodyWaterKg, waterPct),
                        iconColor = Color(0xFF38BDF8)
                    )
                }
            }
            EvolutionSection(
                title = tr("reports.evolution.title", lang),
                // Per-snapshot strip is timescale-agnostic — show whenever any
                // body-comp history exists, independent of the Daily/Weekly/
                // Monthly toggle above.
                visible = showEvolution && bodyCompHistory.isNotEmpty(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // One point per DAY (factual, un-bucketed), horizontally
                // scrollable. Multiple weigh-ins on the same calendar day
                // collapse to the last one (they read essentially the same),
                // so the x-axis never shows a repeated date. Four series:
                // weight, muscle, derived fat mass (weight × bodyFat%), and
                // body water. Water only if at least one snapshot has a
                // reading. Cap to the last 200 days so a very long history
                // doesn't draw an enormous strip every frame.
                val history = remember(bodyCompHistory) {
                    bodyCompHistory
                        .groupBy {
                            val ts = it.sourceTimestampMs ?: it.capturedAtMs
                            java.time.Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate()
                        }
                        .toSortedMap()
                        .values
                        .map { sameDay -> sameDay.maxByOrNull { it.sourceTimestampMs ?: it.capturedAtMs }!! }
                        .takeLast(200)
                }
                val lines = remember(history, lang) {
                    val hasWater = history.any { it.totalBodyWaterKg != null }
                    buildList {
                        add(bodyCompLinePerSnapshot(tr("bodycomp.short.weight", lang), history, { "neutral" }) { it.weightKg.toDouble() })
                        // Muscle: plot kg, but KPI reports % of weight and its pp
                        // delta (kg alone flags red on a cut even as muscle % rises).
                        add(bodyCompLinePerSnapshot(
                            tr("bodycomp.short.muscle", lang), history,
                            tone = { d -> if (d > 0) "good" else "bad" },
                            kpiPicker = { s -> s.muscleMassKg?.let { it.toDouble() / s.weightKg * 100.0 } },
                            kpiUnit = "%",
                            kpiTone = { d -> if (d > 0) "good" else "bad" }
                        ) { it.muscleMassKg?.toDouble() })
                        add(bodyCompLinePerSnapshot(
                            tr("bodycomp.short.fat", lang), history,
                            tone = { d -> if (d > 0) "bad" else "good" },
                            kpiPicker = { s -> s.bodyFatPct?.toDouble() },
                            kpiUnit = "%",
                            kpiTone = { d -> if (d > 0) "bad" else "good" }
                        ) { s ->
                            val f = s.bodyFatPct
                            if (f != null) s.weightKg.toDouble() * f / 100.0 else null
                        })
                        if (hasWater) {
                            add(bodyCompLinePerSnapshot(
                                tr("bodycomp.short.water", lang), history,
                                tone = { "neutral" },
                                kpiPicker = { s -> s.totalBodyWaterKg?.let { it.toDouble() / s.weightKg * 100.0 } },
                                kpiUnit = "%",
                                kpiTone = { "neutral" }
                            ) { it.totalBodyWaterKg?.toDouble() })
                        }
                    }.filter { it.values.any { v -> v != null } }
                }
                val xLabels = remember(history) { history.map { snapshotColumnLabel(it, zoneId) } }

                if (lines.isNotEmpty()) {
                    com.example.ui.chat.MultilineChartScrollable(
                        lines = lines,
                        xLabels = xLabels
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Group body-composition snapshots into week- or month-buckets, taking the
 * LAST reading in each bucket (by timestamp — the factual measurement, never
 * an average) and forward-filling empty buckets from the previous bucket's
 * value. Returns buckets covering:
 *   - Weekly  → last 6 ISO weeks ending in [today]'s week
 *   - Monthly → last 3 calendar months ending in [today]'s month
 *   - other   → empty list (Daily timescale isn't surfaced for body comp)
 *
 * The [picker] lambda chooses which scalar to track. When the picker
 * returns null for a snapshot, that snapshot is skipped — so a snapshot
 * with weight but no body fat doesn't poison the bf series with a 0.
 */
private fun buildBodyCompTrend(
    history: List<com.example.data.local.BodyCompositionSnapshotEntity>,
    timescale: String,
    today: java.time.LocalDate,
    zoneId: java.time.ZoneId,
    picker: (com.example.data.local.BodyCompositionSnapshotEntity) -> Double?
): List<com.example.ui.components.BarBucket> {
    val color = Color(0xFF06B6D4)  // cyan-500 — matches the table icon for weight
    val anchors: List<java.time.LocalDate> = when (timescale) {
        "Weekly" -> {
            val thisWeekStart = today.with(java.time.DayOfWeek.MONDAY)
            (5 downTo 0).map { thisWeekStart.minusWeeks(it.toLong()) }
        }
        "Monthly" -> {
            val thisMonthStart = today.withDayOfMonth(1)
            (2 downTo 0).map { thisMonthStart.minusMonths(it.toLong()) }
        }
        else -> return emptyList()
    }
    if (anchors.isEmpty()) return emptyList()

    // Each anchor keeps the LAST reading in that week/month (by timestamp),
    // not the average. Averaging would erase the factual snapshot the system
    // actually holds — the chart lines must be the real measurements, and the
    // latest point must match the "Composição Corporal" table (which reads the
    // single most-recent snapshot). Value + the timestamp it came from, so a
    // later snapshot in the same anchor wins.
    val lastByAnchor = HashMap<java.time.LocalDate, Pair<Double, Long>>()
    for (snap in history) {
        val v = picker(snap) ?: continue
        val ts = snap.sourceTimestampMs ?: snap.capturedAtMs
        val day = java.time.Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate()
        val anchor = when (timescale) {
            "Weekly" -> day.with(java.time.DayOfWeek.MONDAY)
            "Monthly" -> day.withDayOfMonth(1)
            else -> continue
        }
        val prev = lastByAnchor[anchor]
        if (prev == null || ts >= prev.second) lastByAnchor[anchor] = v to ts
    }

    val sortedAnchors = anchors.sorted()
    val firstAnchor = sortedAnchors.first()
    // Seed carry with the most recent snapshot strictly before the first
    // anchor — so the chart doesn't open at zero when the visible window
    // has no readings yet.
    var carry: Double? = null
    for (snap in history) {
        val v = picker(snap) ?: continue
        val ts = snap.sourceTimestampMs ?: snap.capturedAtMs
        val day = java.time.Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate()
        if (day.isBefore(firstAnchor)) carry = v
    }

    return sortedAnchors.map { anchor ->
        val last = lastByAnchor[anchor]
        val value = last?.first ?: carry
        if (value == null) {
            com.example.ui.components.BarBucket(date = anchor, segments = emptyList())
        } else {
            carry = value
            com.example.ui.components.BarBucket(
                date = anchor,
                segments = listOf(com.example.ui.components.BarSegment(value, color)),
                contentDescription = "$anchor: ${"%.1f".format(value)}"
            )
        }
    }
}

/**
 * Reshape a single-metric [buildBodyCompTrend] result into a [ChartLine]
 * for the small-multiples [MultilineChart]. The value at each x-position is
 * the bucket's segment value (null → gap, so a week with no reading and no
 * carry doesn't dive to zero).
 *
 * The KPI is derived from the visible window: latest = last non-null value,
 * delta = latest − first non-null. [tone] maps that delta to editorial
 * meaning — muscle up is "good", fat up is "bad", weight is "neutral"
 * (up/down isn't good or bad without a goal). This can't be inferred from
 * the numbers alone, hence the explicit lambda.
 */
private fun bodyCompLine(
    label: String,
    buckets: List<com.example.ui.components.BarBucket>,
    tone: (delta: Double) -> String
): com.example.data.remote.ChartLine {
    val values: List<Double?> = buckets.map { it.segments.firstOrNull()?.value }
    val nonNull = values.filterNotNull()
    val kpi = if (nonNull.isEmpty()) {
        null
    } else {
        val latest = nonNull.last()
        val first = nonNull.first()
        val delta = latest - first
        com.example.data.remote.ChartKpi(
            latest = latest,
            delta = delta,
            deltaTone = if (delta == 0.0) "neutral" else tone(delta)
        )
    }
    return com.example.data.remote.ChartLine(
        label = label,
        unit = "kg",
        values = values,
        kpi = kpi
    )
}

/**
 * Per-snapshot [ChartLine] for the scrollable Evolução strip — one value per
 * individual weigh-in in [history] (assumed ascending by timestamp), NO
 * bucketing. `picker` returns the metric for a snapshot or null (→ gap; the
 * scrollable plot bridges gaps when drawing). KPI: latest = last non-null,
 * delta = latest − first non-null; `tone` maps that delta to editorial colour.
 * This is the factual, un-aggregated counterpart to [bodyCompLine].
 *
 * [kpiPicker] (optional): when the KPI should report a different quantity than
 * the plotted line — e.g. muscle/water are plotted in kg (mass over time) but
 * the KPI reports percentage-of-weight and its pp delta. Absolute kg moves with
 * total weight, so on a weight cut muscle kg falls even as muscle % rises;
 * reporting the pp change keeps the tile's colour honest (2026-08-19).
 * [kpiUnit] labels that KPI (e.g. "%"); [kpiTone] maps the pp delta to colour.
 */
private fun bodyCompLinePerSnapshot(
    label: String,
    history: List<com.example.data.local.BodyCompositionSnapshotEntity>,
    tone: (delta: Double) -> String,
    kpiPicker: ((com.example.data.local.BodyCompositionSnapshotEntity) -> Double?)? = null,
    kpiUnit: String? = null,
    kpiTone: ((delta: Double) -> String)? = null,
    picker: (com.example.data.local.BodyCompositionSnapshotEntity) -> Double?
): com.example.data.remote.ChartLine {
    val values: List<Double?> = history.map { picker(it) }
    // KPI series: the % series when supplied, else the plotted values.
    val kpiValues: List<Double?>? = kpiPicker?.let { p -> history.map { p(it) } }
    val kpiSource = kpiValues ?: values
    val kpiToneFn = kpiTone ?: tone
    val nonNull = kpiSource.filterNotNull()
    val kpi = if (nonNull.isEmpty()) {
        null
    } else {
        val latest = nonNull.last()
        val delta = latest - nonNull.first()
        com.example.data.remote.ChartKpi(
            latest = latest,
            delta = delta,
            deltaTone = if (delta == 0.0) "neutral" else kpiToneFn(delta)
        )
    }
    return com.example.data.remote.ChartLine(
        label = label,
        unit = "kg",
        values = values,
        kpi = kpi,
        kpiUnit = kpiUnit,
        kpiValues = kpiValues
    )
}

/** x-axis label for one snapshot in the scrollable strip: "d/M". */
private fun snapshotColumnLabel(
    snap: com.example.data.local.BodyCompositionSnapshotEntity,
    zoneId: java.time.ZoneId
): String {
    val ts = snap.sourceTimestampMs ?: snap.capturedAtMs
    val d = java.time.Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate()
    return "${d.dayOfMonth}/${d.monthValue}"
}


/**
 * Column-label formatter for week- and month-aggregated buckets in the
 * Overview card. Daily anchors fall through (only Weekly/Monthly land
 * here in practice).
 */
private fun bucketColumnLabel(d: java.time.LocalDate, timescale: String, lang: String): String {
    return when (timescale) {
        "Weekly" -> "${d.dayOfMonth}/${d.monthValue}"
        "Monthly" -> {
            val month = d.month.getDisplayName(java.time.format.TextStyle.SHORT, localeForLanguage(lang))
            month.replaceFirstChar { it.uppercase(localeForLanguage(lang)) }
        }
        else -> d.dayOfMonth.toString()
    }
}


@Composable
fun TextLegendItem(color: Color, percentage: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Text(text = "$label: $percentage%", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Inner column of the meals report card, exposed so [MealsReportCard] can
 * compose it under the Card chrome without nesting Cards. Standalone
 * `NutritionTable` wrapper was removed in 2026-06-26 — it had no callers
 * and outlived its purpose once `MealsReportCard` took over the layout.
 */
@Composable
private fun NutritionTableBody(
    consumedCalories: Int,
    protein: Double,
    carbs: Double,
    fat: Double,
    /** See [MealsReportCard.freeSugarsG]. Null → legacy single row for carbs. */
    freeSugarsG: Double?,
    /** See [MealsReportCard.saturatedFatG]. Null → legacy single row for fat. */
    saturatedFatG: Double?,
    mealCount: Int,
    /** See [MealsReportCard.aggregatedTargets]. Non-null + `days > 0` → macro
     *  rows colour their dots by verdict and show `/ alvo`. */
    aggregatedTargets: com.example.data.targets.TargetResolver.AggregatedTargets? = null,
    lang: String
) {
    // 2026-07-20 — macro verdict (colour + target) moved here from the Balanço
    // card, where it was duplicated. When targets exist, protein/carbs/fat dots
    // are coloured by their verdict (proteinVerdict / totalVerdict /
    // saturatedVerdict) and the value carries `/ alvo`; without targets the
    // rows keep their legacy fixed palette + grams-only (unchanged behaviour).
    // Free Sugar uses a FIXED pink identity colour (not a verdict) — 0 g is the
    // common case and the verdict-green read as a category, not a judgement.
    val hasTargets = aggregatedTargets != null && aggregatedTargets.days > 0
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Nutritional Metric Summary Table", lang), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)

        Divider(color = Color(0xFF334155))

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(tr("Metric Dimension", lang), color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f))
            Text(tr("Total Sum", lang), color = AccentSoft, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }

        Divider(color = Color(0xFF334155).copy(alpha = 0.5f))

        TableRowItemHighlighted(name = tr("Food Intake (Consumed)", lang), valStr = "$consumedCalories kcal", iconColor = AccentPrimary)

        // Protein — verdict colour + "/ alvo" when targets exist.
        TableRowItem(
            name = tr("Total Protein", lang),
            valStr = if (hasTargets) String.format("%.1f g / %.0f g", protein, aggregatedTargets!!.protein) else String.format("%.1f g", protein),
            iconColor = if (hasTargets) proteinVerdict(protein, aggregatedTargets!!.protein).color() else Color(0xFFF97316)
        )
        // Carbs — verdict colour + "/ alvo"; free-sugar sub-line coloured by its
        // own verdict.
        TableRowItem(
            name = tr("Total Carbohydrates", lang),
            valStr = if (hasTargets) String.format("%.1f g / %.0f g", carbs, aggregatedTargets!!.carbs) else String.format("%.1f g", carbs),
            iconColor = if (hasTargets) totalVerdict(carbs, aggregatedTargets!!.carbs).color() else Color(0xFF8B5CF6)
        )
        if (freeSugarsG != null) {
            val clampedFree = freeSugarsG.coerceAtMost(carbs)
            TableRowItem(
                name = tr("Free Sugar", lang),
                valStr = String.format("%.1f g", clampedFree),
                iconColor = Color(0xFFEC4899) // pink — fixed identity colour (not the verdict green)
            )
        }
        // Fat — verdict colour + "/ alvo"; saturated sub-line coloured by its
        // own verdict.
        if (saturatedFatG != null) {
            val clampedSat = saturatedFatG.coerceIn(0.0, fat)
            val unsat = (fat - clampedSat).coerceAtLeast(0.0)
            TableRowItem(
                name = tr("Unsaturated Fat", lang),
                valStr = if (hasTargets) String.format("%.1f g", unsat) else String.format("%.1f g", unsat),
                iconColor = if (hasTargets) totalVerdict(fat, aggregatedTargets!!.fat).color() else Color(0xFFEAB308)
            )
            TableRowItem(
                name = tr("Saturated Fat", lang),
                valStr = String.format("%.1f g", clampedSat),
                iconColor = saturatedVerdict(clampedSat, fat).color()
            )
        } else {
            TableRowItem(
                name = tr("Fat", lang),
                valStr = if (hasTargets) String.format("%.1f g / %.0f g", fat, aggregatedTargets!!.fat) else String.format("%.1f g", fat),
                iconColor = if (hasTargets) totalVerdict(fat, aggregatedTargets!!.fat).color() else Color(0xFFEAB308)
            )
        }
        TableRowItem(name = tr("Logged Meals Size", lang), valStr = "$mealCount ${tr("dishes", lang)}", iconColor = Color(0xFFEC4899))
    }
}

@Composable
fun TableRowItem(name: String, valStr: String, iconColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(6.dp).background(iconColor, CircleShape))
            Text(name, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        Text(valStr, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

/**
 * Visually-emphasised variant of [TableRowItem] for the lead-row inside a
 * detail table. Used by the Meals card for "Balanço Energético" and by the
 * Activity card for "Total Queimado". Subtle violet wash + bold body type so
 * the eye lands on the row without it shouting.
 *
 * 2026-06-22 — replaces the standalone LeadKpi block on top of each Reports
 * card. The duplication-free way to honour Hugo's "leitura primeiro pelo
 * insight mais importante" requirement.
 */
@Composable
private fun TableRowItemHighlighted(
    name: String,
    valStr: String,
    iconColor: Color,
    valueWeight: Float = 1f
) {
    // 2026-06-22 (refinement) — mesma tipografia que `TableRowItem` (bodySmall)
    // para não desformatar o card. O destaque vem só do background violet wash
    // + bold no valor + bola um pouquinho maior. Suficiente para o olho ancorar
    // sem fazer a linha "saltar fora" do alinhamento das outras.
    //
    // 2026-06-26 (Hugo feedback) — alinhamento corrigido: bullet 6.dp (igual
    // ao TableRowItem) e sem padding horizontal extra. O `padding(horizontal
    // = 8.dp)` anterior encolhia a Row interna e deslocava o valor direito
    // ~8.dp para dentro vs as rows normais; agora todas as linhas usam o
    // mesmo eixo X. O violet wash continua a destacar mas sem desalinhar.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AccentPrimary.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1.5f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).background(iconColor, CircleShape))
            Text(
                name,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            valStr,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(valueWeight),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun ReportTab(
    foods: List<FoodEntry>,
    activities: List<ActivityEntry>,
    viewModel: ChatViewModel
) {
    val userProfile by viewModel.userProfile.collectAsState()
    val targetSnapshots by viewModel.targetSnapshots.collectAsState()
    val bmrSnapshotsList by viewModel.bmrSnapshots.collectAsState()
    val shWeightSnap by viewModel.shWeight.collectAsState()
    val latestBodyComp by viewModel.latestBodyComposition.collectAsState()
    val exportFolderUri by viewModel.exportFolderUri.collectAsState()
    val exportFolderName by viewModel.exportFolderName.collectAsState()
    val exportError by viewModel.exportError.collectAsState()
    val exportSuccess by viewModel.exportSuccess.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val lastExportAtMs by viewModel.lastExportAtMs.collectAsState()

    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadExportFolderUri(context)
        viewModel.loadLastExportAtMs(context)
    }

    // Surface manual-export outcome as a Toast so the user gets feedback even if
    // they've scrolled away from the export card or switched tabs while the
    // background coroutine was still running. The inline card below stays as the
    // detailed/dismissable record; the Toast is the "I saw it" beat.
    LaunchedEffect(exportSuccess) {
        exportSuccess?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(exportError) {
        exportError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    var timescale by remember { mutableStateOf("Weekly") }
    var selectedDateReport by remember { mutableStateOf(LocalDate.now(java.time.ZoneId.systemDefault())) }

    // SAF tree-picker. The user is shown the system file picker rooted at Drive (or any
    // other DocumentsProvider on the device). They pick a folder once; we persist the
    // grant + folder display-name so subsequent exports can write straight into it
    // without any further interaction. The Drive app on the device handles upload to
    // the user's Google account, so we never request OAuth scopes ourselves.
    val pickFolderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val folderName = androidx.documentfile.provider.DocumentFile
                    .fromTreeUri(context, uri)?.name
                viewModel.saveExportFolderUri(context, uri.toString(), folderName)
                // Encadear export imediato após selecção — o utilizador acabou de
                // dizer onde quer o ficheiro, faz sentido escrevê-lo já.
                viewModel.exportNutritionXlsx(context)
            } catch (e: SecurityException) {
                viewModel.setExportError("Could not persist permission: ${e.message}")
            }
        }
    }

    val now = System.currentTimeMillis()
    val zoneId = java.time.ZoneId.systemDefault()

    val filteredFoods = remember(foods, timescale, selectedDateReport) {
        val (startMs, endMs) = when (timescale) {
            "Daily" -> {
                val start = selectedDateReport.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = selectedDateReport.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
            "Weekly" -> {
                val monday = selectedDateReport.minusDays((selectedDateReport.dayOfWeek.value - 1).toLong())
                val sunday = monday.plusDays(6)
                val start = monday.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = sunday.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
            else -> { // Monthly
                val firstDay = selectedDateReport.withDayOfMonth(1)
                val lastDay = selectedDateReport.withDayOfMonth(selectedDateReport.lengthOfMonth())
                val start = firstDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = lastDay.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
        }
        foods.filter { it.timestamp in startMs..endMs }
    }

    val filteredActivities = remember(activities, timescale, selectedDateReport) {
        val (startMs, endMs) = when (timescale) {
            "Daily" -> {
                val start = selectedDateReport.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = selectedDateReport.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
            "Weekly" -> {
                val monday = selectedDateReport.minusDays((selectedDateReport.dayOfWeek.value - 1).toLong())
                val sunday = monday.plusDays(6)
                val start = monday.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = sunday.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
            else -> { // Monthly
                val firstDay = selectedDateReport.withDayOfMonth(1)
                val lastDay = selectedDateReport.withDayOfMonth(selectedDateReport.lengthOfMonth())
                val start = firstDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = lastDay.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
        }
        activities.filter { it.timestamp in startMs..endMs }
    }

    val shStepsByDate by viewModel.shStepsByDate.collectAsState()
    val dailyActivitySnapshotsList by viewModel.dailyActivitySnapshots.collectAsState()
    val shStatus by viewModel.shStatus.collectAsState()
    val shDailyActivityReport by viewModel.shDailyActivity.collectAsState()
    val shWorkoutsReport by viewModel.shWorkouts.collectAsState()

    val reportSteps = remember(shStepsByDate, timescale, selectedDateReport) {
        when (timescale) {
            "Daily" -> shStepsByDate[selectedDateReport] ?: 0L
            "Weekly" -> {
                val monday = selectedDateReport.minusDays((selectedDateReport.dayOfWeek.value - 1).toLong())
                (0..6).sumOf { shStepsByDate[monday.plusDays(it.toLong())] ?: 0L }
            }
            else -> { // Monthly
                val firstDay = selectedDateReport.withDayOfMonth(1)
                val lastDay = selectedDateReport.withDayOfMonth(selectedDateReport.lengthOfMonth())
                var current = firstDay
                var sum = 0L
                while (!current.isAfter(lastDay)) {
                    sum += shStepsByDate[current] ?: 0L
                    current = current.plusDays(1)
                }
                sum
            }
        }
    }

    val reportDateRange = remember(timescale, selectedDateReport) {
        when (timescale) {
            "Daily" -> selectedDateReport..selectedDateReport
            "Weekly" -> {
                val monday = selectedDateReport.minusDays((selectedDateReport.dayOfWeek.value - 1).toLong())
                monday..monday.plusDays(6)
            }
            else -> {
                val firstDay = selectedDateReport.withDayOfMonth(1)
                firstDay..selectedDateReport.withDayOfMonth(selectedDateReport.lengthOfMonth())
            }
        }
    }

    // For aggregations whose "total" sums per-day values (BMR, ambient
    // burn), clamp the right edge to today so the figure never includes
    // days that haven't happened yet. Without this, a weekly view on
    // Wednesday inflates "Total Queimadas" with 4 days of projected BMR;
    // a monthly view on the 26th inflates it with 4 days the user can't
    // yet have lived. Hugo flagged this on 2026-06-26 — the spreadsheet
    // and the chat-context paths should adopt the same convention later.
    val today = remember { java.time.LocalDate.now(zoneId) }
    val effectiveReportRange = remember(reportDateRange, today) {
        if (reportDateRange.endInclusive.isAfter(today)) {
            reportDateRange.start..(if (today.isBefore(reportDateRange.start)) reportDateRange.start else today)
        } else {
            reportDateRange
        }
    }

    // Body-composition history for the Overview card's evolution chart.
    // Window is the selected range MINUS 180 days on the left edge so the
    // forward-fill in `buildBodyCompTrend` has at least 6 months of prior
    // snapshots to seed the carry-forward when the selected range itself
    // has no readings. Re-fetches whenever the range moves. produceState
    // keeps the Compose tree off the suspend call; default empty list
    // means the chart renders nothing until the first emit lands.
    val bodyCompHistory by produceState<List<com.example.data.local.BodyCompositionSnapshotEntity>>(
        initialValue = emptyList(),
        key1 = reportDateRange
    ) {
        val zone = java.time.ZoneId.systemDefault()
        val startMs = reportDateRange.start.minusDays(180)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = reportDateRange.endInclusive.plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        value = runCatching {
            viewModel.loadBodyCompositionHistory(startMs, endMs)
        }.getOrDefault(emptyList())
    }
    // Per-day target lookup + aggregated period totals. The exporters and the
    // Reports tab UI both compare consumption against these instead of
    // userProfile.targetX directly, so a target change today does not silently
    // re-anchor last month's report against today's numbers.
    val targetsByDayReport = remember(targetSnapshots, reportDateRange) {
        com.example.data.targets.TargetResolver.resolveTargetsByDay(targetSnapshots, reportDateRange)
    }
    val aggregatedTargetsReport = remember(targetSnapshots, reportDateRange) {
        com.example.data.targets.TargetResolver.aggregateOverRange(targetSnapshots, reportDateRange)
    }
    // BMR/TMB summed day-by-day across the effective report range
    // (clamped to today — see [effectiveReportRange]). Same backwards-fill
    // rule as the dashboard so historical periods that pre-date the first
    // snapshot still carry a resting-burn contribution rather than appearing
    // free of TMB. See BmrResolver.
    val aggregatedBmrReport = remember(bmrSnapshotsList, effectiveReportRange) {
        com.example.data.bmr.BmrResolver.aggregateOverRange(bmrSnapshotsList, effectiveReportRange)
    }
    val reportShActiveCal = remember(shWorkoutsReport, shDailyActivityReport, shStepsByDate, dailyActivitySnapshotsList, effectiveReportRange, shStatus) {
        if (shStatus != SamsungHealthRepository.Status.AVAILABLE) 0
        else com.example.data.activity.DailyActivityResolver.aggregateOverRange(
            snapshots = dailyActivitySnapshotsList,
            workouts = shWorkoutsReport,
            dailySummaryByDate = shDailyActivityReport,
            stepsByDay = shStepsByDate,
            range = effectiveReportRange
        ).kcal.toInt()
    }
    // Distância acumulada no período seleccionado — mesma agregação que
    // `reportShActiveCal` mas com `distanceMeters`. Vai para o resumo de
    // actividade tanto no on-screen como no shareable PNG.
    val reportPeriodDistanceMeters = remember(shDailyActivityReport, reportDateRange, shStatus) {
        if (shStatus != SamsungHealthRepository.Status.AVAILABLE) 0f
        else shDailyActivityReport.entries
            .filter { it.key in reportDateRange }
            .map { it.value.distanceMeters }
            .sum()
    }
    val reportNonOverlappingManuals = remember(filteredActivities, shWorkoutsReport, shStatus) {
        if (shStatus != SamsungHealthRepository.Status.AVAILABLE) filteredActivities
        else {
            val intervals = shWorkoutsReport.map { it.startTime to it.endTime }
            filteredActivities.filter { manual ->
                val mStart = java.time.Instant.ofEpochMilli(manual.timestamp)
                val mEnd = mStart.plusSeconds(manual.durationMinutes * 60L)
                intervals.none { (ws, we) -> mStart.isBefore(we) && mEnd.isAfter(ws) }
            }
        }
    }

    // Merged "report view" of activity: non-overlapping manuals + SH workouts + per-day SH
    // ambient residuals. Built once here so the PNG share path AND the Sheets export render
    // the exact same rows the user sees totalled in the report header.
    val reportActivityRows = remember(
        reportNonOverlappingManuals, shWorkoutsReport, shDailyActivityReport, reportDateRange, shStatus
    ) {
        val workoutsInRange = shWorkoutsReport.filter {
            it.startTime.atZone(zoneId).toLocalDate() in reportDateRange
        }
        val dailyInRange = shDailyActivityReport.filterKeys { it in reportDateRange }
        buildReportActivityRows(
            manuals = reportNonOverlappingManuals,
            shWorkouts = workoutsInRange,
            shDaily = dailyInRange,
            shAvailable = shStatus == SamsungHealthRepository.Status.AVAILABLE,
            zoneId = zoneId
        )
    }

    // Per-day BMR snapshot resolution for the report range. Used by the
    // Activity card's chart so each daily bar carries the BMR band that was
    // in force on that day (not today's BMR rewriting history).
    val bmrByDayReport = remember(bmrSnapshotsList, reportDateRange) {
        com.example.data.bmr.BmrResolver.resolveBmrByDay(bmrSnapshotsList, reportDateRange)
    }

    // Per-day macro buckets feeding the Meals card's "Evolução" chart. Walks
    // every day in the range (incl. empty) so the chart can paint the empty
    // pill on no-log days.
    val dailyMacroBuckets = remember(filteredFoods, reportDateRange, zoneId) {
        buildDailyMacroBuckets(filteredFoods, reportDateRange, zoneId)
    }

    // Per-day activity buckets feeding the Activity card's "Evolução" chart.
    // INVARIANT: sum(totalKcal) across all buckets equals `burnedCalories`
    // computed a few lines down. If you tweak one, tweak the other.
    val dailyActivityBuckets = remember(
        reportNonOverlappingManuals, shWorkoutsReport, shDailyActivityReport,
        bmrByDayReport, reportDateRange, shStatus, zoneId
    ) {
        val workoutsInRange = shWorkoutsReport.filter {
            it.startTime.atZone(zoneId).toLocalDate() in reportDateRange
        }
        val dailyInRange = shDailyActivityReport.filterKeys { it in reportDateRange }
        buildDailyActivityBuckets(
            manuals = reportNonOverlappingManuals,
            shWorkouts = workoutsInRange,
            shDaily = dailyInRange,
            bmrByDay = bmrByDayReport,
            range = reportDateRange,
            shAvailable = shStatus == SamsungHealthRepository.Status.AVAILABLE,
            zoneId = zoneId
        )
    }

    val consumedCalories = filteredFoods.sumOf { it.calories }
    // burnedCalories = activity (manual + SH active) + TMB summed day-by-day.
    // Same invariant as the dashboard: every "Burned" total includes TMB so
    // Net = Consumed - Burned reflects a real deficit rather than ignoring
    // resting metabolism.
    val burnedCalories =
        reportNonOverlappingManuals.sumOf { it.caloriesBurned } +
            reportShActiveCal +
            aggregatedBmrReport.kcal.toInt()
    val netCalories = consumedCalories - burnedCalories
    val protein = filteredFoods.sumOf { it.protein }
    val carbs = filteredFoods.sumOf { it.carbs }
    val fat = filteredFoods.sumOf { it.fat }
    // Nullable rollups: NULL when NO food in the window carries the subvalue
    // (all-legacy window → NutritionTableBody renders the single-row fallback).
    // When at least one food carries it, we surface the sum (unknowns absorb as
    // 0 — same trade-off documented on the dashboard split bar).
    val freeSugarsReport: Double? = filteredFoods
        .takeIf { it.any { f -> f.freeSugarsG != null } }
        ?.sumOf { (it.freeSugarsG ?: 0.0).coerceAtMost(it.carbs) }
    val satFatReport: Double? = filteredFoods
        .takeIf { it.any { f -> f.saturatedFatG != null } }
        ?.sumOf { (it.saturatedFatG ?: 0.0).coerceAtMost(it.fat) }

    // Off-screen capture surface for "Share Image" / "Partilhar imagem".
    // The shareable PNG is produced by a SECOND, off-screen Compose tree that
    // re-renders the same data subtree (header, date bar, pies, tables, SH card)
    // WITHOUT the period picker — see [shareableReportContent] below and the
    // share button's onClick at the bottom of this composable. The on-screen
    // tab is rendered normally (picker included) and is never touched by the
    // capture path.
    val captureScope = rememberCoroutineScope()

    // "Partilhar report" (detailed HTML) is generating — disables the button
    // and swaps its label while the 5 charts render sequentially (~170ms), so a
    // double-tap can't launch two concurrent off-screen composition passes.
    var generatingHtml by remember { mutableStateOf(false) }

    // The shareable subtree, defined once and reused twice:
    //   1) Inline below the picker on-screen so the user sees the same content
    //      they will be sharing (minus the picker, which is just a control).
    //   2) Inside the off-screen ComposeView when the share button is tapped.
    // Captures the local variables of ReportTab by closure — no params needed,
    // no risk of the on-screen tab and the shared PNG drifting apart.
    val shareableReportContent: @Composable () -> Unit = {
        Column(
            // Margem em cima do título no shareable PNG: o on-screen tab tem
            // padding do Scaffold + status bar a fazer-lhe de respirador, mas
            // a imagem partilhada é desenhada do pixel zero — sem este top
            // padding o "Análise de X" fica colado ao topo da imagem.
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header — title + subtitle, personalizado com o nome do
            // utilizador para o nutricionista identificar de quem é o
            // relatório (e.g. "Análise de Madhvi").
            Column {
                val brandBrush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.White,
                        0.4f to Color.White,
                        1.0f to Color.White.copy(alpha = 0.5f)
                    )
                )
                val titleText = run {
                    val base = tr("Analytics", userProfile.language)
                    val ofWord = tr("of", userProfile.language)
                    val name = userProfile.name.takeIf { it.isNotBlank() }
                    if (name != null) "$base $ofWord $name" else base
                }
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.headlineMedium.copy(brush = brandBrush),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = tr("Nutrition and activity status", userProfile.language),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = AccentSoft
                )
            }

            // Date bar (no swipe gesture in shareable — gestures don't render anyway)
            DateSelectorBar(
                selectedDate = selectedDateReport,
                period = timescale,
                language = userProfile.language,
                onDateChanged = { selectedDateReport = it }
            )

            // VISUAL PIE CHARTS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NutritionPieChart(
                    protein = protein,
                    carbs = carbs,
                    fat = fat,
                    lang = userProfile.language,
                    modifier = Modifier.weight(1f)
                )
                ActivityPieChartFromRows(
                    rows = reportActivityRows,
                    lang = userProfile.language,
                    modifier = Modifier.weight(1f)
                )
            }

            // METRIC TABLE — share-PNG uses the same MealsReportCard wrapper
            // the on-screen path uses, but with `showEvolution = false` so the
            // collapsible Evolução chart is not part of the partilhada image.
            MealsReportCard(
                macroBuckets = dailyMacroBuckets,
                targetsByDay = targetsByDayReport,
                consumedCalories = consumedCalories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                freeSugarsG = freeSugarsReport,
                saturatedFatG = satFatReport,
                mealCount = filteredFoods.size,
                timescale = timescale,
                lang = userProfile.language,
                modifier = Modifier.fillMaxWidth(),
                aggregatedTargets = aggregatedTargetsReport,
                showEvolution = false
            )

            // (Activity Metrics / Samsung Health card OMITIDO do shareable —
            // o nutricionista quer só o resumo nutricional + actividades. Os
            // dados de steps/active min/calories burned do SH ficam fora da
            // imagem partilhada para evitar uma cardona pesada que distorce
            // o layout. Continuam visíveis on-screen no tab Análise.)

            // ACTIVITY METRICS TABLE — same wrapper, also without Evolução.
            ActivityReportCard(
                activityBuckets = dailyActivityBuckets,
                targetsByDay = targetsByDayReport,
                rows = reportActivityRows,
                distanceMeters = reportPeriodDistanceMeters,
                activityOnlyKcal = burnedCalories - aggregatedBmrReport.kcal.toInt(),
                timescale = timescale,
                lang = userProfile.language,
                modifier = Modifier.fillMaxWidth(),
                showEvolution = false
            )

            // OVERVIEW SUMMARY — included on the shareable PNG so the
            // nutritionist gets the energy-balance synthesis at the
            // bottom of the image. Body-composition rows are surfaced as
            // a service to the reader; if Hugo decides this is too much
            // personal info on a shared image we can switch to passing
            // `latestBodyComp = null` here to mirror the existing PNG
            // pattern (food card already omits weight on the PNG).
            // Evolution chart is suppressed on the PNG path.
            // OVERVIEW SUMMARY — energy balance only on the shareable PNG.
            // No evolution chart on this card (removed 2026-06-26); body
            // composition lives in its own card below (also rendered on
            // the PNG with showEvolution=false).
            OverviewReportCard(
                consumedCalories = consumedCalories,
                activityKcal = burnedCalories - aggregatedBmrReport.kcal.toInt(),
                bmrKcal = aggregatedBmrReport.kcal.toInt(),
                netCalories = netCalories,
                timescale = timescale,
                lang = userProfile.language,
                modifier = Modifier.fillMaxWidth()
            )
            BodyCompositionCard(
                latestBodyComp = latestBodyComp,
                bodyCompHistory = emptyList(),
                timescale = timescale,
                lang = userProfile.language,
                modifier = Modifier.fillMaxWidth(),
                showEvolution = false
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header — title + subtítulo (também aparece no shareable, mas aqui no
        // ecrã queremos que as pills fiquem ENTRE o título e a barra de datas,
        // como nos outros tabs. Por isso renderizamos o header separado da
        // shareableReportContent para o on-screen.)
        Column {
            val brandBrush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color.White,
                    0.4f to Color.White,
                    1.0f to Color.White.copy(alpha = 0.5f)
                )
            )
            Text(
                text = tr("Analytics", userProfile.language),
                style = MaterialTheme.typography.headlineMedium.copy(brush = brandBrush),
                fontWeight = FontWeight.Bold
            )
            Text(
                text = tr("Nutrition and activity status", userProfile.language),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = AccentSoft
            )
        }

        // Period toggle button — pill row uniforme
        // Swipe horizontal em cima do bloco cicla entre Daily/Weekly/Monthly
        // sem wrap (consome o gesto, por isso o HorizontalPager pai não muda
        // de tab enquanto o utilizador está em cima do picker).
        val reportPeriods = remember { listOf("Daily", "Weekly", "Monthly") }
        com.example.ui.components.SegmentedPillRow(
            options = reportPeriods,
            selected = timescale,
            onSelect = { timescale = it },
            labelFor = { tr(it, userProfile.language) },
            rowModifier = Modifier.horizontalSwipe(
                onSwipeLeft = {
                    val i = reportPeriods.indexOf(timescale)
                    if (i > 0) timescale = reportPeriods[i - 1]
                },
                onSwipeRight = {
                    val i = reportPeriods.indexOf(timescale)
                    if (i in 0 until reportPeriods.lastIndex) {
                        timescale = reportPeriods[i + 1]
                    }
                }
            )
        )

        // -------- ON-SCREEN BODY (everything below the picker) --------
        // Date bar with swipe gesture on screen — the swipe lambdas reference
        // selectedDateReport, so they must live inside the on-screen tree (the
        // off-screen render uses the plain DateSelectorBar without swipe).
        Box(
            modifier = Modifier.horizontalSwipe(
                onSwipeLeft = {
                    selectedDateReport = when (timescale) {
                        "Daily" -> selectedDateReport.plusDays(1)
                        "Weekly" -> selectedDateReport.plusWeeks(1)
                        else -> selectedDateReport.plusMonths(1)
                    }
                },
                onSwipeRight = {
                    selectedDateReport = when (timescale) {
                        "Daily" -> selectedDateReport.minusDays(1)
                        "Weekly" -> selectedDateReport.minusWeeks(1)
                        else -> selectedDateReport.minusMonths(1)
                    }
                }
            )
        ) {
            DateSelectorBar(
                selectedDate = selectedDateReport,
                period = timescale,
                language = userProfile.language,
                onDateChanged = { selectedDateReport = it }
            )
        }

        // VISUAL PIE CHARTS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NutritionPieChart(
                protein = protein,
                carbs = carbs,
                fat = fat,
                lang = userProfile.language,
                modifier = Modifier.weight(1f)
            )
            ActivityPieChartFromRows(
                rows = reportActivityRows,
                lang = userProfile.language,
                modifier = Modifier.weight(1f)
            )
        }

        // METRIC TABLE — redesigned 2026-06-22 (refinement):
        //   1) Detail rows (intake + macros + meal count).
        //   2) Optional collapsible "Evolução" chart of macros (Weekly/Monthly).
        // Burned/Balance and body composition are no longer here — see the
        // dedicated `OverviewReportCard` at the bottom of the tab.
        MealsReportCard(
            macroBuckets = dailyMacroBuckets,
            targetsByDay = targetsByDayReport,
            consumedCalories = consumedCalories,
            protein = protein,
            carbs = carbs,
            fat = fat,
            freeSugarsG = freeSugarsReport,
            saturatedFatG = satFatReport,
            mealCount = filteredFoods.size,
            timescale = timescale,
            lang = userProfile.language,
            modifier = Modifier.fillMaxWidth(),
            aggregatedTargets = aggregatedTargetsReport
        )

        // 2026-06-18 — removido o "Activity Metrics ✓ S-Health" card (steps /
        // active minutes / calories burned + 3 corações concêntricos). Os
        // mesmos números aparecem na tab Painel; manter aqui era redundância.

        // ACTIVITY METRICS TABLE — same redesign as the Meals card above.
        // `activityOnlyKcal` excludes TMB so "Total Queimado" represents pure
        // physical activity (workouts + ambient SH), matching the breakdown
        // sum and the Evolução chart segments.
        ActivityReportCard(
            activityBuckets = dailyActivityBuckets,
            targetsByDay = targetsByDayReport,
            rows = reportActivityRows,
            distanceMeters = reportPeriodDistanceMeters,
            activityOnlyKcal = burnedCalories - aggregatedBmrReport.kcal.toInt(),
            timescale = timescale,
            lang = userProfile.language,
            modifier = Modifier.fillMaxWidth()
        )

        // Third card — "Balanço". Activity and TMB are surfaced
        // separately so the activity number agrees with the Atividade
        // card above. No chart on this card (removed 2026-06-26): the
        // table is the entire content. Body composition lives in its
        // own card below.
        OverviewReportCard(
            consumedCalories = consumedCalories,
            activityKcal = burnedCalories - aggregatedBmrReport.kcal.toInt(),
            bmrKcal = aggregatedBmrReport.kcal.toInt(),
            netCalories = netCalories,
            timescale = timescale,
            lang = userProfile.language,
            modifier = Modifier.fillMaxWidth()
        )

        // Composição Corporal — own card, own chart. Hides itself when
        // no body-comp readings exist at all.
        BodyCompositionCard(
            latestBodyComp = latestBodyComp,
            bodyCompHistory = bodyCompHistory,
            timescale = timescale,
            lang = userProfile.language,
            modifier = Modifier.fillMaxWidth()
        )

        // Helper file share function defined inside ReportTab scope
        fun shareFile(file: java.io.File, mimeType: String) {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.example.fileprovider", file)
            val intent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                type = mimeType
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share Report via..."))
        }

        // SHARE CARD — wraps the two share actions in the same dark card chrome
        // as the rest of the Análise tab (Composição, Drive export) instead of
        // two bare full-width buttons that read as an older, un-themed layout.
        Card(
            modifier = Modifier.fillMaxWidth().testTag("share_card"),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.IosShare,
                        contentDescription = tr("share.section.title", userProfile.language),
                        tint = AccentSoft,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        tr("share.section.title", userProfile.language),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Text(
                    tr("share.section.subtitle", userProfile.language),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )

                Button(
                    onClick = {
                        // Render the shareable subtree off-screen (no period picker,
                        // no Samsung Health card) and write to a PNG. The on-screen
                        // tab is left untouched — no flicker, no relayout. See
                        // [renderComposableToBitmap] for the ComposeView trick.
                        captureScope.launch {
                            // Use the device's actual screen width minus our 16dp
                            // horizontal padding (matches the ReportTab's outer
                            // Column padding) so text/value spacing in the table
                            // matches what the user sees on-screen, not a synthetic
                            // 360dp narrow width that pushes labels and values apart.
                            val dm = context.resources.displayMetrics
                            val screenContentWidthPx = (dm.widthPixels - (16 * dm.density * 2).toInt())
                                .coerceAtLeast((300 * dm.density).toInt())
                            val bmp = renderComposableToBitmap(
                                context = context,
                                widthPx = screenContentWidthPx,
                                content = shareableReportContent
                            )
                            val file = ReportCapture.writePng(context, bmp)
                            shareFile(file, "image/png")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("share_png_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8B5CF6),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(tr("Share Image", userProfile.language), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                }

        // "PARTILHAR REPORT" — detailed HTML report (a par do PNG resumo).
        // Renders each Compose-Canvas chart off-screen to a base64 PNG and
        // embeds them in a self-contained HTML file (see [ReportHtmlBuilder]).
        // The PNG button above stays the compact *resumo*; this is the *detalhe*
        // with the evolution charts the PNG omits.
        Button(
            enabled = !generatingHtml,
            onClick = {
                captureScope.launch {
                    generatingHtml = true
                    try {
                        val dm = context.resources.displayMetrics
                        val fullW = (dm.widthPixels - (16 * dm.density * 2).toInt())
                            .coerceAtLeast((300 * dm.density).toInt())
                        val halfW = fullW / 2
                        val lang = userProfile.language

                        // 1) Build interactive chart DATA (no PNG rasterization).
                        // Compose Color → CSS hex bridge for the pure builder.
                        fun cssHex(c: Color): String = String.format("#%06X", 0xFFFFFF and c.toArgb())

                        val macroPie = if (protein + carbs + fat > 0.0) {
                            ReportHtmlBuilder.PieData(
                                slices = listOf(
                                    ReportHtmlBuilder.Slice(tr("Protein", lang), protein, "#F97316"),
                                    ReportHtmlBuilder.Slice(tr("Carbs", lang), carbs, "#8B5CF6"),
                                    ReportHtmlBuilder.Slice(tr("Fats", lang), fat, "#EAB308"),
                                ),
                                unit = "g"
                            )
                        } else null
                        val activityPie = if (reportActivityRows.sumOf { it.caloriesBurned } > 0) {
                            val slices = ActivityType.values().mapNotNull { type ->
                                val cals = reportActivityRows.filter { it.toActivityType() == type }.sumOf { it.caloriesBurned }
                                if (cals > 0) ReportHtmlBuilder.Slice(tr(type.displayKey, lang), cals.toDouble(), cssHex(type.color)) else null
                            }
                            if (slices.isNotEmpty()) ReportHtmlBuilder.PieData(slices, unit = "kcal") else null
                        } else null

                        // Evolution charts only make sense with >1 day-bucket
                        // (Weekly/Monthly). In Daily the range is a single day →
                        // one bar; skip then to keep the HTML truthful.
                        val showEvo = timescale != "Daily"
                        fun barsData(bars: List<com.example.ui.components.BarBucket>, targetPerDay: Map<java.time.LocalDate, Double>): ReportHtmlBuilder.BarsData? {
                            if (bars.none { it.segments.isNotEmpty() }) return null
                            val colLabel = pickColumnLabel(bars, lang)
                            return ReportHtmlBuilder.BarsData(
                                days = bars.map { b ->
                                    ReportHtmlBuilder.DayBar(
                                        label = colLabel(b.date),
                                        segments = b.segments.map { ReportHtmlBuilder.Seg(it.value, cssHex(it.color)) },
                                        target = targetPerDay[b.date]
                                    )
                                }
                            )
                        }
                        val mealsEvo = if (showEvo) barsData(mealBucketsToBars(dailyMacroBuckets, lang), targetsByDayReport.mapValues { it.value.targetKcal.toDouble() }) else null
                        val activityEvo = if (showEvo) barsData(activityBucketsToBars(dailyActivityBuckets, lang), targetsByDayReport.mapValues { it.value.targetActivityCalories.toDouble() }) else null

                        // Body-composition multiline — mirror the on-screen
                        // Evolução card exactly: per-DAY factual readings (dedup
                        // multiple weigh-ins per day → last), 4 lines
                        // (weight/muscle/fat/water) with BODYCOMP_PALETTE colours,
                        // now as interactive SVG (tap a point → KPI retarget).
                        val bodyComp = if (bodyCompHistory.isNotEmpty()) {
                            val perDay = bodyCompHistory
                                .groupBy {
                                    val ts = it.sourceTimestampMs ?: it.capturedAtMs
                                    java.time.Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate()
                                }
                                .toSortedMap()
                                .values
                                .map { sameDay -> sameDay.maxByOrNull { it.sourceTimestampMs ?: it.capturedAtMs }!! }
                                .takeLast(200)
                            val hasWater = perDay.any { it.totalBodyWaterKg != null }
                            val srcLines = buildList {
                                add(bodyCompLinePerSnapshot(tr("bodycomp.short.weight", lang), perDay, { "neutral" }) { it.weightKg.toDouble() })
                                add(bodyCompLinePerSnapshot(
                                    tr("bodycomp.short.muscle", lang), perDay,
                                    tone = { d -> if (d > 0) "good" else "bad" },
                                    kpiPicker = { s -> s.muscleMassKg?.let { it.toDouble() / s.weightKg * 100.0 } },
                                    kpiUnit = "%",
                                    kpiTone = { d -> if (d > 0) "good" else "bad" }
                                ) { it.muscleMassKg?.toDouble() })
                                add(bodyCompLinePerSnapshot(
                                    tr("bodycomp.short.fat", lang), perDay,
                                    tone = { d -> if (d > 0) "bad" else "good" },
                                    kpiPicker = { s -> s.bodyFatPct?.toDouble() },
                                    kpiUnit = "%",
                                    kpiTone = { d -> if (d > 0) "bad" else "good" }
                                ) { s ->
                                    val f = s.bodyFatPct
                                    if (f != null) s.weightKg.toDouble() * f / 100.0 else null
                                })
                                if (hasWater) {
                                    add(bodyCompLinePerSnapshot(
                                        tr("bodycomp.short.water", lang), perDay,
                                        tone = { "neutral" },
                                        kpiPicker = { s -> s.totalBodyWaterKg?.let { it.toDouble() / s.weightKg * 100.0 } },
                                        kpiUnit = "%",
                                        kpiTone = { "neutral" }
                                    ) { it.totalBodyWaterKg?.toDouble() })
                                }
                            }.filter { it.values.any { v -> v != null } }
                            val bodyCompPalette = listOf("#A78BFA", "#10B981", "#F97316", "#38BDF8")
                            if (srcLines.isNotEmpty()) {
                                ReportHtmlBuilder.MultiData(
                                    lines = srcLines.mapIndexed { i, ln ->
                                        ReportHtmlBuilder.LineData(
                                            label = ln.label,
                                            unit = ln.unit ?: "kg",
                                            colorHex = bodyCompPalette[i % bodyCompPalette.size],
                                            values = ln.values,
                                            latest = ln.kpi?.latest,
                                            delta = ln.kpi?.delta,
                                            deltaTone = ln.kpi?.deltaTone ?: "neutral",
                                            kpiUnit = ln.kpiUnit,
                                            kpiValues = ln.kpiValues
                                        )
                                    },
                                    xLabels = perDay.map { snapshotColumnLabel(it, zoneId) }
                                )
                            } else null
                        } else null

                        val hasTargets = aggregatedTargetsReport.days > 0

                        // Body-comp KPI rows — mirror BodyCompositionCard's table.
                        val ptLocale = java.util.Locale("pt", "PT")
                        val bodyCompRows = buildList {
                            val bc = latestBodyComp
                            val weightKg = bc?.weightKg
                            val bodyFatPct = bc?.bodyFatPct
                            val muscleMassKg = bc?.muscleMassKg
                            val totalBodyWaterKg = bc?.totalBodyWaterKg
                            // Order + kg+% mirror the Composição Corporal table:
                            // weight → lean → muscle → fat → water (2026-08-19).
                            if (weightKg != null) add(ReportHtmlBuilder.BodyCompRow(tr("Current Weight", lang), String.format(ptLocale, "%.1f kg", weightKg), "#A78BFA"))
                            if (weightKg != null && bodyFatPct != null) add(ReportHtmlBuilder.BodyCompRow(tr("Lean Body Mass", lang), String.format(ptLocale, "%.1f kg (%.1f%%)", weightKg * (1f - bodyFatPct / 100f), 100f - bodyFatPct), "#10B981"))
                            if (muscleMassKg != null && weightKg != null) add(ReportHtmlBuilder.BodyCompRow(tr("Muscle Mass", lang), String.format(ptLocale, "%.1f kg (%.1f%%)", muscleMassKg, muscleMassKg / weightKg * 100f), "#22D3EE"))
                            if (bodyFatPct != null && weightKg != null) add(ReportHtmlBuilder.BodyCompRow(tr("Fat Mass", lang), String.format(ptLocale, "%.1f kg (%.1f%%)", weightKg * bodyFatPct / 100f, bodyFatPct), "#F59E0B"))
                            if (totalBodyWaterKg != null && weightKg != null) add(ReportHtmlBuilder.BodyCompRow(tr("Body Water", lang), String.format(ptLocale, "%.1f kg (%.1f%%)", totalBodyWaterKg, totalBodyWaterKg / weightKg * 100f), "#38BDF8"))
                        }

                        val rangeFmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", localeForLanguage(lang))
                        val rangeLabel = if (reportDateRange.start == reportDateRange.endInclusive) {
                            rangeFmt.format(reportDateRange.start)
                        } else {
                            "${rangeFmt.format(reportDateRange.start)} – ${rangeFmt.format(reportDateRange.endInclusive)}"
                        }
                        val generatedOn = java.time.format.DateTimeFormatter
                            .ofPattern("d MMM yyyy HH:mm", localeForLanguage(lang))
                            .format(java.time.LocalDateTime.now(zoneId))

                        // Compose Color → CSS hex (#RRGGBB) for the pure builder.

                        // Alimentação detail rows — mirror NutritionTableBody exactly
                        // (verdict colour + "/ alvo" when targets exist).
                        val foodRows = buildList {
                            add(ReportHtmlBuilder.DetailRow(tr("Food Intake (Consumed)", lang), "$consumedCalories kcal", cssHex(AccentPrimary), highlighted = true))
                            add(ReportHtmlBuilder.DetailRow(
                                tr("Total Protein", lang),
                                if (hasTargets) String.format("%.1f g / %.0f g", protein, aggregatedTargetsReport.protein) else String.format("%.1f g", protein),
                                if (hasTargets) cssHex(proteinVerdict(protein, aggregatedTargetsReport.protein).color()) else "#F97316"
                            ))
                            add(ReportHtmlBuilder.DetailRow(
                                tr("Total Carbohydrates", lang),
                                if (hasTargets) String.format("%.1f g / %.0f g", carbs, aggregatedTargetsReport.carbs) else String.format("%.1f g", carbs),
                                if (hasTargets) cssHex(totalVerdict(carbs, aggregatedTargetsReport.carbs).color()) else "#8B5CF6"
                            ))
                            if (freeSugarsReport != null) {
                                val clampedFree = freeSugarsReport.coerceAtMost(carbs)
                                add(ReportHtmlBuilder.DetailRow(tr("Free Sugar", lang), String.format("%.1f g", clampedFree), "#EC4899", indented = true))
                            }
                            if (satFatReport != null) {
                                val clampedSat = satFatReport.coerceIn(0.0, fat)
                                val unsat = (fat - clampedSat).coerceAtLeast(0.0)
                                add(ReportHtmlBuilder.DetailRow(tr("Unsaturated Fat", lang), String.format("%.1f g", unsat), if (hasTargets) cssHex(totalVerdict(fat, aggregatedTargetsReport.fat).color()) else "#EAB308"))
                                add(ReportHtmlBuilder.DetailRow(tr("Saturated Fat", lang), String.format("%.1f g", clampedSat), cssHex(saturatedVerdict(clampedSat, fat).color()), indented = true))
                            } else {
                                add(ReportHtmlBuilder.DetailRow(
                                    tr("Fat", lang),
                                    if (hasTargets) String.format("%.1f g / %.0f g", fat, aggregatedTargetsReport.fat) else String.format("%.1f g", fat),
                                    if (hasTargets) cssHex(totalVerdict(fat, aggregatedTargetsReport.fat).color()) else "#EAB308"
                                ))
                            }
                            add(ReportHtmlBuilder.DetailRow(tr("Logged Meals Size", lang), "${filteredFoods.size} ${tr("dishes", lang)}", "#EC4899"))
                        }

                        // Atividade detail rows — mirror ActivitySummaryTableBody exactly.
                        val activityRows = buildList {
                            val totalDuration = reportActivityRows.sumOf { it.durationMinutes }
                            add(ReportHtmlBuilder.DetailRow(tr("Total Energy Burned", lang), "${burnedCalories - aggregatedBmrReport.kcal.toInt()} kcal", "#EF4444", highlighted = true))
                            add(ReportHtmlBuilder.DetailRow(tr("Total Workout Time", lang), "$totalDuration min", cssHex(AccentPrimary)))
                            reportPeriodDistanceMeters?.takeIf { it > 0f }?.let {
                                add(ReportHtmlBuilder.DetailRow(tr("Total Distance", lang), String.format(ptLocale, "%.2f km", it / 1000f), "#06B6D4"))
                            }
                            add(ReportHtmlBuilder.DetailRow(tr("Workouts Count", lang), "${reportActivityRows.size} ${tr("sessions", lang)}", "#3B82F6"))
                            // Per-type breakdown — same grouping as ActivitySummaryTableBody.
                            ActivityType.values().forEach { type ->
                                val match = reportActivityRows.filter { it.toActivityType() == type }
                                val cals = match.sumOf { it.caloriesBurned }
                                val duration = match.sumOf { it.durationMinutes }
                                if (cals > 0 || duration > 0) {
                                    val modalities = match.mapNotNull { it.sourceLabel?.takeIf { s -> s.isNotBlank() } }.distinct().takeIf { it.isNotEmpty() }
                                    val label = modalities?.joinToString(", ") ?: tr(type.displayKey, lang)
                                    add(ReportHtmlBuilder.DetailRow(label, "$cals kcal ($duration ${tr("min", lang)})", cssHex(type.color), indented = true))
                                }
                            }
                        }

                        // 3) Build HTML + write + share.
                        val html = ReportHtmlBuilder.build(
                            userName = userProfile.name,
                            lang = lang,
                            timescale = timescale,
                            rangeLabel = rangeLabel,
                            consumedCalories = consumedCalories,
                            activityKcal = burnedCalories - aggregatedBmrReport.kcal.toInt(),
                            bmrKcal = aggregatedBmrReport.kcal.toInt(),
                            netCalories = netCalories,
                            foodRows = foodRows,
                            activityRows = activityRows,
                            bodyCompRows = bodyCompRows,
                            charts = ReportHtmlBuilder.Charts(
                                macroPie = macroPie,
                                activityPie = activityPie,
                                mealsEvolution = mealsEvo,
                                activityEvolution = activityEvo,
                                bodyComp = bodyComp
                            ),
                            generatedOn = generatedOn
                        )
                        val file = ReportCapture.writeHtml(context, html)
                        shareFile(file, "text/html")
                    } finally {
                        generatingHtml = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("share_html_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF6366F1),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (generatingHtml) "${tr("Saving...", userProfile.language)}" else tr("Share Report", userProfile.language),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
        }
            } // end share Card Column
        } // end share Card

        // DRIVE EXPORT CARD
        // Replaces the previous Google Sheets / OAuth flow. The user picks a Drive
        // folder once via SAF; after that, "Export to Drive" writes a fresh
        // `diario_nutricao.xlsx` into that folder, replacing any earlier copy. The
        // Drive app on the device handles upload — we never touch OAuth scopes.
        Card(
            modifier = Modifier.fillMaxWidth().testTag("drive_export_card"),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.CloudUpload,
                        contentDescription = tr("Drive Export", userProfile.language),
                        tint = AccentSoft,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        tr("Export to Google Drive", userProfile.language),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Text(
                    tr("Writes a single diario_nutricao.xlsx into the chosen Drive folder. The link stays the same — share it once with the nutritionist.", userProfile.language),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )

                if (exportFolderUri == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF3B82F6).copy(alpha = 0.1f))
                            .border(1.dp, Color(0xFF60A5FA).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            tr("No folder chosen yet. The first export will open the folder picker.", userProfile.language),
                            color = Color(0xFF93C5FD),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AccentPrimary.copy(alpha = 0.1f))
                            .border(1.dp, AccentSoft.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${tr("Folder", userProfile.language)}: ${exportFolderName ?: "—"}",
                            color = Color(0xFFA7F3D0),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = tr("Change", userProfile.language),
                            color = Color(0xFFA7F3D0),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { pickFolderLauncher.launch(null) }
                                .padding(start = 8.dp)
                        )
                    }
                }

                exportError?.let { errMsg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFEF4444).copy(alpha = 0.12f))
                            .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = errMsg,
                            color = Color(0xFFFCA5A5),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = tr("Dismiss", userProfile.language),
                            color = Color(0xFFFCA5A5),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { viewModel.clearExportError() }
                                .padding(start = 8.dp)
                        )
                    }
                }

                exportSuccess?.let { msg ->
                    val successUri by viewModel.exportSuccessUri.collectAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AccentPrimary.copy(alpha = 0.15f))
                            .border(1.dp, AccentSoft.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .then(
                                if (successUri != null) {
                                    Modifier.clickable {
                                        // Open the freshly-exported XLSX. ACTION_VIEW with the SAF
                                        // URI hands it to whichever app the user picks (Google Sheets
                                        // / Drive when installed). FLAG_GRANT_READ_URI_PERMISSION is
                                        // required for the receiving app to read across processes.
                                        val uri = android.net.Uri.parse(successUri)
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setDataAndType(
                                                uri,
                                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                            )
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            Toast.makeText(
                                                context,
                                                tr("No app available to open this file.", userProfile.language),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                } else Modifier
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = AccentSoft,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = msg,
                            color = Color(0xFFA7F3D0),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "OK",
                            color = Color(0xFFA7F3D0),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { viewModel.clearExportSuccess() }
                                .padding(start = 8.dp)
                        )
                    }
                }

                // "Last export: …" — read straight from the persisted timestamp
                // (which both the manual button and the background Worker write
                // to). Hidden when there's no successful export yet, to avoid
                // the useless "Last export: never" line that would otherwise
                // greet a fresh install.
                lastExportAtMs?.let { ms ->
                    val locale = remember(userProfile.language) {
                        java.util.Locale.forLanguageTag(userProfile.language)
                    }
                    val formatter = remember(locale) {
                        java.time.format.DateTimeFormatter
                            .ofPattern("dd MMM yyyy, HH:mm", locale)
                    }
                    val text = java.time.Instant.ofEpochMilli(ms)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(formatter)
                    Text(
                        text = "${tr("Last export", userProfile.language)}: $text",
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Button(
                    onClick = {
                        viewModel.clearExportError()
                        viewModel.clearExportSuccess()
                        if (exportFolderUri == null) {
                            pickFolderLauncher.launch(null)
                        } else {
                            viewModel.exportNutritionXlsx(context)
                        }
                    },
                    enabled = !isExporting,
                    modifier = Modifier.fillMaxWidth().height(44.dp).testTag("export_drive_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("Exporting...", userProfile.language), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (exportFolderUri == null)
                                tr("Choose folder & export", userProfile.language)
                            else
                                tr("Export to Drive", userProfile.language),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Destructive actions live in their own block at the foot of the Reports
        // tab. Each button shows an AlertDialog before doing anything; both
        // operations are unrecoverable and have a history of being clicked by
        // accident, so a single tap is never enough.
        var showWipeConfirm by remember { mutableStateOf(false) }
        var showRestoreConfirm by remember { mutableStateOf(false) }
        var restoreError by remember { mutableStateOf<String?>(null) }

        // SAF picker for the .db file the user wants to restore from. Mime
        // "application/octet-stream" is the conservative choice — Drive often
        // hands .db files back as octet-stream. The DbRestoreImporter validates
        // the SQLite magic header before doing anything destructive, so a wrong
        // pick is safe.
        val restoreLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                // GlobalScope is appropriate here: the success path
                // deliberately kills the process to force Room to reopen the
                // restored DB. Tying this to viewModelScope would cancel the
                // coroutine if the user rotated the screen mid-restore. The
                // failure paths surface their result via mutableState above
                // before returning, so leaks are bounded.
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val outcome = com.example.data.export.DbRestoreImporter.restore(context, uri)
                    when (outcome) {
                        is com.example.data.export.DbRestoreImporter.Outcome.Success -> {
                            // Kill the process so Room reopens the new database
                            // cleanly on next launch. Without this the in-memory
                            // singleton would keep pointing at stale pages.
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                        is com.example.data.export.DbRestoreImporter.Outcome.InvalidFile -> {
                            restoreError = tr("Selected file is not a valid database backup.", userProfile.language)
                        }
                        is com.example.data.export.DbRestoreImporter.Outcome.Failure -> {
                            restoreError = tr("Restore failed: %s", userProfile.language).format(outcome.message)
                        }
                    }
                }
            }
        }

        // ── About / Advanced ───────────────────────────────────────────────
        // Collapsed by default. Houses the destructive actions (wipe + restore)
        // alongside build-info breadcrumbs (version, build date, authors). The
        // destructive buttons used to live raw at the foot of the tab; tucking
        // them behind a chevron prevents accidental taps while keeping the
        // information one click away.
        var aboutExpanded by remember { mutableStateOf(false) }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { aboutExpanded = !aboutExpanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = tr("About & Advanced", userProfile.language),
                    color = Color(0xFFE2E8F0),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (aboutExpanded) Icons.Rounded.KeyboardArrowUp
                                  else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8)
                )
            }

            if (aboutExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Build / version / authors. BuildConfig.BUILD_DATE is the
                    // configuration-time yyyy-MM-dd from build.gradle.kts; the
                    // versionName + versionCode come straight from defaultConfig.
                    Text(
                        text = "${tr("Version", userProfile.language)}: " +
                            "${com.example.BuildConfig.VERSION_NAME} (${com.example.BuildConfig.VERSION_CODE})",
                        color = Color(0xFFCBD5E1),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "${tr("Build date", userProfile.language)}: " +
                            com.example.BuildConfig.BUILD_DATE,
                        color = Color(0xFFCBD5E1),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "${tr("Authors", userProfile.language)}: Madhvi & Hugo",
                        color = Color(0xFFCBD5E1),
                        style = MaterialTheme.typography.labelMedium
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 1.dp,
                        color = Color(0xFF334155)
                    )

                    Button(
                        onClick = { showWipeConfirm = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("reset_logs_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF7F1D1D))
                    ) {
                        Icon(Icons.Rounded.DeleteForever, contentDescription = tr("Reset", userProfile.language))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("Reset All Nutritionist Logs", userProfile.language), fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { showRestoreConfirm = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("restore_app_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF7F1D1D))
                    ) {
                        Icon(
                            Icons.Rounded.SettingsBackupRestore,
                            contentDescription = tr("Restore entire app", userProfile.language)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("Restore entire app", userProfile.language), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (showWipeConfirm) {
            AlertDialog(
                onDismissRequest = { showWipeConfirm = false },
                title = { Text(tr("Delete app data?", userProfile.language)) },
                text = {
                    Text(
                        tr(
                            "This will permanently delete every food log and every manual activity log. Samsung Health data is not affected. This action cannot be undone.",
                            userProfile.language
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showWipeConfirm = false
                            viewModel.wipeAppData()
                        }
                    ) {
                        Text(
                            tr("Confirm", userProfile.language),
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWipeConfirm = false }) {
                        Text(tr("Cancel", userProfile.language))
                    }
                }
            )
        }

        if (showRestoreConfirm) {
            AlertDialog(
                onDismissRequest = { showRestoreConfirm = false },
                title = { Text(tr("Restore entire app?", userProfile.language)) },
                text = {
                    Text(
                        tr(
                            "This will overwrite the current database with the backup file you pick. Every food, activity, habit, BMR snapshot, target, chat message and insight will be replaced. The app will close and you will need to reopen it. This action cannot be undone.",
                            userProfile.language
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRestoreConfirm = false
                            restoreError = null
                            restoreLauncher.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*"))
                        }
                    ) {
                        Text(
                            tr("Pick backup file (.db)", userProfile.language),
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRestoreConfirm = false }) {
                        Text(tr("Cancel", userProfile.language))
                    }
                }
            )
        }

        restoreError?.let { err ->
            AlertDialog(
                onDismissRequest = { restoreError = null },
                title = { Text(tr("Restore entire app", userProfile.language)) },
                text = { Text(err) },
                confirmButton = {
                    TextButton(onClick = { restoreError = null }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

// ==========================================
// PORTAL GRAPHICS & CHAT LAYOUT HELPERS
// ==========================================

@Composable
fun ApiKeyMissingLayout(lang: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D).copy(alpha = 0.3f)),
            border = BorderStroke(1.dp, Color(0xFFEF4444)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.VpnKey,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = tr("Gemini API Key Missing", lang),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = tr("Please secure your key inside the AI Studio Secrets Panel. Do not hardcode values directly into the Gradle settings.", lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFCA5A5),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun BulletPointRow(text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("•", color = AccentPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF94A3B8)
        )
    }
}

@Composable
fun EmptyChatSuggestionsLayout(
    systemInstruction: String,
    lang: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(AccentPrimary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.BubbleChart,
                contentDescription = null,
                tint = AccentPrimary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            tr("AI Nutrition Consultation Desk", lang),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            tr("Consult Gemini Pro directly with your personalized dietary and workout queries.", lang),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            textAlign = TextAlign.Center
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = tr("Recommended Queries & Visual Inputs:", lang),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AccentSoft
                )
                
                BulletPointRow(tr("Ask for customized muscle recovery meal plans.", lang))
                BulletPointRow(tr("Attach a photo of your snack or dish for nutritional estimation.", lang))
                BulletPointRow(tr("Ask how your protein absorption changes post-workout.", lang))
                BulletPointRow(tr("Compare the benefits of complex versus simple carbohydrates.", lang))
            }
        }
    }
}

/**
 * Phase 3 (#4 / #6). Reusable row inside a [ModalBottomSheet] that
 * presents a single action: an icon, a label, an optional tint colour
 * and a click target sized for comfortable touch (56 dp). Used by the
 * long-press menu on chat bubbles AND by the attach picker (Galeria /
 * Câmara) — keep the visual contract identical between both call sites
 * so the bottom sheet feels like the same component everywhere.
 */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 15.sp
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MessageBubbleItem(
    message: ChatMessage,
    lang: String,
    viewModel: ChatViewModel,
    // 2026-06-15: opt-in text selection. When `isSelectionActive` is true the
    // bubble wraps its Markdown in a SelectionContainer so the user can drag
    // selection handles. Defaults keep backwards compatibility with any code
    // path that still calls without these args.
    isSelectionActive: Boolean = false,
    onEnterSelection: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onExitSelection: () -> Unit = {}
) {
    val isUser = message.sender == "user"
    val cleanedById by viewModel.cleanedMessageText.collectAsState()
    val proposalsById by viewModel.pendingHabitProposals.collectAsState()
    val mealProposalsById by viewModel.pendingMealProposals.collectAsState()
    val activityProposalsById by viewModel.pendingActivityProposals.collectAsState()
    val insightProposalsById by viewModel.pendingInsightProposals.collectAsState()
    val reportProposalsById by viewModel.pendingReportProposals.collectAsState()
    val chartProposalsById by viewModel.pendingChartProposals.collectAsState()
    val receiptsById by viewModel.proposalReceipts.collectAsState()
    val displayText = cleanedById[message.id] ?: message.text
    val proposals = proposalsById[message.id].orEmpty()
    val mealProposals = mealProposalsById[message.id].orEmpty()
    val activityProposals = activityProposalsById[message.id].orEmpty()
    val insightProposals = insightProposalsById[message.id].orEmpty()
    val reportProposals = reportProposalsById[message.id].orEmpty()
    val chartProposals = chartProposalsById[message.id].orEmpty()
    val receipts = receiptsById[message.id].orEmpty()
    // 2026-07-06 — hoisted so we can wire tap-on-bubble = stop TTS while
    // this bubble is being read. Reused in the footer 🔊 icon below.
    val speakingId by viewModel.speakingMessageId.collectAsState()
    val isSpeakingThis = !isUser && speakingId == message.id
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Phase 3 (#4): long-press → ModalBottomSheet (Edit/Copy/Delete).
    // Edit is gated to user messages (assistant replies cannot be edited
    // because the cascade-delete-and-resubmit semantics don't make sense
    // for them). Delete cascades to all later messages; we surface that
    // explicitly via [showDeleteConfirm] before the Repository call.
    var showActionSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        // 2026-07-06 — tap on the bubble that is currently
                        // being read = stop TTS. Paridade com o botão 🔊 no
                        // rodapé (que faz o mesmo toggle via TtsController
                        // de-dup). Tap noutras bubbles (user, ou assistente
                        // não-em-leitura) permanece no-op.
                        onClick = { if (isSpeakingThis) viewModel.stopSpeaking() },
                        onLongClick = { showActionSheet = true }
                    ),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isUser) Color(0xFF1E3A8A) else Color(0xFF1E293B)
                ),
                border = BorderStroke(1.dp, if (isUser) Color(0xFF2563EB) else Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Phase C: a chat message can carry multiple images. Resolve
                    // the stored payload (legacy base64 OR JSON array of paths)
                    // and render each as a thumb. Decoding errors fall back to
                    // a placeholder; we never crash the bubble for a missing
                    // image file.
                    val imagePayload = message.imageBase64
                    if (!imagePayload.isNullOrBlank()) {
                        val parts = remember(imagePayload) { ChatImageStore.decode(imagePayload) }
                        val bitmaps = remember(imagePayload) {
                            parts.map { entry ->
                                if (ChatImageStore.isPath(entry)) {
                                    try {
                                        val uri = android.net.Uri.parse(entry)
                                        val rawPath = uri.path
                                        if (rawPath != null) {
                                            // Try the raw path first (file://
                                            // URIs land here). Fall back to
                                            // the filesDir-prefixed path for
                                            // legacy `content://com.example
                                            // .fileprovider/chat_images/...`
                                            // rows whose `uri.path` is
                                            // `/chat_images/...` only.
                                            // Decoder is EXIF-aware: rotates
                                            // landscape captures so they
                                            // render the way the user shot
                                            // them.
                                            val direct = ChatImageStore.decodeBitmapWithOrientation(rawPath)
                                            direct ?: ChatImageStore.decodeBitmapWithOrientation(
                                                "/data/data/${com.example.BuildConfig.APPLICATION_ID}/files$rawPath"
                                            )
                                        } else null
                                    } catch (e: Exception) { null }
                                } else {
                                    entry.toBitmap()
                                }
                            }
                        }
                        if (bitmaps.size == 1) {
                            bitmaps.first()?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = tr("Message Attachment", lang),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .padding(bottom = 10.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        } else if (bitmaps.size > 1) {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(bitmaps.size) { idx ->
                                    val bmp = bitmaps[idx]
                                    Box(
                                        modifier = Modifier
                                            .size(120.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    ) {
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = tr("Message Attachment", lang),
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color(0xFF334155))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Markdown rendering — Gemini answers naturally with
                    // **bold**, ### headings, ---, lists and tables. Pre-fix
                    // these characters were rendered literally; compose-richtext
                    // turns them into proper formatting. User messages contain
                    // no markdown so this is a no-op for the user side.
                    //
                    // 2026-06-15: text selection is now opt-in. By default the
                    // bubble renders RichText directly (no SelectionContainer)
                    // so the long-press menu of [combinedClickable] is the only
                    // thing that fires — no native handles, no native popup
                    // competing with the ModalBottomSheet. When the user picks
                    // "Select text" from that menu, [isSelectionActive] flips
                    // to true and the same RichText gets wrapped, exposing the
                    // native selection handles. The body is inlined twice to
                    // keep the RichText configuration identical between modes;
                    // factoring it out into a separate @Composable would force
                    // either a state holder for the style or recomputing it on
                    // every recomposition — the duplication is cheaper.
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalContentColor provides Color.White
                    ) {
                        if (isSelectionActive) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                com.halilibo.richtext.ui.material3.RichText(
                                    style = com.halilibo.richtext.ui.RichTextStyle(
                                        stringStyle = com.halilibo.richtext.ui.string.RichTextStringStyle(
                                            boldStyle = androidx.compose.ui.text.SpanStyle(
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            ),
                                            italicStyle = androidx.compose.ui.text.SpanStyle(
                                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                color = Color.White
                                            ),
                                            codeStyle = androidx.compose.ui.text.SpanStyle(
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                background = Color(0xFF0F172A),
                                                color = Color(0xFFE2E8F0)
                                            ),
                                            linkStyle = androidx.compose.ui.text.TextLinkStyles(
                                                style = androidx.compose.ui.text.SpanStyle(
                                                    color = AccentSoft,
                                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                                                )
                                            )
                                        ),
                                        codeBlockStyle = com.halilibo.richtext.ui.CodeBlockStyle(
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                color = Color(0xFFE2E8F0),
                                                fontSize = 13.sp
                                            ),
                                            modifier = Modifier
                                                .background(
                                                    Color(0xFF0F172A),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .padding(8.dp)
                                        ),
                                        tableStyle = com.halilibo.richtext.ui.TableStyle(
                                            headerTextStyle = androidx.compose.ui.text.TextStyle(
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            ),
                                            cellPadding = 6.sp,
                                            borderColor = Color(0xFF334155),
                                            borderStrokeWidth = 1f
                                        )
                                    )
                                ) {
                                    Markdown(content = displayText)
                                }
                            }
                        } else {
                            com.halilibo.richtext.ui.material3.RichText(
                                style = com.halilibo.richtext.ui.RichTextStyle(
                                    stringStyle = com.halilibo.richtext.ui.string.RichTextStringStyle(
                                        boldStyle = androidx.compose.ui.text.SpanStyle(
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        ),
                                        italicStyle = androidx.compose.ui.text.SpanStyle(
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                            color = Color.White
                                        ),
                                        codeStyle = androidx.compose.ui.text.SpanStyle(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            background = Color(0xFF0F172A),
                                            color = Color(0xFFE2E8F0)
                                        ),
                                        linkStyle = androidx.compose.ui.text.TextLinkStyles(
                                            style = androidx.compose.ui.text.SpanStyle(
                                                color = AccentSoft,
                                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                                            )
                                        )
                                    ),
                                    codeBlockStyle = com.halilibo.richtext.ui.CodeBlockStyle(
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = Color(0xFFE2E8F0),
                                            fontSize = 13.sp
                                        ),
                                        modifier = Modifier
                                            .background(
                                                Color(0xFF0F172A),
                                                RoundedCornerShape(6.dp)
                                            )
                                            .padding(8.dp)
                                    ),
                                    tableStyle = com.halilibo.richtext.ui.TableStyle(
                                        headerTextStyle = androidx.compose.ui.text.TextStyle(
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        ),
                                        cellPadding = 6.sp,
                                        borderColor = Color(0xFF334155),
                                        borderStrokeWidth = 1f
                                    )
                                )
                            ) {
                                Markdown(content = displayText)
                            }
                        }
                    }
                }
            }
            // Proposal cards: one per habit the assistant offered to memorize.
            // Disappear when the user confirms or discards.
            proposals.forEach { proposal ->
                Spacer(modifier = Modifier.height(6.dp))
                HabitProposalCard(
                    proposal = proposal,
                    language = lang,
                    onConfirm = { viewModel.confirmHabitProposal(message.id, proposal) },
                    onDiscard = { viewModel.discardHabitProposal(message.id, proposal) }
                )
            }
            // Phase D: meal-log and activity-log proposal cards.
            mealProposals.forEach { item ->
                Spacer(modifier = Modifier.height(6.dp))
                LogMealProposalCard(
                    item = item,
                    language = lang,
                    onConfirm = { viewModel.confirmMealProposal(ctx, message.id, item) },
                    onDiscard = { viewModel.discardMealProposal(message.id, item) }
                )
            }
            activityProposals.forEach { p ->
                Spacer(modifier = Modifier.height(6.dp))
                LogActivityProposalCard(
                    proposal = p,
                    language = lang,
                    onConfirm = { viewModel.confirmActivityProposal(message.id, p) },
                    onDiscard = { viewModel.discardActivityProposal(message.id, p) }
                )
            }
            insightProposals.forEach { p ->
                Spacer(modifier = Modifier.height(6.dp))
                InsightProposalCard(
                    proposal = p,
                    language = lang,
                    onConfirm = { viewModel.confirmInsightProposal(message.id, p) },
                    onDiscard = { viewModel.discardInsightProposal(message.id, p) }
                )
            }
            // Phase G: ad-hoc report card. Tapping "Generate" reuses the
            // canonical SAF/Drive pipeline behind the Reports tab button, but
            // with a date filter and a custom filename so the canonical
            // `diario_nutricao.xlsx` is left untouched.
            reportProposals.forEach { p ->
                Spacer(modifier = Modifier.height(6.dp))
                GenerateReportProposalCard(
                    proposal = p,
                    language = lang,
                    onConfirm = { viewModel.confirmReportProposal(ctx, message.id, p) },
                    onDiscard = { viewModel.discardReportProposal(message.id, p) }
                )
            }
            // Phase 3 (chat-redesign 2026-06-28): inline chart cards. No
            // Confirm/Discard buttons — purely informational. Renders below
            // the markdown body, above the receipts/edited markers, so the
            // visual answer sits right next to its written explanation.
            chartProposals.forEach { chart ->
                Spacer(modifier = Modifier.height(6.dp))
                com.example.ui.chat.ChartCard(proposal = chart)
            }
            // Receipts left behind by acted-on proposals. A discrete one-line
            // breadcrumb under the bubble: "✓ Habit saved: <descriptor>",
            // "✗ Discarded: <name>", or "· Dismissed: <name>" when the user
            // moved the conversation forward without tapping anything. Stays
            // visible until the message itself is edit-deleted.
            receipts.forEach { receipt ->
                Spacer(modifier = Modifier.height(4.dp))
                ProposalReceiptLine(receipt = receipt, language = lang)
            }
            // Phase 4 v3 (2026-06-13): the hallucination warning card was
            // removed. Earlier versions surfaced an amber card under the
            // bubble when the model claimed to have saved a habit without
            // emitting a [HABIT_PROPOSAL] block, asking the user to "reword"
            // their request. That blamed the user for the model's bad
            // formatting. Now the parser silently salvages malformed markers
            // (HabitProposalParser.tryExtractFromBogus) into real proposals,
            // and the cases that don't salvage are logged-only — never
            // surfaced. The original card composable is still defined below
            // (suppressed unused) in case it's wanted again for a different
            // signal.
            // Phase 3 (#4): "edited" marker. The message's edit timestamp is
            // populated when the user re-submits an edited message via the
            // long-press menu; a NULL value (the default) means it's been
            // sent verbatim and needs no marker.
            if (message.editedTimestamp != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = tr("edited", lang),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            val sdf = remember(message.timestamp) {
                SimpleDateFormat("hh:mm a", Locale.US)
            }
            // 2026-07-05 (Voice Mode): assistant bubbles get a subtle 🔊 icon
            // to the right of their timestamp so the user can tap to have the
            // reply read aloud. User bubbles keep the original standalone
            // timestamp — there's no value in reading your own words back.
            // The 🔊 hides on error bubbles and on empty (mid-stream) text.
            if (!isUser) {
                // speakingId / isSpeakingThis hoisted to the top of the function.
                val canRead = !message.isError && (displayText.isNotBlank() || message.text.isNotBlank())
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = sdf.format(Date(message.timestamp)),
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.weight(1f))
                    if (canRead) {
                        IconButton(
                            onClick = {
                                if (isSpeakingThis) viewModel.stopSpeaking()
                                else viewModel.speakMessage(message)
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isSpeakingThis)
                                    Icons.Filled.VolumeOff
                                else
                                    Icons.Filled.VolumeUp,
                                contentDescription = tr("Read aloud", lang),
                                tint = if (isSpeakingThis)
                                    Color(0xFF3B82F6)
                                else
                                    Color(0xFF64748B),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = sdf.format(Date(message.timestamp)),
                    color = Color(0xFF64748B),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
    // Phase 3 (#4): action sheet shown on long-press of a message bubble.
    // Edit appears only for user messages — assistant replies cannot be
    // edited because the cascade-delete-and-resubmit semantics don't apply.
    if (showActionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showActionSheet = false },
            containerColor = Color(0xFF0F172A)
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                // 2026-06-15: "Select text" is the FIRST entry — it's the
                // power-user-but-rarely-used path. Picking it dismisses the
                // sheet and flips the bubble into selection mode (handled by
                // the parent ChatTab via [onEnterSelection]). Native handles
                // and the system "Copy / Select all" popup then take over,
                // not competing with this sheet anymore.
                ActionRow(
                    icon = Icons.Rounded.Create,
                    label = tr("Select text", lang),
                    onClick = {
                        showActionSheet = false
                        onEnterSelection()
                    }
                )
                if (isUser) {
                    ActionRow(
                        icon = Icons.Filled.Edit,
                        label = tr("Edit", lang),
                        onClick = {
                            showActionSheet = false
                            viewModel.beginEditingMessage(message.id)
                        }
                    )
                }
                ActionRow(
                    icon = Icons.Filled.ContentCopy,
                    label = tr("Copy", lang),
                    onClick = {
                        viewModel.copyMessageToClipboard(ctx, displayText)
                        Toast.makeText(ctx, tr("Copied", lang), Toast.LENGTH_SHORT).show()
                        showActionSheet = false
                    }
                )
                ActionRow(
                    icon = Icons.Rounded.Delete,
                    label = tr("Delete", lang),
                    tint = Color(0xFFEF4444),
                    onClick = {
                        showActionSheet = false
                        showDeleteConfirm = true
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Color(0xFF1E293B),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCBD5E1),
            title = { Text(tr("Delete message", lang)) },
            text = { Text(tr("Delete this message and all later ones?", lang)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMessageAndAfter(message.id)
                    showDeleteConfirm = false
                }) {
                    Text(
                        text = tr("Delete", lang),
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(tr("Cancel", lang), color = Color(0xFF94A3B8))
                }
            }
        )
    }
}

/**
 * One-line breadcrumb that replaces a proposal card after the user confirms,
 * discards, or implicitly dismisses it (by sending another message). Each
 * outcome is colour-coded so the user can scan a long thread and see what
 * still has actionable cards versus what's already resolved:
 *   - CONFIRMED → soft green checkmark "✓ Habit saved: …"
 *   - DISCARDED → soft red cross    "✗ Discarded: …"
 *   - DISMISSED → muted dot         "· Dismissed: …"
 *
 * Stays small (`labelSmall`) and full-opacity so it doesn't compete with the
 * assistant's bubble text but is still legible.
 */
@Composable
private fun ProposalReceiptLine(
    receipt: ChatViewModel.ProposalReceipt,
    language: String
) {
    val verbKey = when (receipt.kind) {
        ChatViewModel.ProposalKind.HABIT -> when (receipt.outcome) {
            ChatViewModel.ProposalOutcome.CONFIRMED -> "Habit saved"
            ChatViewModel.ProposalOutcome.DISCARDED -> "Habit discarded"
            ChatViewModel.ProposalOutcome.DISMISSED -> "Habit dismissed"
        }
        ChatViewModel.ProposalKind.MEAL -> when (receipt.outcome) {
            ChatViewModel.ProposalOutcome.CONFIRMED -> "Meal logged"
            ChatViewModel.ProposalOutcome.DISCARDED -> "Meal discarded"
            ChatViewModel.ProposalOutcome.DISMISSED -> "Meal dismissed"
        }
        ChatViewModel.ProposalKind.ACTIVITY -> when (receipt.outcome) {
            ChatViewModel.ProposalOutcome.CONFIRMED -> "Activity logged"
            ChatViewModel.ProposalOutcome.DISCARDED -> "Activity discarded"
            ChatViewModel.ProposalOutcome.DISMISSED -> "Activity dismissed"
        }
        ChatViewModel.ProposalKind.INSIGHT -> when (receipt.outcome) {
            ChatViewModel.ProposalOutcome.CONFIRMED -> "Insight saved"
            ChatViewModel.ProposalOutcome.DISCARDED -> "Insight discarded"
            ChatViewModel.ProposalOutcome.DISMISSED -> "Insight dismissed"
        }
        ChatViewModel.ProposalKind.REPORT -> when (receipt.outcome) {
            ChatViewModel.ProposalOutcome.CONFIRMED -> "Report exported"
            ChatViewModel.ProposalOutcome.DISCARDED -> "Report discarded"
            ChatViewModel.ProposalOutcome.DISMISSED -> "Report dismissed"
        }
    }
    val (glyph, color) = when (receipt.outcome) {
        ChatViewModel.ProposalOutcome.CONFIRMED -> "✓" to Color(0xFF22C55E)  // green-500
        ChatViewModel.ProposalOutcome.DISCARDED -> "✗" to Color(0xFFEF4444)  // red-500
        ChatViewModel.ProposalOutcome.DISMISSED -> "·" to Color(0xFF94A3B8)  // slate-400
    }
    Text(
        text = "$glyph ${tr(verbKey, language)}: ${receipt.label}",
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun HabitProposalCard(
    proposal: com.example.data.remote.HabitProposal,
    language: String,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = tr("Memorize this habit?", language),
                color = Color(0xFF38BDF8),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = proposal.descriptor,
                color = Color.White,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${tr("Trigger words", language)}: ${proposal.trigger}" +
                    (proposal.mealCategoryHint?.let { " · ${tr(it.displayKey, language)}" } ?: ""),
                color = Color(0xFF94A3B8),
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConfirm) {
                    Text(tr("Confirm", language), color = AccentSoft, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDiscard) {
                    Text(tr("Discard", language), color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

/**
 * Phase 3 (#1b). Inline amber warning card shown beneath an assistant
 * message when the LLM appears to have claimed it persisted a habit
 * without emitting a [HABIT_PROPOSAL] block. Stylistically it mirrors
 * [HabitProposalCard] (same shape, padding, button geometry) so it
 * docks cleanly alongside the other proposal cards on the same bubble,
 * but uses an amber palette to differentiate "this is a warning, not
 * an action" from the cyan/blue-tinted "this is a proposal you can
 * confirm" cards.
 */
@Suppress("unused") // Phase 4 v3: render path removed; composable retained for potential reuse.
@Composable
private fun HabitHallucinationWarningCard(
    language: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF422006)),
        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tr("AI may have claimed it saved a habit without doing so", language),
                    color = Color(0xFFFBBF24),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = tr("Reword your request, or use the + button on the Habits tab", language),
                color = Color(0xFFFDE68A),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = tr("Dismiss", language),
                        color = Color(0xFFFBBF24),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun LogMealProposalCard(
    item: ChatViewModel.PendingMealProposal,
    language: String,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit
) {
    val p = item.proposal
    val dup = item.duplicateOf
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(1.dp, AccentSoft.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = tr("Add this meal to today's history?", language),
                color = AccentSoft,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = p.name,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${p.calories} kcal · P ${formatGrams(p.protein)} · C ${formatGrams(p.carbs)} · F ${formatGrams(p.fat)}" +
                    (p.mealCategory?.let { " · ${tr(it.displayKey, language)}" } ?: ""),
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
            if (dup != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFBBF24).copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, Color(0xFFFBBF24).copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "${tr("Already logged", language)} \"${dup.name}\" — ${tr("Add anyway?", language)}",
                        color = Color(0xFFFBBF24),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConfirm) {
                    Text(
                        text = if (dup != null) tr("Log as duplicate", language) else tr("Add to history", language),
                        color = AccentSoft,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(onClick = onDiscard) {
                    Text(tr("Discard", language), color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

@Composable
private fun LogActivityProposalCard(
    proposal: com.example.data.remote.LogActivityProposal,
    language: String,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = tr("Add this activity to history?", language),
                color = Color(0xFFF59E0B),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = proposal.name,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            // Subtitle prefers the fine-grained sourceLabel ("Pilates",
            // "Weight machine") over the coarse bucket enum name — matches
            // the fidelity rule used everywhere else since ShWorkout.displayLabel.
            val modalityLabel = proposal.sourceLabel?.takeIf { it.isNotBlank() }
                ?: proposal.activityTypeName?.takeIf { it.isNotBlank() }
            Text(
                text = "${proposal.durationMinutes} min · ${proposal.caloriesBurned} kcal" +
                    (modalityLabel?.let { " · $it" } ?: ""),
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConfirm) {
                    Text(tr("Add to history", language), color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDiscard) {
                    Text(tr("Discard", language), color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

private fun formatGrams(g: Double): String {
    val rounded = (g * 10).toInt() / 10.0
    return if (rounded % 1.0 == 0.0) "${rounded.toInt()}g" else "${rounded}g"
}

/** Rounds a gram value to at most 1 decimal place, dropping a trailing ".0"
 *  (8.0 → "8", 5.2 → "5.2", 32.79 → "32.8"). Used to seed the editable macro
 *  fields so the user never sees "32.790000004" or a needless "8.0". */
internal fun roundedGramsValue(g: Double): String {
    val rounded = kotlin.math.round(g * 10) / 10.0
    return if (rounded % 1.0 == 0.0) "${rounded.toInt()}" else "$rounded"
}

@Composable
private fun InsightProposalCard(
    proposal: com.example.data.remote.InsightProposal,
    language: String,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = tr("Save this fact about you?", language),
                color = Color(0xFFA855F7),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = proposal.text,
                color = Color.White,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${proposal.category} · ${(proposal.confidence * 100).toInt()}% · ${proposal.source}",
                color = Color(0xFF94A3B8),
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConfirm) {
                    Text(tr("Save fact", language), color = Color(0xFFA855F7), fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDiscard) {
                    Text(tr("Discard", language), color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

/**
 * Phase G (reformulado): inline card asking the user to confirm the
 * generation of an ad-hoc report into the linked Drive folder. Tailwind cyan
 * accent so it doesn't visually collide with the other proposal colours
 * (green = meal, orange = activity, purple = insight, blue = habit).
 */
@Composable
private fun GenerateReportProposalCard(
    proposal: com.example.data.remote.GenerateReportProposal,
    language: String,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = tr("Generate this report?", language),
                color = Color(0xFF06B6D4),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            val scopeLabel = if (proposal.scope.isBlank()) {
                "${proposal.fromDate} → ${proposal.toDate}"
            } else {
                "${proposal.scope} (${proposal.fromDate} → ${proposal.toDate})"
            }
            Text(
                text = scopeLabel,
                color = Color.White,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = proposal.filenameStem.ifBlank { "relatorio_${proposal.fromDate}_${proposal.toDate}" } + ".xlsx",
                color = Color(0xFF94A3B8),
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConfirm) {
                    Text(tr("Generate", language), color = Color(0xFF06B6D4), fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDiscard) {
                    Text(tr("Discard", language), color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

/**
 * Live streaming assistant bubble — REMOVED 2026-06-18.
 *
 * The cumulative-reply overlay that grew chunk-by-chunk from
 * `GeminiStreamingClient` was retired: live feedback during a turn is now
 * carried entirely by `TypingIndicatorBubble`, and the final assistant
 * message lands in the LazyColumn in one go when the orchestrator emits
 * `HandleEvent.Done`. The streaming pipeline (parser, salvage, persistence)
 * still runs internally — only the on-screen consumer is gone.
 */

@Composable
fun StreamingAssistantBubble(
    text: String,
    /** 2026-06-29 — when non-null, renders a small X icon in the bubble's
     *  top-right corner. Wired to `viewModel.cancelSendJob()` so Hugo can
     *  abort an in-flight reply that's clearly going off the rails. The
     *  previous build only showed cancel on the TypingIndicatorBubble; once
     *  bytes started arriving the cancel control vanished, even though the
     *  underlying coroutine could still be cancelled. */
    onCancel: (() -> Unit)? = null,
) {
    // Build 2 / Fase 1 (2026-06-27): live LLM response rendered chunk by
    // chunk inside the LazyColumn. Lives at index 0 (top of the
    // reverseLayout list) so the user always sees the START of the
    // assistant's answer — the bubble grows downward off-screen, and
    // the caller pins the LazyColumn to keep the top of THIS item
    // anchored just below the screen header. See the top-anchor
    // scrolling block in ChatScreen() — re-anchor to the BOTTOM of the
    // growing item (LazyColumn's default in reverseLayout=true) would
    // hide the start of the response, which Hugo flagged as the worst
    // possible UX. Long-press / proposal cards are intentionally not
    // wired here: the turn is mid-flight, the message is not yet in
    // the Room timeline, and any user action against a partial reply
    // would race against the orchestrator's persist step.
    val isBlank = text.isBlank()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Card(
                // Build 2 v6 (2026-06-28): animateContentSize smooths
                // the height transition as new chunks land. Without it,
                // each chunk snaps the card to its new height — visible
                // as a hard "step" at every Flash burst. With it, the
                // card morphs over ~150ms which reads as natural
                // typing flow. Default animationSpec (spring) is fine.
                modifier = Modifier.animateContentSize(),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 16.dp
                ),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Mid-stream markdown is often broken (an unclosed `**`,
                    // a half-finished list bullet, a code fence with no
                    // closing back-ticks). The halilibo commonmark parser
                    // copes with that gracefully — it renders what it can
                    // and ignores the trailing junk. The CompositionLocal
                    // + explicit RichTextStyle below mirrors the final
                    // persisted bubble's setup (see MessageBubbleItem
                    // around line ~7486): without them, the markdown
                    // renders in BLACK over the dark card background and
                    // the text is invisible — Hugo flagged this on the
                    // first build of Fase 1.
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalContentColor provides Color.White
                    ) {
                        com.halilibo.richtext.ui.material3.RichText(
                            style = com.halilibo.richtext.ui.RichTextStyle(
                                stringStyle = com.halilibo.richtext.ui.string.RichTextStringStyle(
                                    boldStyle = androidx.compose.ui.text.SpanStyle(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    ),
                                    italicStyle = androidx.compose.ui.text.SpanStyle(
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                        color = Color.White
                                    ),
                                    codeStyle = androidx.compose.ui.text.SpanStyle(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        background = Color(0xFF0F172A),
                                        color = Color(0xFFE2E8F0)
                                    ),
                                    linkStyle = androidx.compose.ui.text.TextLinkStyles(
                                        style = androidx.compose.ui.text.SpanStyle(
                                            color = AccentSoft,
                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                                        )
                                    )
                                ),
                                codeBlockStyle = com.halilibo.richtext.ui.CodeBlockStyle(
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = Color(0xFFE2E8F0),
                                        fontSize = 13.sp
                                    ),
                                    modifier = Modifier
                                        .background(
                                            Color(0xFF0F172A),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(8.dp)
                                )
                            )
                        ) {
                            Markdown(content = if (isBlank) " " else text)
                        }
                    }
                    // Blinking cursor — purely cosmetic confirmation that
                    // bytes are still arriving. Disappears the instant the
                    // streaming bubble is replaced by the persisted one.
                    val infinite = rememberInfiniteTransition(label = "streaming_cursor")
                    val cursorAlpha by infinite.animateFloat(
                        initialValue = 0.15f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(500, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "streaming_cursor_alpha"
                    )
                    // 2026-06-29 — cursor + cancel button in a single row so
                    // the user keeps the abort affordance for the entire
                    // duration of the reply (previously cancel vanished the
                    // moment streaming started).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "▋",
                            color = AccentSoft.copy(alpha = cursorAlpha),
                            fontSize = 14.sp
                        )
                        if (onCancel != null) {
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Cancel",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TypingIndicatorBubble(
    phaseText: String? = null,
    onCancel: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Card(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val infiniteTransition = rememberInfiniteTransition()
                val alphas = listOf(
                    infiniteTransition.animateFloat(
                        initialValue = 0.2f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = 0, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        )
                    ).value,
                    infiniteTransition.animateFloat(
                        initialValue = 0.2f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = 200, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        )
                    ).value,
                    infiniteTransition.animateFloat(
                        initialValue = 0.2f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = 400, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        )
                    ).value
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    alphas.forEach { alphaVal ->
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(AccentSoft.copy(alpha = alphaVal))
                        )
                    }
                }

                if (!phaseText.isNullOrBlank()) {
                    Text(
                        text = phaseText,
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                if (onCancel != null) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cancel",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatInputBar(
    inputText: String,
    onTextChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    isTyping: Boolean,
    attachedImageBase64: String?,
    onAttachImageClicked: () -> Unit,
    onRemoveAttachedImage: () -> Unit,
    lang: String,
    /** Phase C: list of staged disk paths. When non-empty, this overrides the
     *  legacy [attachedImageBase64] preview row. */
    attachedImagePaths: List<String> = emptyList(),
    onRemoveAttachedImageAt: (Int) -> Unit = {},
    /** Phase 3 (#4): when true, the bar shows an "Editing message" header,
     *  swaps the Send icon for a check, and routes click to [onSendClicked]
     *  which the caller binds to [ChatViewModel.confirmEditAndResubmit]
     *  instead of the regular send path. The text field is also pre-loaded
     *  with the original message text by the VM, so [inputText] flows in
     *  through the same plumbing. */
    isEditing: Boolean = false,
    onCancelEdit: () -> Unit = {},
    /** 2026-07-05: hands-free Voice Mode. When [voiceMode] is not Idle the
     *  mic button paints in accent + a pulsing state marker. Tapping the
     *  mic calls [onToggleVoiceMode] which the caller wires to
     *  [ChatViewModel.toggleVoiceMode]. Null = feature disabled (headless
     *  tests / older callers). */
    voiceMode: VoiceMode = VoiceMode.Idle,
    onToggleVoiceMode: () -> Unit = {},
    /** Vestigial (Voice Mode v3, 2026-07-05): the per-bubble 🔊 lives in
     *  MessageBubbleItem now, not in the input bar. These params are kept
     *  as no-op defaults so the two call sites don't need editing on the
     *  swap back. Remove them once the call sites are trimmed. */
    @Suppress("UNUSED_PARAMETER") speakingMessageId: Long? = null,
    @Suppress("UNUSED_PARAMETER") onReadLastAssistant: () -> Unit = {}
) {
    val controller = LocalSoftwareKeyboardController.current

    Surface(
        color = Color(0xFF0F172A),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Phase 3 (#4): editing-mode header. Sits above the attachments
            // row so the user always sees it whether or not the message
            // they're editing had images attached.
            if (isEditing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        tint = AccentSoft,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = tr("Editing message", lang),
                        color = AccentSoft,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onCancelEdit,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = tr("Cancel", lang),
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            // Attached image preview area
            // Phase C: prefer the multi-image path list. If empty, fall back
            // to the legacy single-base64 preview so other call sites stay
            // working until they're migrated.
            if (attachedImagePaths.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(attachedImagePaths.size) { index ->
                        val path = attachedImagePaths[index]
                        val pathBitmap = remember(path) {
                            try {
                                val uri = android.net.Uri.parse(path)
                                val p = uri.path
                                if (p != null) {
                                    // Same legacy-content-URI fallback as the
                                    // message-bubble thumbnail: if the parsed
                                    // path doesn't exist on disk, retry with
                                    // the filesDir prefix so legacy
                                    // `content://com.example.fileprovider/...`
                                    // entries still render. Decoder is
                                    // EXIF-aware to keep landscape photos
                                    // landscape.
                                    ChatImageStore.decodeBitmapWithOrientation(p)
                                        ?: ChatImageStore.decodeBitmapWithOrientation(
                                            "/data/data/${com.example.BuildConfig.APPLICATION_ID}/files$p"
                                        )
                                } else null
                            } catch (e: Exception) { null }
                        }
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                        ) {
                            if (pathBitmap != null) {
                                Image(
                                    bitmap = pathBitmap.asImageBitmap(),
                                    contentDescription = tr("Attached thumbnail", lang),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF1E293B))
                                )
                            }
                            IconButton(
                                onClick = { onRemoveAttachedImageAt(index) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFDC2626).copy(alpha = 0.85f))
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = tr("Remove image", lang),
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
                Divider(color = Color(0xFF1E293B))
            } else if (attachedImageBase64 != null) {
                val bitmap = remember(attachedImageBase64) { attachedImageBase64.toBitmap() }
                if (bitmap != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = tr("Attached thumbnail", lang),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        Text(
                            text = tr("Meal image attached to request", lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = onRemoveAttachedImage,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFDC2626).copy(alpha = 0.2f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = tr("Remove image", lang),
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Divider(color = Color(0xFF1E293B))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                // Phase 3 (#5): anchor the attach button + send button to
                // the bottom of the input row so they don't drift upwards
                // as the multi-line OutlinedTextField grows from 1 → 5
                // lines. CenterVertically would have looked floaty.
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 2026-07-05 (Voice Mode v3): mic pill sits directly above
                // the camera pill, forming a vertical stack to the left of
                // the text field. The 🔊 per-bubble button stayed in the
                // bubble footer (see MessageBubbleItem) — Hugo prefers the
                // mic in this stacked position because it's less crowded
                // than the pre-v2 layout that had three pills in one row.
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Mic pill — hands-free Voice Mode toggle. Same 32dp
                    // circle as the 🔊 slot it replaced. Visual state
                    // tracks the VoiceMode state machine.
                    run {
                        val active = voiceMode != VoiceMode.Idle
                        // Listening = mic genuinely live and capturing. We give
                        // it a distinct vivid fill so the user knows *this* is
                        // the moment to speak, versus Thinking (reply pending)
                        // or Idle (tap to start). When the recognizer dies on
                        // silence the VM drops back to Idle, so the pill
                        // de-highlights automatically — no more "lit but dead"
                        // mic that the user talks into for nothing.
                        val listening = voiceMode == VoiceMode.Listening
                        val icon = when (voiceMode) {
                            VoiceMode.Idle,
                            VoiceMode.Listening,
                            VoiceMode.Thinking -> Icons.Filled.Mic
                            VoiceMode.Speaking -> Icons.Filled.MicOff
                        }
                        val fill = when {
                            listening -> Color(0xFF22C55E) // green — mic is live
                            active -> AccentPrimary          // Thinking / Speaking
                            else -> Color(0xFF1E293B)        // Idle
                        }
                        IconButton(
                            onClick = onToggleVoiceMode,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(fill)
                                .border(
                                    1.dp,
                                    if (active) fill else Color(0xFF334155),
                                    CircleShape
                                )
                                .testTag("chat_voice_mode_button")
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = tr("Voice mode", lang),
                                tint = if (active) Color.White else AccentSoft,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onAttachImageClicked,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF334155), CircleShape)
                            .testTag("chat_attach_image_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhotoCamera,
                            contentDescription = tr("Attach photo", lang),
                            tint = AccentSoft,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = onTextChanged,
                    placeholder = {
                        Text(
                            text = if (isEditing) {
                                tr("Edit your message...", lang)
                            } else {
                                tr("Ask about your diet/nutrition...", lang)
                            },
                            color = Color(0xFF64748B),
                            fontSize = 14.sp
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_text_field"),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentSoft,
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedContainerColor = Color(0xFF0B0F19),
                        unfocusedContainerColor = Color(0xFF0B0F19)
                    ),
                    // Phase 3 (#5): multi-line input. Enter inserts a
                    // newline (ImeAction.Default lets the IME draw a
                    // newline glyph instead of Send). The text field
                    // grows from 1 → 5 lines, then scrolls internally.
                    minLines = 1,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default
                    )
                )

                // Phase 3 (#4 / #5): in edit mode the gating ignores
                // [attachedImageBase64] (the original images re-attach
                // internally on resubmit) and the icon morphs into a
                // checkmark so the user reads "save" rather than "send".
                val canSubmit = if (isEditing) {
                    inputText.isNotBlank() && !isTyping
                } else {
                    (inputText.isNotBlank() || attachedImageBase64 != null) && !isTyping
                }
                IconButton(
                    onClick = {
                        if (canSubmit) {
                            onSendClicked()
                            controller?.hide()
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (canSubmit) AccentPrimary else Color(0xFF334155))
                        .testTag("chat_send_button")
                ) {
                    Icon(
                        imageVector = if (isEditing) Icons.Rounded.Check else Icons.Rounded.Send,
                        contentDescription = if (isEditing) {
                            tr("Save", lang)
                        } else {
                            tr("Send Message", lang)
                        },
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Phase 3 (#6) / Phase 5. Builds the destination JPEG file under
 * `filesDir/<dirName>/`, wraps it in a FileProvider URI matching the
 * `com.example.fileprovider` authority declared in the manifest, hands
 * the prepared (file, uri) pair to [onPrepared] so the caller can
 * remember them across the camera-activity round-trip, then launches
 * the [TakePicture] contract. The camera writes the JPEG straight to
 * disk — no Bitmap intermediate, no JPEG re-encode (lossless).
 *
 * [dirName] selects between the two camera capture pools that have
 * entries in `res/xml/file_paths.xml`:
 *  - `"chat_images"` (default) — for the in-chat camera attach button
 *  - `"food_images"` — for the Activity tab capture flow (Phase 5)
 */
private fun launchCamera(
    context: android.content.Context,
    launcher: androidx.activity.compose.ManagedActivityResultLauncher<android.net.Uri, Boolean>,
    dirName: String = "chat_images",
    onPrepared: (java.io.File, android.net.Uri) -> Unit
) {
    val dir = java.io.File(context.filesDir, dirName).apply { if (!exists()) mkdirs() }
    val prefix = if (dirName == "food_images") "img" else "chat"
    val file = java.io.File(
        dir,
        "${prefix}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.jpg"
    )
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "com.example.fileprovider", file
    )
    onPrepared(file, uri)
    launcher.launch(uri)
}

fun String.toBitmap(): Bitmap? {
    return try {
        val bytes = Base64.decode(this, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }
}

/**
 * Decode a bitmap from [uri] downsampled so that the longer side is ≤ [maxDim]
 * pixels. Uses BitmapFactory.Options.inSampleSize (power-of-2 subsampling done
 * during decode, so a 4000×3000 JPEG never expands to a 48 MB ARGB_8888 buffer
 * — it lands at ≤ ~1 MB for maxDim=512). Necessary because camera photos on
 * the S25 are up to 12+ MP and the Java heap on that device is capped at ~268
 * MB, easily blown by a couple of full-resolution previews in the composition.
 *
 * Returns null on any I/O or decode failure (broken symlink, deleted file,
 * invalid content:// URI, etc.). Callers render a "Picture not available"
 * placeholder in that case.
 */
/**
 * Decode `uri` into a bitmap whose longer side is at most `maxDim` px,
 * with EXIF orientation applied automatically.
 *
 * History of pain (all three iterations were mine, same day):
 *   1. `ImageDecoder.decodeBitmap(source)` — auto-rotated by EXIF but
 *      decoded at full resolution, so 12–50 MP camera JPEGs allocated
 *      48–200 MB ARGB_8888 bitmaps and OOM'd the Galaxy S25 heap once
 *      several previews landed in the tree at once (see [[bitmap-oom-fix-2026-07-02]]).
 *   2. Two-pass `BitmapFactory.decodeStream + inSampleSize` — fixed OOM
 *      but silently dropped EXIF, so Pixel/Samsung JPEGs with
 *      Orientation=6 rendered sideways (see [[bitmap-decode-loses-exif-rotation-2026-07-02]]).
 *   3. Same + `ExifInterface.rotationDegrees` + `Matrix.postRotate` —
 *      handled ROTATE_{90,180,270} but ignored the 4 FLIP/TRANSPOSE
 *      cases, and re-implemented in ~60 lines what `ImageDecoder`
 *      already ships.
 *
 * The right shape is #1's simplicity + #2's downsampling. `ImageDecoder`
 * exposes exactly that via `setTargetSampleSize` on the header-decoded
 * listener: the header pass hands you `info.size`, you compute a power-of-2
 * sample, the decode itself streams into a downsampled bitmap, and EXIF
 * orientation is applied by default. All in one API call, no second stream,
 * no manual rotate, no OOM.
 *
 * `minSdk = 29` in this project — `ImageDecoder` (API 28) is always available.
 */
private fun decodeDownsampledBitmap(
    context: android.content.Context,
    uri: Uri,
    maxDim: Int
): Bitmap? {
    return try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longSide = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (longSide / sample > maxDim) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
            // Force a software bitmap: hardware bitmaps can't be drawn into
            // an `Image { bitmap = … .asImageBitmap() }` on some Compose paths
            // and would also skip our Crop scaling contract.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // EXIF orientation is honored by default (respectOrientation=true).
            // Explicitly leaving decoder.isMutableRequired = false so Skia can
            // hand back the internal downsampled buffer without copying.
        }
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Throwable) {
        null
    }
}

@Composable
private fun FoodImagePreview(
    imagePath: String?,
    modifier: Modifier,
    language: String = "en",
    isThumbnail: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var bitmap by remember(imagePath) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(imagePath) { mutableStateOf(false) }

    if (!imagePath.isNullOrBlank()) {
        LaunchedEffect(imagePath) {
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val uri = Uri.parse(imagePath)
                    // Downsample to ~512 px max on the longer side. Full-resolution
                    // camera photos (12–50 MP → 48–200 MB in ARGB_8888) blow the
                    // ~268 MB Java heap on the Galaxy S25 when several previews
                    // are simultaneously in the composition tree (Análise tab,
                    // meal history, chat bubbles). Observed: 160 imagens em
                    // files/food_images = 520 MB total → OOM em cadeia.
                    decodeDownsampledBitmap(context, uri, maxDim = 512)
                } catch (t: Throwable) {
                    null
                }
            }
            if (loaded != null) {
                bitmap = loaded
                loadFailed = false
            } else {
                bitmap = null
                loadFailed = true
            }
        }
    } else {
        bitmap = null
        loadFailed = true
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F172A))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null && !loadFailed) {
            // Fill the whole preview box regardless of aspect ratio. Crop keeps
            // the image edge-to-edge (no letterbox gaps) — the box already has a
            // bounded height (heightIn 180–280dp for the big card, size() for
            // thumbnails), so cropping trims overflow rather than distorting.
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = tr("Meal Image", language),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.BrokenImage,
                    contentDescription = tr("Picture not available", language),
                    tint = Color(0xFF94A3B8).copy(alpha = 0.3f),
                    modifier = Modifier.size(if (isThumbnail) 20.dp else 40.dp)
                )
                if (!isThumbnail) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = tr("Picture not available", language),
                        color = Color(0xFF94A3B8).copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Returns the **short** label for a meal category — only used inside the
 * 5-pill row in the meal-edit screen, where horizontal real estate is tight
 * (~65dp/pill at fontSize=11.sp). The full forms ("Pequeno-almoço") get
 * clipped to "Pequeno-al" without this. We keep the long form everywhere
 * else (proposal cards, habit screen, exports) by NOT renaming
 * `MealCategory.displayKey` itself.
 *
 * Falls back to the long form when no `.short` translation exists for the
 * current language (e.g. English never had clipping issues, so we don't
 * register a `.short` mapping there — `tr()`'s silent-fallback returns the
 * key intact, which we detect and replace with the long form).
 */
private fun mealCategoryShortLabel(
    category: com.example.data.local.MealCategory,
    language: String
): String {
    val shortKey = "${category.displayKey}.short"
    val short = tr(shortKey, language)
    return if (short == shortKey) tr(category.displayKey, language) else short
}

@Composable
fun MacroBar(label: String, value: Double, color: Color, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, modifier = Modifier.width(100.dp), color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(Color(0xFF334155), CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(Math.min((value / 100.0).toFloat(), 1.0f))
                    .background(color, CircleShape)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = String.format("%.1f %s", value, unit), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
    }
}

/** Visible breadcrumb showing which user-taught food habits informed this entry's macros.
 *  Resolves IDs against the live habit map; missing/archived ids are silently dropped. */
@Composable
private fun AppliedHabitsChip(
    habitIds: List<Long>?,
    habitsById: Map<Long, com.example.data.local.FoodHabit>,
    language: String,
    compact: Boolean = false
) {
    if (habitIds.isNullOrEmpty()) return
    val resolved = habitIds.mapNotNull { habitsById[it] }
    if (resolved.isEmpty()) return
    val names = resolved.joinToString(", ") { habit ->
        habit.trigger.split(";").firstOrNull()?.trim()?.ifBlank { null }
            ?: habit.descriptor.take(28)
    }
    val labelText = "${tr("Applied habits", language)}: $names"
    Box(
        modifier = Modifier
            .background(Color(0xFF1E3A8A).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = if (compact) 3.dp else 5.dp)
    ) {
        Text(
            text = labelText,
            color = Color(0xFF38BDF8),
            fontSize = if (compact) 10.sp else 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ActivityCaptureSection(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val userProfile by viewModel.userProfile.collectAsState()
    val isAnalyzing by viewModel.isAnalyzingActivity.collectAsState()
    val pendingActivitySession by viewModel.pendingActivitySession.collectAsState()
    val analysisError by viewModel.activityAnalysisError.collectAsState()

    var activityDescription by remember { mutableStateOf("") }
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, it)
                android.graphics.ImageDecoder.decodeBitmap(source)
            } else {
                MediaStore.Images.Media.getBitmap(context.contentResolver, it)
            }
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            
            viewModel.analyzeAndLogActivity(
                base64Image = base64Image,
                promptHint = "Analyzing uploaded screenshot for activity stats",
                onSuccess = {},
                onFailure = { err ->
                    val fallbackEntry = com.example.data.local.ActivityEntry(
                        name = "Uploaded Activity",
                        caloriesBurned = 250,
                        durationMinutes = 45,
                        activityType = com.example.data.local.ActivityType.OTHER,
                        sessionId = "session_${System.currentTimeMillis()}"
                    )
                    viewModel.setPendingActivitySession(listOf(fallbackEntry))
                }
            )
        }
    }

    if (pendingActivitySession.isNotEmpty()) {
        val head = pendingActivitySession.first()
        val isSaving by viewModel.isSavingEntry.collectAsState()
        val saveError by viewModel.saveError.collectAsState()

        var editSummary by remember(head.sessionId, head.timestamp) { mutableStateOf((head.fullSummary ?: "").sanitizeAiNewlines()) }

        // ── Unsaved-changes guard for system back ──
        // Snapshot of the session as the user landed on it. Comparing the whole
        // list catches every kind of edit — summary, timestamp, per-segment kcal
        // / duration / type — without enumerating every field by name.
        val initialSession by remember(head.sessionId, head.timestamp) { mutableStateOf(pendingActivitySession) }
        val isDirty = initialSession != pendingActivitySession
        var showDiscardDialog by remember(head.sessionId, head.timestamp) { mutableStateOf(false) }

        BackHandler(enabled = !isSaving) {
            if (isDirty) {
                showDiscardDialog = true
            } else {
                viewModel.rejectPendingActivitySession()
            }
        }

        if (showDiscardDialog) {
            AlertDialog(
                onDismissRequest = { showDiscardDialog = false },
                containerColor = Color(0xFF0F172A),
                titleContentColor = Color.White,
                textContentColor = Color.White,
                title = { Text(tr("Unsaved changes", userProfile.language)) },
                text = { Text(tr("You have unsaved changes. What would you like to do?", userProfile.language)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDiscardDialog = false
                        viewModel.confirmAndSaveActivitySession()
                    }) {
                        Text(
                            tr("Save", userProfile.language),
                            color = AccentSoft,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            showDiscardDialog = false
                            viewModel.rejectPendingActivitySession()
                        }) {
                            Text(
                                tr("Discard", userProfile.language),
                                color = Color(0xFFEF4444)
                            )
                        }
                        TextButton(onClick = { showDiscardDialog = false }) {
                            Text(
                                tr("Cancel", userProfile.language),
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Inline title removed 2026-06-23 — the screen-level
            // [ScreenHeader] (see Activity tab) already shows the gradient
            // brand-title + AccentSoft subtitle when pendingActivityEntry
            // is non-null, mirroring the Edit Meal Log flow. Duplicating
            // the title here produced two stacked headers and a visual
            // inconsistency the user called out.

            // Summary Field
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editSummary,
                        onValueChange = { newSummary ->
                            editSummary = newSummary
                            // Mark dirty for the streaming-reanalysis gate in
                            // confirmAndSaveActivitySession — without this the
                            // re-stream never fires and the typing bubble
                            // never appears (mirrors the food path's
                            // markSummaryEdited + onValueChange).
                            if (newSummary != (head.fullSummary ?: "")) {
                                viewModel.markActivitySummaryEdited()
                            }
                            viewModel.updatePendingActivitySession { list ->
                                list.map { it.copy(fullSummary = newSummary) }
                            }
                        },
                        label = { Text(tr("Workout Description Summary", userProfile.language)) },
                        modifier = Modifier.fillMaxWidth().testTag("edit_workout_summary_input"),
                        minLines = 2,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3B82F6), unfocusedBorderColor = Color(0xFF334155)
                        )
                    )
                    Text(
                        text = tr("Editing this description triggers AI workout recalibration upon saving.", userProfile.language),
                        fontSize = 11.sp,
                        color = Color(0xFF38BDF8),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }

            // Date Selection Row
            // Styling alinhado com o equivalente em Edit Meal Log
            // (8dp radius + #0F172A bg + 12dp padding) para coerência
            // visual entre os dois flows — Hugo 2026-06-23.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                    .clickable {
                        val calendar = Calendar.getInstance().apply { timeInMillis = head.timestamp }
                        val datePickerDialog = android.app.DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                calendar.set(Calendar.YEAR, year)
                                calendar.set(Calendar.MONTH, month)
                                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                
                                android.app.TimePickerDialog(
                                    context,
                                    { _, hourOfDay, minute ->
                                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                                        calendar.set(Calendar.MINUTE, minute)
                                        viewModel.updatePendingActivityEntryTimestamp(calendar.timeInMillis)
                                    },
                                    calendar.get(Calendar.HOUR_OF_DAY),
                                    calendar.get(Calendar.MINUTE),
                                    true
                                ).show()
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        )
                        datePickerDialog.datePicker.firstDayOfWeek = java.util.Calendar.MONDAY
                        datePickerDialog.show()
                    }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.EditCalendar,
                            contentDescription = tr("Edit Date", userProfile.language),
                            tint = Color(0xFF3B82F6),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tr("Log Date & Time", userProfile.language),
                            color = Color(0xFF94A3B8),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = tr("Choose Date", userProfile.language),
                        color = Color(0xFF3B82F6),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                val sdf = SimpleDateFormat("EEEE, d MMM yyyy  •  HH:mm", localeForLanguage(userProfile.language))
                Text(
                    text = sdf.format(Date(head.timestamp)),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 22.dp)
                )
            }

            // Exercise items list
            pendingActivitySession.forEachIndexed { index, exercise ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${tr("Exercise", userProfile.language)} #${index + 1}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF3B82F6)
                            )
                            if (pendingActivitySession.size > 1) {
                                IconButton(
                                    onClick = {
                                        viewModel.updatePendingActivitySession { it.filterIndexed { idx, _ -> idx != index } }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        // Exercise Name
                        OutlinedTextField(
                            value = exercise.name,
                            onValueChange = { newName ->
                                viewModel.updatePendingActivitySession { list ->
                                    list.mapIndexed { i, item -> if (i == index) item.copy(name = newName) else item }
                                }
                            },
                            label = { Text(tr("Exercise Name", userProfile.language)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF3B82F6), unfocusedBorderColor = Color(0xFF334155)
                            )
                        )

                        // Duration, Calories
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = if (exercise.durationMinutes == 0) "" else exercise.durationMinutes.toString(),
                                onValueChange = { newDur ->
                                    viewModel.updatePendingActivitySession { list ->
                                        list.mapIndexed { i, item -> if (i == index) item.copy(durationMinutes = newDur.toIntOrNull() ?: 0) else item }
                                    }
                                },
                                label = { Text(tr("Duration (min)", userProfile.language)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF3B82F6), unfocusedBorderColor = Color(0xFF334155)
                                )
                            )

                            OutlinedTextField(
                                value = if (exercise.caloriesBurned == 0) "" else exercise.caloriesBurned.toString(),
                                onValueChange = { newCal ->
                                    viewModel.updatePendingActivitySession { list ->
                                        list.mapIndexed { i, item -> if (i == index) item.copy(caloriesBurned = newCal.toIntOrNull() ?: 0) else item }
                                    }
                                },
                                label = { Text(tr("Calories Burned", userProfile.language)) },
                                modifier = Modifier.weight(1.2f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF60A5FA), unfocusedBorderColor = Color(0xFF334155)
                                )
                            )
                        }

                        // Exercise Type Row Selector
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = tr("Activity Type", userProfile.language),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                com.example.data.local.ActivityType.values().forEach { type ->
                                    val isSelected = exercise.activityType == type
                                    val actColor = when (type) {
                                        com.example.data.local.ActivityType.WALKING -> AccentPrimary
                                        com.example.data.local.ActivityType.RUNNING -> Color(0xFF3B82F6)
                                        com.example.data.local.ActivityType.STRENGTH -> Color(0xFFEC4899)
                                        com.example.data.local.ActivityType.CYCLING -> Color(0xFFF59E0B)
                                        com.example.data.local.ActivityType.SWIMMING -> Color(0xFF06B6D4)
                                        com.example.data.local.ActivityType.YOGA -> Color(0xFF8B5CF6)
                                        com.example.data.local.ActivityType.OTHER -> Color(0xFF64748B)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .height(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) actColor.copy(alpha = 0.2f) else Color(0xFF0F172A))
                                            .border(1.dp, if (isSelected) actColor else Color(0xFF334155), RoundedCornerShape(8.dp))
                                            .clickable {
                                                viewModel.updatePendingActivitySession { list ->
                                                    list.mapIndexed { i, item -> if (i == index) item.copy(activityType = type) else item }
                                                }
                                            }
                                            .padding(horizontal = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = tr(type.name.lowercase().replaceFirstChar { it.uppercase() }, userProfile.language),
                                            color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Notes Field
                        OutlinedTextField(
                            value = exercise.notes ?: "",
                            onValueChange = { newNotes ->
                                viewModel.updatePendingActivitySession { list ->
                                    list.mapIndexed { i, item -> if (i == index) item.copy(notes = newNotes) else item }
                                }
                            },
                            label = { Text(tr("Exercise Notes / Reps / Weight", userProfile.language)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF3B82F6), unfocusedBorderColor = Color(0xFF334155)
                            )
                        )
                    }
                }
            }

            // Add Exercise Row Button
            OutlinedButton(
                onClick = {
                    viewModel.updatePendingActivitySession { list ->
                        list + ActivityEntry(
                            name = "New Exercise",
                            durationMinutes = 30,
                            caloriesBurned = 150,
                            timestamp = head.timestamp,
                            sessionId = head.sessionId,
                            fullSummary = head.fullSummary,
                            lastAiAnalyzedSummary = head.lastAiAnalyzedSummary,
                            activityType = com.example.data.local.ActivityType.OTHER,
                            notes = null
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF3B82F6)),
                border = BorderStroke(1.dp, Color(0xFF3B82F6))
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(tr("Add Exercise Segment", userProfile.language), fontWeight = FontWeight.Bold)
            }

            if (saveError != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        saveError!!,
                        color = Color(0xFFFECACA),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Confirm & Save
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 2026-06-18 — re-analysis feedback bubble for activity edits.
                // Mirrors the meal-edit screen: when the user changes the
                // workout summary and taps Confirm, the orchestrator streams
                // a Phase event so this bubble fades in with "A reanalisar
                // o treino…" and a cancel button. Hidden for plain saves.
                run {
                    val phaseKey by viewModel.foodAnalysisPhase.collectAsState()
                    val isReanalyzing = isSaving && phaseKey != null
                    androidx.compose.animation.AnimatedVisibility(visible = isReanalyzing) {
                        TypingIndicatorBubble(
                            phaseText = tr(
                                phaseKey ?: "phase_reanalyzing_activity",
                                userProfile.language
                            ),
                            onCancel = { viewModel.cancelFoodAnalysis() }
                        )
                    }
                }

                Button(
                    onClick = { viewModel.confirmAndSaveActivitySession() },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("accept_analysis_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("Analyzing changes...", userProfile.language), fontWeight = FontWeight.Bold, color = Color.White)
                    } else {
                        Icon(Icons.Rounded.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("Confirm & Log Activity", userProfile.language), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    }
                }

                Button(
                    onClick = { viewModel.rejectPendingActivitySession() },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("reject_analysis_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFFEF4444)),
                    border = BorderStroke(1.dp, Color(0xFF7F1D1D))
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(tr("Close & Discard", userProfile.language), fontWeight = FontWeight.Bold)
                }
            }
        }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // (Removido o título+subtítulo "Activity tracker / Upload fitness..." —
            // duplicava o pill seleccionado por cima.)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 280.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E293B))
                    .border(
                        BorderStroke(2.dp, Brush.linearGradient(listOf(AccentSoft, Color(0xFF60A5FA)))),
                        RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize().padding(24.dp)
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(color = AccentSoft, strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            tr("Analysing activity via AI...", userProfile.language),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            OutlinedTextField(
                                value = activityDescription,
                                onValueChange = { activityDescription = it },
                                placeholder = { Text(tr("Describe your workout...", userProfile.language), color = Color(0xFF94A3B8)) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    cursorColor = AccentSoft, focusedBorderColor = AccentSoft,
                                    unfocusedBorderColor = Color.Gray, focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxSize()
                            )
                            IconButton(
                                onClick = {
                                    if (activityDescription.isNotBlank()) {
                                        val currentDesc = activityDescription
                                        viewModel.analyzeAndLogActivity(
                                            base64Image = null,
                                            promptHint = "Analyzing described activity: $currentDesc",
                                            onSuccess = {
                                                activityDescription = ""
                                            },
                                            onFailure = { err ->
                                                val fallback = createFallbackActivityFromDescription(currentDesc)
                                                viewModel.setPendingActivitySession(listOf(fallback))
                                                activityDescription = ""
                                            }
                                        )
                                    }
                                },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            ) {
                                Icon(Icons.Rounded.Send, contentDescription = "Send", tint = AccentSoft)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(tr("Or select a screenshot below", userProfile.language), fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Button(
                    onClick = { launcher.launch("image/*") },
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = "Upload Screen")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(tr("Upload Screenshot", userProfile.language), fontWeight = FontWeight.Bold)
                }
            }

            if (analysisError != null && !isAnalyzing && pendingActivitySession.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(tr("Analysis Error Details", userProfile.language), color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(analysisError!!, color = Color(0xFFFECACA), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityCaptureTab(viewModel: ChatViewModel) {
    val userProfile by viewModel.userProfile.collectAsState()
    val activeMode by viewModel.activeCaptureMode.collectAsState()
    val pendingFoodEntry by viewModel.pendingFoodEntry.collectAsState()
    // Mirror the meal-edit path so the header morphs the same way when the
    // user enters the activity edit form (Hugo 2026-06-23): both flows
    // should share the gradient brand-title aesthetic of [ScreenHeader].
    val pendingActivityEntry by viewModel.pendingActivityEntry.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 2026-06-18: NO .imePadding() here. The OUTER Scaffold in
            // ChatScreen() now uses contentWindowInsets =
            // systemBars.union(ime), so the IME inset is already applied
            // upstream via .padding(innerPadding) on the pager content
            // Box. Adding .imePadding() here double-consumed the IME and
            // produced a large empty band between this tab's content
            // (e.g. the "Descreva a sua refeição..." TextField) and the
            // top of the keyboard. See [[scaffold-edge-to-edge-ime-cookbook]].
            .verticalScroll(rememberScrollState())
    ) {
        // Uniform top-of-screen header (same gradient + style as the
        // Dashboard). When the user is mid-edit on a meal — typically
        // arrived here by tapping a row in the History tab — the header
        // morphs into "Edit Meal Log" so the screen identifies itself
        // correctly. The duplicate inline title inside FoodCaptureSection
        // is removed below to avoid two stacked titles.
        val isEditingMeal = pendingFoodEntry != null
        val isEditingActivity = pendingActivityEntry != null
        com.example.ui.components.ScreenHeader(
            title = when {
                isEditingMeal ->
                    if (pendingFoodEntry!!.id != 0L) tr("Edit Meal Log", userProfile.language)
                    else tr("Review AI Analysis", userProfile.language)
                isEditingActivity ->
                    if (pendingActivityEntry!!.id != 0L) tr("Edit Activity Session", userProfile.language)
                    else tr("Review AI Session Analysis", userProfile.language)
                else -> tr("Activity", userProfile.language)
            },
            subtitle = when {
                isEditingMeal ->
                    tr("Fine-tune and verify the specific nutritional properties", userProfile.language)
                isEditingActivity ->
                    tr("Customize individually tracked exercise session segments", userProfile.language)
                else -> tr("Capture meals and workouts", userProfile.language)
            }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Modern segment selector — pill row uniforme, com ícone à esquerda do texto.
            // Usa SegmentedPillBox low-level porque cada pill tem ícone+texto (não cabe
            // no helper de só-texto). Activity já não precisa duma cor distinta — a
            // selecção é comunicada pelo glow violeta do pill activo vs outline cinza
            // do outro.
            //
            // Hide the segment pills while the user is mid-edit on a food entry —
            // tapping them would silently switch context and lose the in-progress
            // edit. The "Edit Meal Log" header rendered by FoodCaptureSection is
            // self-explanatory; the pills are noise there.
            val isFoodSelected = activeMode == CaptureMode.FOOD
            val isActivitySelected = activeMode == CaptureMode.ACTIVITY
            if (pendingFoodEntry == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
            com.example.ui.components.SegmentedPillBox(
                isSelected = isFoodSelected,
                onClick = { viewModel.setCaptureMode(CaptureMode.FOOD) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("segment_toggle_food")
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Restaurant,
                        contentDescription = null,
                        tint = if (isFoodSelected) Color.White else Color(0xFF94A3B8)
                    )
                    Text(
                        text = tr("Food tracker", userProfile.language),
                        fontWeight = FontWeight.Bold,
                        color = if (isFoodSelected) Color.White else Color(0xFF94A3B8),
                        fontSize = 14.sp
                    )
                }
            }

            com.example.ui.components.SegmentedPillBox(
                isSelected = isActivitySelected,
                onClick = { viewModel.setCaptureMode(CaptureMode.ACTIVITY) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("segment_toggle_activity")
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DirectionsRun,
                        contentDescription = null,
                        tint = if (isActivitySelected) Color.White else Color(0xFF94A3B8)
                    )
                    Text(
                        text = tr("Activity tracker", userProfile.language),
                        fontWeight = FontWeight.Bold,
                        color = if (isActivitySelected) Color.White else Color(0xFF94A3B8),
                        fontSize = 14.sp
                    )
                }
            }
        }
        } // end if (pendingFoodEntry == null) — pills hidden in edit mode

        // Spacer between the segment pills and the chosen layout — only
        // meaningful when the pills are visible. In edit mode (pendingFoodEntry
        // != null) the pills are skipped above, so this 4dp + the surrounding
        // 16dp spacedBy steps are dead vertical space between the
        // ScreenHeader and the Meal Name field. 2026-06-19: see plan
        // i-neew-you-to-dazzling-moon.md.
        if (pendingFoodEntry == null) {
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Render chosen layout
        if (activeMode == CaptureMode.FOOD) {
            FoodCaptureSection(viewModel = viewModel)
        } else {
            ActivityCaptureSection(viewModel = viewModel)
        }
        } // end inner padding(16) Column
    }
}

@Composable
fun ActivityPieChart(
    activities: List<ActivityEntry>,
    modifier: Modifier = Modifier
) {
    if (activities.isEmpty()) {
        Box(
            modifier = modifier.background(Color(0xFF1E293B), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No logs yet",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B)
            )
        }
        return
    }

    val grouped = remember(activities) {
        val total = activities.sumOf { it.caloriesBurned }.toFloat()
        if (total == 0f) {
            val count = activities.size.toFloat()
            activities.groupBy { it.activityType }
                .map { (type, list) -> type to (list.size / count) }
        } else {
            activities.groupBy { it.activityType }
                .map { (type, list) -> type to (list.sumOf { it.caloriesBurned } / total) }
        }
    }

    androidx.compose.foundation.Canvas(modifier = modifier) {
        var startAngle = -90f
        grouped.forEach { (type, pct) ->
            val sweepAngle = pct * 360f
            drawArc(
                color = Color(type.colorHex),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 10.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
            startAngle += sweepAngle
        }
    }
}

// Force rebuild to trigger preview reload

/**
 * Compose o título personalizado do Dashboard a partir do nome no perfil.
 * - Pega no primeiro token (split por whitespace).
 * - Capitaliza a primeira letra.
 * - Se o nome estiver vazio (caso defensivo: o `UserProfile` já força "Humano"
 *   como default e o save dialog faz `.ifBlank { "Humano" }`), devolve só "Nutri".
 *
 * Exemplos: "Hugo Rodrigues" → "NutriHugo"; "joão" → "NutriJoão"; "" → "Nutri".
 */
private fun nutriBrand(profileName: String): String {
    val first = profileName.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    if (first.isEmpty()) return "Nutri"
    val capitalized = first.replaceFirstChar { it.uppercase() }
    return "Nutri$capitalized"
}
