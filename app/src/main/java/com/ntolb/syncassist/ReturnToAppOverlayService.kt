package com.ntolb.syncassist

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class ReturnToAppOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var returnButton: TextView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission was revoked; return button not shown.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (returnButton != null) return START_NOT_STICKY

        val button = TextView(this).apply {
            text = "Return to SyncAssist"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(55, 84, 145))
                cornerRadius = dp(22).toFloat()
            }
            elevation = dp(8).toFloat()
            contentDescription = "Return to SyncAssist"
            setOnClickListener {
                startActivity(
                    Intent(this@ReturnToAppOverlayService, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        )
                    },
                )
                stopSelf()
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val parameters = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(72)
        }
        try {
            windowManager?.addView(button, parameters)
            returnButton = button
        } catch (error: SecurityException) {
            Log.e(TAG, "Could not show return-to-app overlay.", error)
            stopSelf(startId)
        } catch (error: WindowManager.BadTokenException) {
            Log.e(TAG, "Could not attach return-to-app overlay.", error)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        returnButton?.let { button ->
            windowManager?.removeView(button)
            returnButton = null
        }
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FileSyncOverlay"

        fun stop(context: Context) {
            context.stopService(Intent(context, ReturnToAppOverlayService::class.java))
        }
    }
}
