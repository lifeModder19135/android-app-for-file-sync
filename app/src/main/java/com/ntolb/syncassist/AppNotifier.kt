package com.ntolb.syncassist

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast

enum class SyncNotificationKind {
    AUTOMATIC,
    SINGLE_SET,
    ALL_SETS,
}

enum class ChangeNotificationKind {
    SET_CREATED,
    FILE_ADDED,
}

object AppNotifier {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun sync(context: Context, settings: AppSettings, kind: SyncNotificationKind, message: String) {
        val enabled = when (kind) {
            SyncNotificationKind.AUTOMATIC -> settings.autoSyncNotifications
            SyncNotificationKind.SINGLE_SET -> settings.singleSetSyncNotifications
            SyncNotificationKind.ALL_SETS -> settings.allSetsSyncNotifications
        }
        if (!enabled) return
        deliver(
            context,
            message,
            settings.syncToast,
            settings.syncVibration,
            settings.syncSound,
        )
    }

    fun change(context: Context, settings: AppSettings, kind: ChangeNotificationKind, message: String) {
        val enabled = when (kind) {
            ChangeNotificationKind.SET_CREATED -> settings.setCreatedNotifications
            ChangeNotificationKind.FILE_ADDED -> settings.fileAddedNotifications
        }
        if (!enabled) return
        deliver(
            context,
            message,
            settings.changeToast,
            settings.changeVibration,
            settings.changeSound,
        )
    }

    private fun deliver(
        context: Context,
        message: String,
        toast: Boolean,
        vibration: Boolean,
        sound: Boolean,
    ) {
        if (!toast && !vibration && !sound) return
        val appContext = context.applicationContext
        mainHandler.post {
            if (toast) Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            if (vibration) vibrate(appContext)
            if (sound) playSound(appContext)
        }
    }

    private fun vibrate(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Could not vibrate for an app notification.", error)
        }
    }

    private fun playSound(context: Context) {
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            if (tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)) {
                mainHandler.postDelayed({ tone.release() }, 250)
            } else {
                tone.release()
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not play the app notification sound.", error)
        }
    }

    private const val TAG = "AppNotifier"
}
