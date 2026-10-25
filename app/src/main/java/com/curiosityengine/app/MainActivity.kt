package com.curiosityengine.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Date

class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var keyField: EditText
    private lateinit var interestField: EditText

    private val prefs by lazy { getSharedPreferences("ce_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun tv(t: String, size: Float) = TextView(this).apply { text = t; textSize = size }
        root.addView(tv("Curiosity Engine", 28f))
        root.addView(tv("Build v$version | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nDevice time: ${Date()}", 14f))

        keyField = EditText(this).apply {
            hint = "Gemini API key (stored on this device only)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        root.addView(keyField)
        val saveBtn = Button(this).apply { text = "Save key" }
        saveBtn.setOnClickListener {
            prefs.edit().putString("key", keyField.text.toString().trim()).apply()
            output.text = "Key saved."
        }
        root.addView(saveBtn)

        val testBtn = Button(this).apply { text = "1. Connection test" }
        testBtn.setOnClickListener { connectionTest() }
        root.addView(testBtn)

        interestField = EditText(this).apply { hint = "Interest (e.g. volcanoes, jazz, chess)"; setText(prefs.getString("interest", "")) }
        root.addView(interestField)
        val cardBtn = Button(this).apply { text = "2. Get curiosity card" }
        cardBtn.setOnClickListener { getCard() }
        root.addView(cardBtn)

        output = TextView(this).apply { textSize = 16f; setTextIsSelectable(true); setPadding(0, 24, 0, 0) }
        root.addView(output)
        val last = prefs.getString("lastCard", null)
        if (last != null) output.text = "(Cached last card)\n\n" + last

        setContentView(ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(root)
        })
    }

    private fun key(): String = prefs.getString("key", "")?.trim() ?: ""

    private fun connectionTest() {
        if (key().isEmpty()) { output.text = "Enter and save your API key first."; return }
        output.text = "Testing..."
        Thread {
            val t0 = System.currentTimeMillis()
            val msg = try {
                val r = Net.gemini(key(), "Reply with exactly: CONNECTION OK", false)
                "PASS (${System.currentTimeMillis() - t0} ms)\nReply: $r"
            } catch (e: Exception) {
                "FAIL\n${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread { output.text = msg }
        }.start()
    }

    private fun getCard() {
        val interest = interestField.text.toString().trim()
        if (key().isEmpty()) { output.text = "Enter and save your API key first."; return }
        if (interest.isEmpty()) { output.text = "Type an interest first."; return }
        prefs.edit().putString("interest", interest).apply()
        output.text = "Thinking..."
        val prompt = "You are a curiosity coach. Create one curiosity card about: $interest. " +
            "Return JSON with string keys: hook (one intriguing sentence), question (a thought-provoking question), " +
            "answer (2-3 sentences), fact (one surprising fact), deeper (a 'go deeper' paragraph of about 80 words), " +
            "action (one small real-world action to do today), pillar (one of THINK, LEARN, MOVE, ENGAGE, DISCOVER, CONFIDENCE)."
        Thread {
            val msg = try {
                val j = JSONObject(Net.gemini(key(), prompt, true))
                "HOOK\n${j.optString("hook")}\n\nQUESTION\n${j.optString("question")}\n\nANSWER\n${j.optString("answer")}\n\n" +
                    "FACT\n${j.optString("fact")}\n\nGO DEEPER\n${j.optString("deeper")}\n\nACTION\n${j.optString("action")}\n\nPILLAR: ${j.optString("pillar")}"
            } catch (e: Exception) {
                "FAIL\n${e.javaClass.simpleName}: ${e.message}"
            }
            if (!msg.startsWith("FAIL")) prefs.edit().putString("lastCard", msg).apply()
            runOnUiThread { output.text = msg }
        }.start()
    }
}
