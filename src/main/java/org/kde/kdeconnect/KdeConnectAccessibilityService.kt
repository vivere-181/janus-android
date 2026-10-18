/*
 * SPDX-FileCopyrightText: 2021 SohnyBohny <sohny.bean@streber24.de>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.kde.kdeconnect.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin.Cursor
import org.kde.kdeconnect_tp.R
import java.util.ArrayDeque
import java.util.Deque

class KdeConnectAccessibilityService : AccessibilityService() {

    @JvmField
    var window: AccessibilityNodeInfo? = null

    private lateinit var cursorView: View
    private lateinit var cursorLayout: LayoutParams
    private lateinit var windowManager: WindowManager
    private lateinit var runHandler: Handler
    private lateinit var hideRunnable: Runnable
    private var swipeStoke: GestureDescription.StrokeDescription? = null
    private var scrollSum = 0.0

    override fun onCreate() {
        instance = this
        Log.i("KdeConnectAccessibilityService", "created")
    }

    @Suppress("DEPRECATION")
    override fun onServiceConnected() {
        // Create an overlay and display the cursor
        windowManager = ContextCompat.getSystemService(this, WindowManager::class.java)!!
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        cursorView = View.inflate(baseContext, R.layout.mouse_receiver_cursor, null)
        cursorLayout = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            LayoutParams.FLAG_DISMISS_KEYGUARD or LayoutParams.FLAG_NOT_FOCUSABLE
                    or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_FULLSCREEN
                    or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        // allow cursor to move over status bar on devices having a display cutout
        // https://developer.android.com/guide/topics/display-cutout/#render_content_in_short_edge_cutout_areas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cursorLayout.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        cursorLayout.gravity = Gravity.START or Gravity.TOP
        cursorLayout.x = displayMetrics.widthPixels / 2
        cursorLayout.y = displayMetrics.heightPixels / 2

        // https://developer.android.com/training/system-ui/navigation.html#behind
        cursorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)

        windowManager.addView(cursorView, cursorLayout)

        hideRunnable = Runnable {
            cursorView.visibility = View.GONE
            Log.i("KdeConnectAccessibilityService", "Hiding pointer due to inactivity")
        }
        runHandler = Handler()

        cursorView.visibility = View.GONE
    }

    private fun hideAfter5Seconds() {
        hide(5000)
    }

    fun hide(delayMillis: Int) {
        runHandler.removeCallbacks(hideRunnable)
        runHandler.postDelayed(hideRunnable, delayMillis.toLong())
    }

    val x: Int
        get() = cursorLayout.x + cursorView.width / 2

    val y: Int
        get() = cursorLayout.y + cursorView.height / 2

    @Suppress("DEPRECATION")
    fun moveView(dx: Int, dy: Int) {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)

        cursorLayout.x += dx
        cursorLayout.y += dy

        if (x > displayMetrics.widthPixels)
            cursorLayout.x = displayMetrics.widthPixels - cursorView.width / 2
        if (y > displayMetrics.heightPixels)
            cursorLayout.y = displayMetrics.heightPixels - cursorView.height / 2
        if (x < 0) cursorLayout.x = -cursorView.width / 2
        if (y < 0) cursorLayout.y = -cursorView.height / 2

        Cursor.x = x
        Cursor.y = y

        Handler(mainLooper).post {
            // Log.i("KdeConnectAccessibilityService", "performing move");
            try {
                windowManager.updateViewLayout(cursorView, cursorLayout)
                cursorView.visibility = View.VISIBLE
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
    }

    fun move(dx: Int, dy: Int): Boolean {
        val fromX = x
        val fromY = y

        moveView(dx, dy)

        hideAfter5Seconds()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isSwiping()) {
            return continueSwipe(fromX, fromY)
        }

        return true
    }

    fun setPos(x: Int, y: Int): Boolean {
        return move(x - this.x, y - this.y)
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun click(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isSwiping()) {
            return stopSwipe()
        }

        return click(x, y)
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun click(x: Int, y: Int): Boolean {
        return dispatchGesture(createClick(x, y, 1 /*ms*/), null, null)
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun longClick(): Boolean {
        return dispatchGesture(createClick(x, y,
                ViewConfiguration.getLongPressTimeout()), null, null)
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun longClickSwipe(): Boolean {
        return if (isSwiping()) {
            stopSwipe()
        } else {
            startSwipe()
        }
    }

    private fun isSwiping(): Boolean {
        return swipeStoke != null
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun startSwipe(): Boolean {
        assert(swipeStoke == null)
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val stroke = GestureDescription.StrokeDescription(path, 0, 1, true)
        swipeStoke = stroke
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        cursorView.findViewById<ImageView>(R.id.mouse_cursor).setImageResource(R.drawable.mouse_pointer_clicked)
        return dispatchGesture(builder.build(), null, null)
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun continueSwipe(fromX: Int, fromY: Int): Boolean {
        val path = Path()
        path.moveTo(fromX.toFloat(), fromY.toFloat())
        path.lineTo(x.toFloat(), y.toFloat())
        val stroke = swipeStoke!!.continueStroke(path, 0, 5, true)
        swipeStoke = stroke
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        return dispatchGesture(builder.build(), null, null)
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun stopSwipe(): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val currentStroke = swipeStoke ?: return true
        val stroke = currentStroke.continueStroke(path, 0, 1, false)
        swipeStoke = stroke
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        swipeStoke = null
        cursorView.findViewById<ImageView>(R.id.mouse_cursor).setImageResource(R.drawable.mouse_pointer)
        return dispatchGesture(builder.build(), null, null)
    }

    fun scroll(dx: Int, dy: Int): Boolean {
        scrollSum += dy
        if (Math.signum(dy.toDouble()) != Math.signum(scrollSum)) scrollSum = dy.toDouble()
        if (Math.abs(scrollSum) < 500) return false
        scrollSum = 0.0

        val scrollable = findNodeByAciton(rootInActiveWindow,
                if (dy > 0) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)

        if (scrollable == null) return false

        return scrollable.performAction(
            if (dy > 0) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
        )
    }

    // https://codelabs.developers.google.com/codelabs/developing-android-a11y-service/#6
    private fun findNodeByAciton(root: AccessibilityNodeInfo?, action: AccessibilityNodeInfo.AccessibilityAction): AccessibilityNodeInfo? {
        val deque: Deque<AccessibilityNodeInfo> = ArrayDeque()
        deque.add(root!!)
        while (!deque.isEmpty()) {
            val node = deque.removeFirst()
            if (node.actionList.contains(action)) {
                return node
            }
            for (i in 0 until node.childCount) {
                deque.addLast(node.getChild(i))
            }
        }
        return null
    }

    override fun onDestroy() {
        if (::windowManager.isInitialized && ::cursorView.isInitialized) {
            windowManager.removeView(cursorView)
        }
        window = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(ignored: AccessibilityEvent?) {
        window = rootInActiveWindow
    }

    override fun onInterrupt() {

    }

    companion object {
        @JvmField
        var instance: KdeConnectAccessibilityService? = null

        @RequiresApi(api = Build.VERSION_CODES.N)
        private fun createClick(x: Int, y: Int, duration: Int): GestureDescription {
            val clickPath = Path()
            clickPath.moveTo(x.toFloat(), y.toFloat())
            val clickStroke = GestureDescription.StrokeDescription(clickPath, 0, duration.toLong())
            val clickBuilder = GestureDescription.Builder()
            clickBuilder.addStroke(clickStroke)
            return clickBuilder.build()
        }
    }
}
