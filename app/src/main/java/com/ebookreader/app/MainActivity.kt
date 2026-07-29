package com.ebookreader.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnAccessibility: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        btnAccessibility = findViewById(R.id.btn_open_accessibility)

        btnAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityStatus()
    }

    private fun checkAccessibilityStatus() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            val ttsError = ReaderAccessibilityService.instance?.tts?.initError
            if (ttsError != null) {
                tvStatus.text = ttsError
                tvStatus.setTextColor(resources.getColor(android.R.color.holo_orange_dark, theme))
                btnAccessibility.text = "安装文字转语音引擎"
                btnAccessibility.setOnClickListener {
                    try {
                        // 尝试打开 Google TTS 下载页
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                            "market://details?id=com.google.android.tts")))
                    } catch (e: Exception) {
                        // Play 商店不可用，打开 TTS 设置
                        startActivity(Intent().apply {
                            action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                        })
                    }
                }
            } else {
                tvStatus.text = "就绪 打开阅读App后点悬浮按钮播放"
                tvStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, theme))
                btnAccessibility.text = "重新配置"
                btnAccessibility.setOnClickListener { openAccessibilitySettings() }
            }
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            }
        } else {
            tvStatus.text = "辅助功能未开启"
            tvStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, theme))
            btnAccessibility.text = "开启辅助功能"
            btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        android.widget.Toast.makeText(this,
            "在列表中找到听书助手并开启", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            android.widget.Toast.makeText(this,
                "请允许在其他应用上层显示", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
