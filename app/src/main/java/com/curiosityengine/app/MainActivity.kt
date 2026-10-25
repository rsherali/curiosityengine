package com.curiosityengine.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Date

class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var keyField: EditText
    private lateinit var interestField: EditText
    private lateinit var spinner: Spinner
    private val choices = listOf("Auto") + Net.PROVIDERS

    private val prefs by lazy { getSharedPreferences("ce_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun tv(t: String, size: Float) = TextView(this).apply { text = t; textSize = size }
        root.addView(tv("Curiosity Engine", 28f))
        root.addView(tv("Build v$version | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nDevice time: ${Date()}", 14f))

        root.addView(tv("Provider (Auto = try every saved key)", 14f))
        spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        root.addView(spinner)

        keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(keyField)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val name = choices[pos]
                keyField.isEnabled = name != "Auto"
                keyField.hint = if (name == "Auto") "Auto uses all saved keys" else "$name API key (this device only)"
                keyField.setText(if (name == "Auto") "" else keyFor(name))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val saveBtn = Button(this).apply { text = "Save key" }
        saveBtn.setOnClickListener {
            val name = choices[spinner.selectedItemPosition]
            if (name == "Auto") { output.text = "Pick a provider to save its key."; return@setOnClickListener }
            prefs.edit().putString("key_$name", keyField.text.toString().trim()).apply()
            output.text = "$name key saved. Providers with keys: ${Net.PROVIDERS.filter { keyFor(it).isNotEmpty() }}"
        }
        root.addView(saveBtn)

        val testBtn = Button(this).apply { text = "1. Connection test" }
        testBtn.setOnClickListener { run("Reply with exactly: CONNECTION OK", false) }
        root.addView(testBtn)

        interestField = EditText(this).apply { hint = "Interest (e.g. volcanoes, jazz, chess)"; setText(prefs.getString("interest", "")) }
        root.addView(interestField)
        val cardBtn = Button(this).apply { text = "2. Get curiosity card" }
        cardBtn.setOnClickListener { getCard() }
        root.addView(cardBtn)

        output = TextView(this).apply { textSize = 16f; setTextIsSelectable(true); setPadding(0, 24, 0, 0) }
        root.addView(output)
        prefs.getString("lastCard", null)?.let { output.text = "(Cached last card)\n\n$it" }

        setContentView(ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(root)
        })
    }

    private fun keyFor(p: String): String = prefs.getString("key_$p", "")?.trim() ?: ""

    /** Runs a prompt on the selected provider (or Auto) in the background and returns (label, text) to onDone. */
    private fun ask(prompt: String, onDone: (String) -> String) {
        val choice = choices[spinner.selectedItemPosition]
        output.text = "Working..."
        Thread {
            val t0 = System.currentTimeMillis()
            val msg = try {
                val (who, text) = if (choice == "Auto") {
                    Net.askAuto(Net.PROVIDERS.associate { it to keyFor(it) }, prompt)
                } else {
                    if (keyFor(choice).isEmpty()) throw RuntimeException("No $choice key saved.")
                    Pair(choice, Net.ask(choice, keyFor(choice), prompt))
                }
                "PASS via $who (${System.currentTimeMillis() - t0} ms)\n\n" + onDone(text)
            } catch (e: Exception) {
                "FAIL\n${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread { output.text = msg }
        }.start()
    }

    private fun run(prompt: String, @Suppress("UNUSED_PARAMETER") json: Boolean) = ask(prompt) { "Reply: $it" }

    private fun getCard() {
        val interest = interestField.text.toString().trim()
        if (interest.isEmpty()) { output.text = "Type an interest first."; return }
        prefs.edit().putString("interest", interest).apply()
        val prompt = "You are a curiosity coach. Create one curiosity card about: $interest. " +
            "Reply with ONLY a JSON object, no other text, with string keys: hook (one intriguing sentence), " +
            "question (a thought-provoking question), answer (2-3 sentences), fact (one surprising fact), " +
            "deeper (a 'go deeper' paragraph of about 80 words), action (one small real-world action to do today), " +
            "pillar (one of THINK, LEARN, MOVE, ENGAGE, DISCOVER, CONFIDENCE)."
        ask(prompt) { raw ->
            val j = Net.extractJson(raw)
            val card = "HOOK\n${j.optString("hook")}\n\nQUESTION\n${j.optString("question")}\n\nANSWER\n${j.optString("answer")}\n\n" +
                "FACT\n${j.optString("fact")}\n\nGO DEEPER\n${j.optString("deeper")}\n\nACTION\n${j.optString("action")}\n\nPILLAR: ${j.optString("pillar")}"
            prefs.edit().putString("lastCard", card).apply()
            card
        }
    }
}
