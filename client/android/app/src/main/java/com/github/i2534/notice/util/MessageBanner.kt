package com.github.i2534.notice.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.github.i2534.notice.R

enum class BannerType(
    val colorRes: Int,
) {
    Success(R.color.status_connected),
    Error(R.color.error),
    Warning(R.color.warning),
    Info(R.color.primary),
}

object MessageBanner {

    private const val DEFAULT_DURATION = 2500L
    private const val ANIMATION_DURATION = 250L

    private var currentBanner: BannerImpl? = null
    private val queue: MutableList<QueueItem> = mutableListOf()
    private val handler = Handler(Looper.getMainLooper())
    private var currentActivity: Activity? = null

    fun init(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { currentActivity = activity }
            override fun onActivityPaused(activity: Activity) { if (currentActivity === activity) currentActivity = null }
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) { if (currentActivity === activity) currentActivity = null }
        })
    }

    private data class QueueItem(
        val message: String,
        val type: BannerType,
        val duration: Long,
    )

    private class BannerImpl(
        private val activity: Activity,
        message: String,
        type: BannerType,
        private val dismissDelay: Long,
    ) {
        private val bannerView: FrameLayout

        init {
            val density = activity.resources.displayMetrics.density

            bannerView = FrameLayout(activity).apply {
                val paddingX = (16 * density).toInt()
                val paddingY = (8 * density).toInt()
                setPadding(paddingX, paddingY, paddingX, paddingY)

                val bgColor = activity.resources.getColor(R.color.surface, activity.theme)
                background = ColorDrawable(bgColor)
            }

            val textView = TextView(activity).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            bannerView.addView(textView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            })
        }

        fun show(onDone: () -> Unit) {
            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = activity.resources.displayMetrics.density
            val statusBarHeight = getStatusBarHeight(activity)

            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                else @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
                y = statusBarHeight + (8 * density).toInt()
            }

            bannerView.alpha = 0f
            wm.addView(bannerView, params)

            bannerView.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION)
                .withEndAction {
                    handler.postDelayed({
                        hide(onDone, wm)
                    }, dismissDelay)
                }
                .start()
        }

        private fun hide(onComplete: () -> Unit, wm: WindowManager) {
            bannerView.animate()
                .alpha(0f)
                .setDuration(ANIMATION_DURATION)
                .withEndAction {
                    try { wm.removeView(bannerView) } catch (_: Exception) {}
                    onComplete()
                }
                .start()
        }

        fun hideBanner(onComplete: () -> Unit) {
            try {
                val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(bannerView)
            } catch (_: Exception) {}
            onComplete()
        }

        private fun getStatusBarHeight(context: Context): Int {
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
        }
    }

    fun show(
        context: Context,
        message: String,
        type: BannerType = BannerType.Info,
        duration: Long = DEFAULT_DURATION,
    ) {
        enqueue(message, type, duration)
    }

    fun showRes(
        context: Context,
        @StringRes resId: Int,
        type: BannerType = BannerType.Info,
        duration: Long = DEFAULT_DURATION,
    ) {
        show(context, context.getString(resId), type, duration)
    }

    fun dismissAll() {
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        currentBanner?.let { banner ->
            banner.hideBanner {
                if (currentBanner === banner) currentBanner = null
            }
        }
    }

    private fun enqueue(
        message: String,
        type: BannerType,
        duration: Long,
    ) {
        queue.add(QueueItem(message, type, duration))
        processQueue()
    }

    private fun processQueue() {
        if (currentBanner != null || queue.isEmpty()) return
        val host = currentActivity ?: run { Log.w("MessageBanner", "no currentActivity"); queue.clear(); return }
        val item = queue.removeAt(0)
        Log.d("MessageBanner", "showing: ${item.message}")
        currentBanner = BannerImpl(host, item.message, item.type, item.duration).apply {
            show {
                currentBanner = null
                processQueue()
            }
        }
    }
}
