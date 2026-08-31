package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Off-screen Compose-tree-to-Bitmap renderer for "Share Image".
 *
 * The Analytics tab needs a shared PNG that mirrors the current state — title,
 * date range, charts, tables, Samsung Health card — but WITHOUT the
 * Daily/Weekly/Monthly picker (it's a control, useless in a static image).
 * Rather than surgically excluding a node from the live captured tree (which
 * forced an awkward layout reorder), we render a SECOND Compose tree off-screen
 * with the layout we want. The on-screen tab is never touched.
 *
 * Implementation notes:
 *  - We host a transient [ComposeView] as a child of the Activity's window
 *    decor view, sized at our chosen width and zero opacity, so Compose has a
 *    real `LifecycleOwner` / `ViewModelStoreOwner` / `SavedStateRegistryOwner`
 *    inherited from the Activity. Without these the Compose runtime won't
 *    drive the composition (effects, `collectAsState`, etc.).
 *  - The view is laid out with `MeasureSpec.UNSPECIFIED` for height so the
 *    composition reports its full intrinsic height — same trick the previous
 *    GraphicsLayer-based capture used.
 *  - We wait two frames after attach: one to let composition run, a second to
 *    let measurement settle (`collectAsState` reads inside the tree may bump a
 *    second time after the first commit).
 *  - We then `view.draw(canvas)` into a `Bitmap.Config.ARGB_8888` buffer the
 *    same size as the laid-out view, with a pre-painted dark navy background
 *    so cards drawn with translucent fills look identical to the on-screen
 *    appearance.
 *  - The `ComposeView` is detached and disposed before we return.
 */
suspend fun renderComposableToBitmap(
    context: Context,
    widthPx: Int,
    content: @Composable () -> Unit
): ImageBitmap = withContext(Dispatchers.Main) {
    val activity = context as? ComponentActivity
        ?: error("renderComposableToBitmap requires a ComponentActivity context (got ${context.javaClass.name})")

    val composeView = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent(content)
    }

    // Compose needs view-tree owners to drive the composition. The Activity
    // already implements all three; copy them onto our throwaway view.
    composeView.setViewTreeLifecycleOwner(activity)
    composeView.setViewTreeViewModelStoreOwner(activity)
    composeView.setViewTreeSavedStateRegistryOwner(activity)

    // Attach to the decor view so the runtime considers us "attached" — this
    // is what actually starts the composition. Use 1×1 layout params with
    // alpha=0 to avoid any flicker; we replace the size manually below.
    val decor = activity.window.decorView as ViewGroup
    val lp = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    composeView.alpha = 0f
    composeView.layoutParams = lp
    decor.addView(composeView)

    try {
        // Force measure → layout → first frame.
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        composeView.measure(widthSpec, heightSpec)
        composeView.layout(0, 0, composeView.measuredWidth, composeView.measuredHeight)

        // Two frames: composition + a second pass for state subscriptions
        // (collectAsState inside the tree may bump on its second commit).
        awaitFrame()
        awaitFrame()

        // Re-measure in case the second frame changed intrinsic size.
        composeView.measure(widthSpec, heightSpec)
        composeView.layout(0, 0, composeView.measuredWidth, composeView.measuredHeight)

        val w = composeView.measuredWidth.coerceAtLeast(1)
        val h = composeView.measuredHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        // Pre-paint navy background so translucent cards look right.
        canvas.drawColor(android.graphics.Color.parseColor("#FF0F172A"))
        composeView.draw(canvas)

        bitmap.asImageBitmap()
    } finally {
        decor.removeView(composeView)
        composeView.disposeComposition()
    }
}

/**
 * Render a single chart composable off-screen and return a **base64 PNG**
 * string (no `data:` prefix — the HTML builder adds it). Used by the
 * "Partilhar report" (detailed HTML) share to embed the Compose-Canvas charts
 * as `<img src="data:image/png;base64,…">`.
 *
 * The chart is wrapped in a fixed-height, navy-backed [Box] BEFORE handing it
 * to [renderComposableToBitmap]. This is mandatory, not cosmetic:
 * [renderComposableToBitmap] lays out with height `UNSPECIFIED`, and charts
 * like `StackedDayBarChart` / `MultilineChart` use `Modifier.fillMaxSize` on
 * their inner Canvas — without a bounded height they collapse to 0px and the
 * image comes out blank. The navy background matches the app so the exported
 * chart looks identical to on-screen.
 *
 * PNG (lossless, quality ignored) is used deliberately over JPEG: thin
 * gridlines and axis text on these charts pick up visible artifacts under
 * JPEG. Sizes stay small because each chart is bounded (130–300dp tall).
 */
suspend fun renderChartToBase64Png(
    context: Context,
    widthPx: Int,
    heightDp: Int,
    content: @Composable () -> Unit
): String = withContext(Dispatchers.Main) {
    val img = renderComposableToBitmap(context, widthPx) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .background(Color(0xFF0F172A))
        ) {
            content()
        }
    }
    val out = ByteArrayOutputStream()
    img.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}
