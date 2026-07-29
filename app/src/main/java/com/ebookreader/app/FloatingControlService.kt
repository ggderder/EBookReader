package com.ebookreader.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * 悬浮控制面板 —— 在前台显示播放控制按钮
 */
class FloatingControlService : Service() {

    companion object {
        var instance: FloatingControlService? = null
            private set
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isPlaying = false
    private var currentSpeed = 1.0f

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(1, buildNotification("听书助手就绪"))
        initFloatingWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        removeFloatingWindow()
        super.onDestroy()
    }

    // ==========================================
    // 悬浮窗
    // ==========================================

    @Suppress("DEPRECATION")
    private fun initFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.floating_control, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = 100

        windowManager?.addView(floatingView, params)

        // 拖动支持
        setupDrag(floatingView!!, params)

        // 按钮事件
        setupButtons(floatingView!!)
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtons(view: View) {
        val btnPlay: ImageButton = view.findViewById(R.id.btn_play)
        val btnRewind: ImageButton = view.findViewById(R.id.btn_rewind)
        val btnSkip: ImageButton = view.findViewById(R.id.btn_skip)
        val btnSpeed: ImageButton = view.findViewById(R.id.btn_speed)
        val btnClose: ImageButton = view.findViewById(R.id.btn_close)

        btnPlay.setOnClickListener {
            if (isPlaying) {
                ReaderAccessibilityService.instance?.handleAction(ReaderAccessibilityService.ACTION_PAUSE)
            } else {
                ReaderAccessibilityService.instance?.handleAction(ReaderAccessibilityService.ACTION_START)
            }
        }

        btnRewind.setOnClickListener {
            // 重新读当前页
            ReaderAccessibilityService.instance?.readCurrentPage()
        }

        btnSkip.setOnClickListener {
            ReaderAccessibilityService.instance?.handleAction(ReaderAccessibilityService.ACTION_SKIP)
        }

        btnSpeed.setOnClickListener {
            // 循环切换语速：1.0 → 1.5 → 2.0 → 0.75 → 1.0
            val speeds = floatArrayOf(1.0f, 1.5f, 2.0f, 0.75f, 1.0f)
            val current = ReaderAccessibilityService.instance?.getCurrentSpeed() ?: 1.0f
            val next = speeds.firstOrNull { it > current } ?: speeds.first()
            currentSpeed = next
            ReaderAccessibilityService.instance?.handleAction(ReaderAccessibilityService.ACTION_SPEED, next)
            Toast.makeText(this, "语速: ${next}x", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            ReaderAccessibilityService.instance?.handleAction(ReaderAccessibilityService.ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
            stopSelf()
        }
    }

    fun updatePlayState(status: TTSManager.Status) {
        val btnPlay = floatingView?.findViewById<ImageButton>(R.id.btn_play)
        when (status) {
            TTSManager.Status.SPEAKING -> {
                isPlaying = true
                btnPlay?.setImageResource(android.R.drawable.ic_media_pause)
                updateNotification("正在朗读...")
            }
            TTSManager.Status.PAUSED, TTSManager.Status.IDLE -> {
                isPlaying = false
                btnPlay?.setImageResource(android.R.drawable.ic_media_play)
                updateNotification("已暂停")
            }
            else -> {}
        }
    }

    private fun removeFloatingWindow() {
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
        }
    }

    // ==========================================
    // 通知栏
    // ==========================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ebook_reader",
                "听书服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "听书助手后台服务"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, ReaderAccessibilityService::class.java).apply {
            action = "stop"
        }
        val stopPending = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, "ebook_reader")
            .setContentTitle("听书助手")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, buildNotification(text))
    }
}
