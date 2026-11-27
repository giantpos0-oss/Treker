package com.willwalsh.treker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import com.willwalsh.treker.TrekerApp
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun BrowseScreen() {
    val app = LocalContext.current.applicationContext as TrekerApp
    val walker = app.walker
    val scope = rememberCoroutineScope()

    // Opening this tab starts browsing from where you are, or from a place you chose.
    val from = AppNav.browseFrom.value
    LaunchedEffect(from) {
        if (from != null) {
            AppNav.browseFrom.value = null
            walker.startAt(from.pos, from.name)
        } else if (walker.position.value == null) {
            val here = app.location.fix.value?.pos
            if (here == null) app.speaker.say("Waiting for GPS to start map browsing.", true)
            else walker.startAt(here)
        }
    }

    fun act(f: () -> Unit) {
        f()
        if (walker.needsReload) scope.launch { walker.reloadAround() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        BigButton("Start from my location") {
            val here = app.location.fix.value?.pos
            if (here == null) app.speaker.say("No GPS position yet.", true)
            else scope.launch { walker.startAt(here) }
        }
        Text(walker.description.value, fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp))
        BigButton("Report a problem here") {
            val p = walker.position.value
            if (p == null) app.speaker.say("Map browsing hasn't started.", true)
            else AppNav.report.value = ReportRequest(p, heard = walker.description.value)
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                SwipePad(ctx,
                    up = { act { walker.forward() } },
                    down = { act { walker.turnAround() } },
                    left = { act { walker.turn(left = true) } },
                    right = { act { walker.turn(left = false) } },
                    tap = { walker.describe() },
                    report = {
                        walker.position.value?.let { AppNav.report.value = ReportRequest(it, heard = walker.description.value) }
                    },
                )
            },
        )
    }
}

/**
 * The swipe area for map browsing. On Android 14 and later it asks the screen reader to pass
 * touches straight through, so swipes reach BkindExplorer instead of moving screen reader focus.
 * The same moves are also screen reader actions on the area, as a backup.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SwipePad(
    context: Context,
    private val up: () -> Unit, private val down: () -> Unit,
    private val left: () -> Unit, private val right: () -> Unit,
    private val tap: () -> Unit,
    private val report: () -> Unit,
) : View(context) {

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { tap(); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x; val dy = e2.y - start.y
            if (abs(dx) < 60 && abs(dy) < 60) return false
            if (abs(dy) > abs(dx)) { if (dy < 0) up() else down() } else { if (dx < 0) left() else right() }
            return true
        }
    })

    init {
        contentDescription = "Map browsing area. Swipe up to move forward to the next intersection, " +
            "down to turn around, left or right to turn onto a side street. Tap to hear where you are."
        isFocusable = true
        setBackgroundColor(0xFF101820.toInt())
        ViewCompat.addAccessibilityAction(this, "Move forward") { _, _ -> up(); true }
        ViewCompat.addAccessibilityAction(this, "Turn around") { _, _ -> down(); true }
        ViewCompat.addAccessibilityAction(this, "Turn left") { _, _ -> left(); true }
        ViewCompat.addAccessibilityAction(this, "Turn right") { _, _ -> right(); true }
        ViewCompat.addAccessibilityAction(this, "Where am I") { _, _ -> tap(); true }
        ViewCompat.addAccessibilityAction(this, "Report a problem here") { _, _ -> report(); true }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // Android 14's "request touch passthrough", called by name so it works with any SDK version.
        if (Build.VERSION.SDK_INT >= 34) runCatching {
            AccessibilityNodeInfo::class.java.getMethod("setRequestTouchPassthrough", Boolean::class.javaPrimitiveType).invoke(info, true)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = detector.onTouchEvent(event)
}
