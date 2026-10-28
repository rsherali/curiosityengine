package com.curiosityengine.app

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore
import org.conscrypt.Conscrypt

/** Forces TLS 1.1/1.2 on sockets. Android 4.4 supports TLS 1.2 but leaves it off by default. */
class Tls12SocketFactory(private val d: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        fix(d.createSocket(s, host, port, autoClose))
    override fun createSocket(host: String, port: Int): Socket = fix(d.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        fix(d.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = fix(d.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        fix(d.createSocket(address, port, localAddress, localPort))

    private fun fix(s: Socket): Socket {
        if (s is SSLSocket) s.enabledProtocols = arrayOf("TLSv1.1", "TLSv1.2")
        return s
    }
}

object Net {
    /** Provider ids. Model names are unverified aliases; each list is tried in order. */
    val PROVIDERS = listOf("Groq", "Gemini", "OpenRouter")

    private val GROQ_MODELS = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-20b", "openai/gpt-oss-120b", "llama-3.1-8b-instant")
    private val GEMINI_MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest")
    private val OPENROUTER_MODELS = listOf("openrouter/free", "meta-llama/llama-3.3-70b-instruct:free")

    private fun open(url: String, conscrypt: Boolean): HttpsURLConnection {
        val c = URL(url).openConnection() as HttpsURLConnection
        if (conscrypt) {
            // Modern TLS stack bundled in the app (used when the OS stack fails the handshake)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val sc = SSLContext.getInstance("TLS", Conscrypt.newProvider())
            sc.init(null, tmf.trustManagers, null)
            c.sslSocketFactory = sc.socketFactory
        } else if (Build.VERSION.SDK_INT < 21) {
            val sc = SSLContext.getInstance("TLSv1.2")
            sc.init(null, null, null)
            c.sslSocketFactory = Tls12SocketFactory(sc.socketFactory)
        }
        c.connectTimeout = 15000
        c.readTimeout = 60000
        return c
    }

    private fun doPost(url: String, headers: Map<String, String>, body: String, conscrypt: Boolean): Pair<Int, String> {
        val c = open(url, conscrypt)
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
        } ?: ""
        c.disconnect()
        return Pair(code, text)
    }

    /** Returns Pair(httpCode, bodyText). Tries the OS TLS first; on handshake failure retries with Conscrypt. */
    fun postJson(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        try {
            return doPost(url, headers, body, false)
        } catch (e: javax.net.ssl.SSLException) {
            try {
                return doPost(url, headers, body, true)
            } catch (e2: Throwable) {
                throw RuntimeException("OS TLS: ${e.message} | Conscrypt: ${e2.javaClass.simpleName}: ${e2.message}")
            }
        }
    }

    /** GET helper (OS TLS first, then Conscrypt). */
    private fun getText(url: String, headers: Map<String, String>): String {
        fun once(conscrypt: Boolean): String {
            val c = open(url, conscrypt)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            val st = if (code < 400) c.inputStream else c.errorStream
            val t = st?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: ""
            c.disconnect()
            return t
        }
        return try { once(false) } catch (e: javax.net.ssl.SSLException) { once(true) }
    }

    /** Tries each model with retries on busy errors. call(model) returns (code, body). */
    private fun tryModels(models: List<String>, needJson: Boolean, call: (String) -> Pair<Int, String>, extract: (String) -> String): String {
        var last = "no attempt"
        for (model in models) {
            for (attempt in 1..2) {
                val (code, text) = call(model)
                if (code == 200) {
                    val out = try { extract(text) } catch (e: Exception) { "" }
                    val ok = out.isNotBlank() && (!needJson || try { extractJson(out); true } catch (e: Exception) { false })
                    if (ok) return out
                    last = "$model replied but empty or no valid JSON (first 80 chars: ${out.take(80)})"
                    break                                   // try the next model
                }
                last = "$model HTTP $code: ${text.take(160)}"
                if (code == 404 || code == 400) break          // bad model name: next model
                if (code != 429 && code != 500 && code != 503) throw RuntimeException(last)
                Thread.sleep(2000L * attempt)
            }
        }
        throw RuntimeException(last)
    }

    private fun gemini(key: String, prompt: String, needJson: Boolean): String {
        val req = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        return tryModels(GEMINI_MODELS, needJson, { m ->
            postJson("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
                mapOf("x-goog-api-key" to key), req.toString())
        }, { t ->
            JSONObject(t).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        })
    }

    private fun openAiStyle(url: String, key: String, models: List<String>, prompt: String, needJson: Boolean): String {
        try {
            return tryModels(models, needJson, { m ->
                val req = JSONObject().put("model", m).put("max_tokens", 2000)
                if (m.startsWith("openai/gpt-oss")) req.put("reasoning_effort", "low")
                req.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                postJson(url, mapOf("Authorization" to "Bearer $key"), req.toString())
            }, { t ->
                JSONObject(t).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            })
        } catch (e: RuntimeException) {
            if (e.message?.contains("HTTP 404") == true || e.message?.contains("HTTP 400") == true) {
                // Model names are stale: show what this key can actually use
                val ids = try {
                    val arr = JSONObject(getText(url.replace("/chat/completions", "/models"), mapOf("Authorization" to "Bearer $key")))
                        .getJSONArray("data")
                    (0 until arr.length()).joinToString(", ") { arr.getJSONObject(it).getString("id") }
                } catch (e2: Exception) { "(could not list models: ${e2.message})" }
                throw RuntimeException("${e.message}\nAvailable models: $ids")
            }
            throw e
        }
    }

    /** Ask one provider. Returns raw model text. */
    fun ask(provider: String, key: String, prompt: String, needJson: Boolean = false): String = when (provider) {
        "Gemini" -> gemini(key, prompt, needJson)
        "Groq" -> openAiStyle("https://api.groq.com/openai/v1/chat/completions", key, GROQ_MODELS, prompt, needJson)
        "OpenRouter" -> openAiStyle("https://openrouter.ai/api/v1/chat/completions", key, OPENROUTER_MODELS, prompt, needJson)
        else -> throw RuntimeException("Unknown provider $provider")
    }

    /** Auto: tries every provider that has a key, in order. Returns Pair(providerUsed, text). */
    fun askAuto(keys: Map<String, String>, prompt: String, needJson: Boolean = false): Pair<String, String> {
        val errors = StringBuilder()
        for (p in PROVIDERS) {
            val k = keys[p] ?: ""
            if (k.isEmpty()) continue
            try { return Pair(p, ask(p, k, prompt, needJson)) }
            catch (e: Exception) { errors.append("$p: ${e.javaClass.simpleName}: ${e.message}\n") }
        }
        throw RuntimeException(if (errors.isEmpty()) "No keys saved." else "All providers failed:\n$errors")
    }

    /** Pulls the JSON object out of text that may include code fences or chatter. */
    fun extractJson(t: String): JSONObject {
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) throw RuntimeException("No JSON in reply: ${t.take(120)}")
        return JSONObject(t.substring(a, b + 1))
    }
}
