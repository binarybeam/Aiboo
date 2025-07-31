package com.example.aibooo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("message") ?: "Reminder from Aiboo"
        val priority = intent.getStringExtra("priority") ?: "low"

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "Aiboo:ReminderWakeLock"
        )
        wakeLock.acquire(10 * 60 * 1000L)

        val reminderIntent = Intent(context, ReminderActivity::class.java).apply {
            putExtra("message", message)
            putExtra("priority", priority)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        try {
            context.startActivity(reminderIntent)
        }
        catch (e: Exception) {
            android.util.Log.e("ReminderReceiver", "Failed to start ReminderActivity: ${e.message}")
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification: Notification

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "REMINDER", "Reminders",
                NotificationManager.IMPORTANCE_HIGH
            )
            val pendingIntent = PendingIntent.getActivity(context, 0, reminderIntent, PendingIntent.FLAG_IMMUTABLE)

            notificationManager.createNotificationChannel(channel)
            notification = NotificationCompat.Builder(context, "REMINDER")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentTitle("Aiboo Reminder")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setVibrate(longArrayOf(0, 500, 1000, 500))
                .setContentIntent(pendingIntent)
                .build()
        }
        else {
            val pendingIntent = PendingIntent.getActivity(context, 0, reminderIntent, PendingIntent.FLAG_IMMUTABLE)
            
            notification = @Suppress("DEPRECATION")
            Notification.Builder(context).apply {
                setContentTitle("Aiboo Reminder")
                setContentText(message)
                setSmallIcon(R.mipmap.ic_launcher_round)
                setStyle(Notification.BigTextStyle().bigText(message))
                setAutoCancel(true)
                setContentIntent(pendingIntent)
            }.build()
        }
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mediaPlayer = MediaPlayer.create(context, if (priority.lowercase() == "high") R.raw.loud_alarm else R.raw.alarm)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        mediaPlayer!!.start()

        if (priority == "high") {
            Handler(Looper.getMainLooper()).postDelayed({
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
            }, 10000)
        }
        else {
            Handler(Looper.getMainLooper()).postDelayed({
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
            }, 2000)
        }
        
        Handler(Looper.getMainLooper()).postDelayed({
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }, 5000)
    }
}