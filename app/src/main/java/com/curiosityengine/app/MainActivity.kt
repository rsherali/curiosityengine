package com.curiosityengine.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }

        val title = TextView(this).apply {
            text = "Curiosity Engine"
            textSize = 32f
            gravity = Gravity.CENTER
        }
        val info = TextView(this).apply {
            text = "Build v$version\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(title)
            addView(info)
        }
        setContentView(root)
    }
}
