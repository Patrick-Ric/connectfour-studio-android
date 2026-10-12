package io.github.patrickric.connectfourstudio

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

/**
 * Android 15+ draws every app edge to edge (targetSdk >= 35, no opt-out from
 * API 36 on): the window content may start behind the status bar and the
 * action bar and end behind the navigation bar. This pads the content view
 * (android.R.id.content, whose own padding is otherwise unused) so that it stays clear of the action bar and of the system bars / display
 * cutout. The padding is measured from the actual positions, so it is 0 on
 * older Android versions where the system already keeps the content clear.
 */
object SystemBars {
    /** Call after setContentView(). [force] = also below API 35 (testing). */
    @SuppressLint("DiscouragedApi") // the framework action bar has no public id
    fun keepClear(activity: Activity, force: Boolean = false) {
        if (Build.VERSION.SDK_INT < 35 && !force) return
        if (force && Build.VERSION.SDK_INT in 30..34) {
            // Emulate the Android 15 behaviour: draw behind the system bars.
            activity.window.setDecorFitsSystemWindows(false)
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val barId = activity.resources.getIdentifier("action_bar_container", "id", "android")
        val update = { insets: WindowInsets? -> pad(activity, content, barId, insets) }
        content.setOnApplyWindowInsetsListener { v, insets ->
            v.post { update(insets) }
            insets
        }
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update(content.rootWindowInsets) }
        content.requestApplyInsets()
    }

    private fun pad(activity: Activity, content: View, barId: Int, insets: WindowInsets?) {
        if (insets == null) return
        val l: Int
        val t: Int
        val r: Int
        val b: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            l = sys.left
            t = sys.top
            r = sys.right
            b = sys.bottom
        } else {
            @Suppress("DEPRECATION")
            l = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            t = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            r = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            b = insets.systemWindowInsetBottom
        }

        val win = activity.window.decorView
        val loc = IntArray(2)
        content.getLocationInWindow(loc)
        val cLeft = loc[0]
        val cTop = loc[1]
        val cRight = cLeft + content.width
        val cBottom = cTop + content.height

        // Top edge to keep clear: below a visible action bar, else below the status bar.
        var clearTop = t
        val bar = if (barId != 0) win.findViewById<View>(barId) else null
        if (bar != null && bar.visibility == View.VISIBLE && bar.height > 0 && activity.actionBar?.isShowing == true) {
            bar.getLocationInWindow(loc)
            clearTop = maxOf(clearTop, loc[1] + bar.height)
        }
        val pl = maxOf(0, l - cLeft)
        val pt = maxOf(0, clearTop - cTop)
        val pr = maxOf(0, cRight - (win.width - r))
        val pb = maxOf(0, cBottom - (win.height - b))
        if (content.paddingLeft != pl || content.paddingTop != pt || content.paddingRight != pr || content.paddingBottom != pb) {
            content.setPadding(pl, pt, pr, pb)
        }
    }
}

/** Testing only: emulate Android 15 edge-to-edge on older devices (set to true to test). */
const val FORCE_EDGE_TEST = false
