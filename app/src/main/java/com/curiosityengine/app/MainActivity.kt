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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random

class Card(val topic: String, val kind: String, val via: String, val json: JSONObject) {
    var deeper: String? = null
}

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("ce_prefs", Context.MODE_PRIVATE) }
    private val rnd = Random()

    private val CATS = linkedMapOf(
        "Science & Nature" to listOf("Space", "Animals", "Oceans", "Weather", "Human body", "Plants", "Physics", "Chemistry"),
        "Maths" to listOf("Puzzles", "Numbers", "Geometry", "Probability", "Logic", "Famous mathematicians"),
        "Technology" to listOf("AI", "Computers", "Inventions", "Internet", "Robotics", "Gadgets"),
        "Food" to listOf("Cooking science", "World cuisines", "Ingredients", "Drinks", "Food history", "Nutrition")
    )
    private val WILD = listOf("Music", "History", "Art", "Languages", "Psychology", "Architecture", "Geography", "Sport")

    // ---- feed state ----
    private var current: Card? = null
    @Volatile private var next: Card? = null
    @Volatile private var fetching = false
    @Volatile private var loading = false
    @Volatile private var generation = 0   // bumps when interests change; stale fetches are discarded
    private val recentHooks = ArrayList<String>()

    private lateinit var statusView: TextView
    private lateinit var topicView: TextView
    private lateinit var hookView: TextView
    private lateinit var questionView: TextView
    private lateinit var answerView: TextView
    private lateinit var deeperView: TextView
    private lateinit var revealBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (selected().isEmpty()) showInterests() else showFeed()
    }

    // ---------- helpers ----------
    private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()
    private fun version() = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
    private fun keyFor(p: String): String = prefs.getString("key_$p", "")?.trim() ?: ""
    private fun selected(): List<String> = (prefs.getString("tags", "") ?: "").split("|").filter { it.isNotEmpty() }
    private fun weight(tag: String) = prefs.getInt("w_$tag", 3)

    private fun text(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { onClick() }
    }

    private fun row(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in buttons) addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun screen(content: LinearLayout) {
        content.setPadding(dp(16), dp(16), dp(16), dp(16))
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    // ---------- 1. Interests ----------
    private fun showInterests() {
        val root = vertical()
        root.addView(text("Choose your interests", 24f, true))
        root.addView(text("Pick at least one. You can change this any time.", 14f))
        val boxes = ArrayList<CheckBox>()
        val chosen = selected()
        for ((cat, tags) in CATS) {
            root.addView(text(cat, 18f, true))
            for (t in tags) {
                val cb = CheckBox(this).apply { text = t; isChecked = chosen.contains(t) }
                boxes.add(cb); root.addView(cb)
            }
        }
        val msg = text("", 14f)
        root.addView(msg)
        root.addView(btn("Save & start") {
            val picked = boxes.filter { it.isChecked }.map { it.text.toString() }
            if (picked.isEmpty()) { msg.text = "Pick at least one interest." } else {
                val old = selected()
                val blocked = ((prefs.getString("blocked", "") ?: "").split("|").filter { it.isNotEmpty() } +
                    old.filter { !picked.contains(it) }).filter { !picked.contains(it) }.distinct()
                prefs.edit().putString("tags", picked.joinToString("|"))
                    .putString("blocked", blocked.joinToString("|")).apply()
                generation++
                current = null; next = null; loading = false; fetching = false
                recentHooks.clear()
                showFeed()
            }
        })
        screen(root)
    }

    // ---------- 2. Feed ----------
    private fun showFeed() {
        val root = vertical()
        root.addView(row(
            btn("Interests") { showInterests() },
            btn("Favourites") { showFavourites() },
            btn("Settings") { showSettings() }
        ))
        topicView = text("", 13f)
        statusView = text("", 13f)
        hookView = text("", 22f, true)
        questionView = text("", 18f)
        answerView = text("", 16f).apply { visibility = View.GONE }
        deeperView = text("", 15f).apply { visibility = View.GONE }
        revealBtn = btn("Show answer") {
            answerView.visibility = View.VISIBLE
            revealBtn.visibility = View.GONE
        }.apply { visibility = View.GONE }

        root.addView(topicView); root.addView(statusView)
        root.addView(hookView); root.addView(questionView)
        root.addView(revealBtn); root.addView(answerView)
        root.addView(row(
            btn("More like this") { signal("more") },
            btn("Less like this") { signal("less") },
            btn("Favourite") { signal("favourite") }
        ))
        root.addView(row(
            btn("Go deeper") { goDeeper() },
            btn("Surprised me") { signal("surprised") }
        ))
        root.addView(deeperView)
        root.addView(btn("Next card") { loadNext() })
        root.addView(text("Build v${version()}", 11f))
        screen(root)

        val c = current
        if (c != null) show(c) else loadNext()
    }

    private fun pickTopic(): Pair<String, String> {
        val sel = selected()
        if (sel.isEmpty()) return Pair(WILD[rnd.nextInt(WILD.size)], "unexpected")
        val r = rnd.nextInt(100)
        if (r >= 90) return Pair(WILD[rnd.nextInt(WILD.size)], "unexpected")
        if (r >= 70) {
            val cats = CATS.filter { e -> e.value.any { sel.contains(it) } }
            val blocked = (prefs.getString("blocked", "") ?: "").split("|")
            val adj = cats.values.flatten().filter { !sel.contains(it) && !blocked.contains(it) }
            if (adj.isNotEmpty()) return Pair(adj[rnd.nextInt(adj.size)], "adjacent")
        }
        val pool = sel.filter { weight(it) > 0 }.ifEmpty { sel }
        var total = 0
        for (t in pool) total += maxOf(weight(t), 1)
        var pick = rnd.nextInt(total)
        for (t in pool) { pick -= maxOf(weight(t), 1); if (pick < 0) return Pair(t, "preferred") }
        return Pair(pool[0], "preferred")
    }

    private fun fetchText(prompt: String): Pair<String, String> {
        val choice = prefs.getString("provider", "Auto") ?: "Auto"
        if (choice == "Auto") return Net.askAuto(Net.PROVIDERS.associate { it to keyFor(it) }, prompt)
        if (keyFor(choice).isEmpty()) throw RuntimeException("No $choice key saved. Open Settings.")
        return Pair(choice, Net.ask(choice, keyFor(choice), prompt))
    }

    private fun fetchCard(): Card {
        val (topic, kind) = pickTopic()
        val avoid = recentHooks.takeLast(6).joinToString(" | ")
        val prompt = "You are a curiosity coach for a curious adult. Create one curiosity card about: $topic. " +
            "Make it surprising and specific, not a generic fact. Avoid repeating these earlier hooks: $avoid. " +
            "Reply with ONLY a JSON object, no other text, with string keys: " +
            "hook (one intriguing sentence), question (a thought-provoking question), " +
            "answer (2-3 sentences), fact (one surprising related fact), " +
            "action (one small real-world action to do today), " +
            "pillar (one of THINK, LEARN, MOVE, ENGAGE, DISCOVER, CONFIDENCE)."
        val (via, raw) = fetchText(prompt)
        return Card(topic, kind, via, Net.extractJson(raw))
    }

    private fun prefetch() {
        if (next != null || fetching) return
        fetching = true
        val gen = generation
        Thread {
            try {
                val c = fetchCard()
                if (gen == generation) next = c      // discard if interests changed meanwhile
            } catch (e: Exception) { /* ignore: next tap retries */ }
            if (gen == generation) fetching = false
        }.start()
    }

    private fun loadNext() {
        if (loading) return
        val n = next
        if (n != null) { next = null; show(n); prefetch(); return }
        loading = true
        val gen = generation
        statusView.text = "Fetching a card..."
        Thread {
            try {
                val c = fetchCard()
                runOnUiThread { if (gen == generation) { loading = false; show(c); prefetch() } }
            } catch (e: Exception) {
                runOnUiThread { loading = false; statusView.text = "FAIL: ${e.javaClass.simpleName}: ${e.message}\nTap Next card to retry." }
            }
        }.start()
    }

    private fun show(c: Card) {
        current = c
        val j = c.json
        recentHooks.add(j.optString("hook"))
        topicView.text = "${c.topic} (${c.kind}) | ${j.optString("pillar")} | via ${c.via}"
        statusView.text = ""
        hookView.text = j.optString("hook")
        questionView.text = j.optString("question")
        answerView.text = "ANSWER\n${j.optString("answer")}\n\nFACT\n${j.optString("fact")}\n\nTRY TODAY\n${j.optString("action")}"
        answerView.visibility = View.GONE
        revealBtn.visibility = View.VISIBLE
        val d = c.deeper
        if (d != null) { deeperView.text = d; deeperView.visibility = View.VISIBLE } else deeperView.visibility = View.GONE
    }

    private fun signal(kind: String) {
        val c = current ?: return
        val e = prefs.edit()
        e.putInt("n_$kind", prefs.getInt("n_$kind", 0) + 1)
        when (kind) {
            "more" -> e.putInt("w_${c.topic}", weight(c.topic) + 2)
            "less" -> e.putInt("w_${c.topic}", maxOf(0, weight(c.topic) - 2))
            "surprised" -> e.putInt("w_${c.topic}", weight(c.topic) + 1)
            "favourite" -> {
                val arr = try { JSONArray(prefs.getString("favs", "[]")) } catch (x: Exception) { JSONArray() }
                arr.put(JSONObject().put("topic", c.topic).put("card", c.json))
                e.putString("favs", arr.toString())
            }
        }
        e.apply()
        statusView.text = when (kind) {
            "more" -> "Noted: more ${c.topic}."
            "less" -> "Noted: less ${c.topic}."
            "surprised" -> "Noted: that surprised you."
            else -> "Saved to favourites."
        }
    }

    private fun goDeeper() {
        val c = current ?: return
        if (c.deeper != null) return
        statusView.text = "Going deeper..."
        val j = c.json
        val prompt = "Explain more about this for a curious adult in about 200 words of plain text (no markdown, no lists). " +
            "Topic: ${c.topic}. Question: ${j.optString("question")}. Short answer so far: ${j.optString("answer")}. " +
            "End with one suggestion for what to explore next."
        Thread {
            try {
                val (_, raw) = fetchText(prompt)
                runOnUiThread {
                    c.deeper = "GO DEEPER\n" + raw.trim()
                    if (current === c) { deeperView.text = c.deeper; deeperView.visibility = View.VISIBLE; statusView.text = "" }
                }
            } catch (e: Exception) {
                runOnUiThread { statusView.text = "FAIL: ${e.javaClass.simpleName}: ${e.message}" }
            }
        }.start()
    }

    // ---------- Favourites ----------
    private fun showFavourites() {
        val root = vertical()
        root.addView(btn("Back") { showFeed() })
        root.addView(text("Favourites", 24f, true))
        val arr = try { JSONArray(prefs.getString("favs", "[]")) } catch (x: Exception) { JSONArray() }
        if (arr.length() == 0) root.addView(text("Nothing saved yet. Tap Favourite on a card.", 16f))
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.getJSONObject(i); val c = o.getJSONObject("card")
            root.addView(text("${o.optString("topic")}: ${c.optString("hook")}", 16f, true))
            root.addView(text("${c.optString("question")}\n${c.optString("answer")}", 14f))
        }
        screen(root)
    }

    // ---------- Settings ----------
    private fun showSettings() {
        val root = vertical()
        root.addView(btn("Back") { showFeed() })
        root.addView(text("Settings", 24f, true))
        root.addView(text("Build v${version()} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", 13f))
        val choices = listOf("Auto") + Net.PROVIDERS
        root.addView(text("Provider (Auto = try every saved key)", 14f))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        spinner.setSelection(choices.indexOf(prefs.getString("provider", "Auto") ?: "Auto").coerceAtLeast(0))
        root.addView(spinner)
        val keyField = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(keyField)
        val out = text("", 14f)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val name = choices[pos]
                prefs.edit().putString("provider", name).apply()
                keyField.isEnabled = name != "Auto"
                keyField.hint = if (name == "Auto") "Auto uses all saved keys" else "$name API key (this device only)"
                keyField.setText(if (name == "Auto") "" else keyFor(name))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(btn("Save key") {
            val name = choices[spinner.selectedItemPosition]
            if (name == "Auto") out.text = "Pick a provider to save its key." else {
                prefs.edit().putString("key_$name", keyField.text.toString().trim()).apply()
                out.text = "$name key saved. Providers with keys: ${Net.PROVIDERS.filter { keyFor(it).isNotEmpty() }}"
            }
        })
        root.addView(btn("Connection test") {
            out.text = "Testing..."
            Thread {
                val t0 = System.currentTimeMillis()
                val msg = try {
                    val (via, r) = fetchText("Reply with exactly: CONNECTION OK")
                    "PASS via $via (${System.currentTimeMillis() - t0} ms): ${r.take(60)}"
                } catch (e: Exception) { "FAIL: ${e.javaClass.simpleName}: ${e.message}" }
                runOnUiThread { out.text = msg }
            }.start()
        })
        root.addView(out)
        val stats = "Signals so far: more ${prefs.getInt("n_more", 0)}, less ${prefs.getInt("n_less", 0)}, " +
            "favourite ${prefs.getInt("n_favourite", 0)}, surprised ${prefs.getInt("n_surprised", 0)}"
        root.addView(text(stats, 13f))
        screen(root)
    }
}
