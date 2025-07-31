package com.example.aibooo

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.example.aibooo.databinding.ActivityReminderBinding
import androidx.core.net.toUri
import java.text.SimpleDateFormat

class ReminderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReminderBinding
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    @SuppressLint("SetTextI18n", "SimpleDateFormat")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.TYPE_STATUS_BAR)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )

        val message = intent.getStringExtra("message") ?: "Reminder from Aiboo"
        val priority = intent.getStringExtra("priority") ?: "low"

        binding.textView6.text = message
        binding.textView8.text = "Scheduled at ${SimpleDateFormat("hh:mm a").format(System.currentTimeMillis())}"

        if (priority.lowercase() == "high") {
            binding.root.setBackgroundColor("#1F0001".toColorInt())
            binding.cardView6.setCardBackgroundColor("#8E0101".toColorInt())
            binding.textView7.text = "IMPORTANT"
        }

        acquireWakeLock()
        binding.close.setOnClickListener {
            finish()
            releaseWakeLock()
        }
    }
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
                "Aiboo:ReminderWakeLock"
            )
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
    }

    override fun onPause() {
        super.onPause()
        releaseWakeLock()
        finish()
    }
} 