package com.ebookreader.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
            tvStatus.text = "辅助功能已开启 ✅"
            tvStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, theme))
            btnAccessibility.text = "重新配置"
            // 引导用户打开悬浮窗权限
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            }
        } else {
            tvStatus.text = "辅助功能未开启"
            tvStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, theme))
            btnAccessibility.text = "开启辅助功能"
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
