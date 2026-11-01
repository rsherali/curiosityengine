package com.curiosityengine.app

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.cert.CertificateFactory
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
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
    private val GROQ_STRONG = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b")
    private val GEMINI_STRONG = listOf("gemini-flash-latest")
    private val GEMINI_MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest")
    private val OPENROUTER_MODELS = listOf("openrouter/free", "meta-llama/llama-3.3-70b-instruct:free")

    // Root certificates missing from old Android trust stores (Android 4.4 predates Let's Encrypt's ISRG roots,
    // which Wikipedia uses). Merged with the system roots so every other site keeps working.
    private val ISRG_X1 = """-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----"""
    private val ISRG_X2 = """-----BEGIN CERTIFICATE-----
MIICGzCCAaGgAwIBAgIQQdKd0XLq7qeAwSxs6S+HUjAKBggqhkjOPQQDAzBPMQsw
CQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2gg
R3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMjAeFw0yMDA5MDQwMDAwMDBaFw00
MDA5MTcxNjAwMDBaME8xCzAJBgNVBAYTAlVTMSkwJwYDVQQKEyBJbnRlcm5ldCBT
ZWN1cml0eSBSZXNlYXJjaCBHcm91cDEVMBMGA1UEAxMMSVNSRyBSb290IFgyMHYw
EAYHKoZIzj0CAQYFK4EEACIDYgAEzZvVn4CDCuwJSvMWSj5cz3es3mcFDR0HttwW
+1qLFNvicWDEukWVEYmO6gbf9yoWHKS5xcUy4APgHoIYOIvXRdgKam7mAHf7AlF9
ItgKbppbd9/w+kHsOdx1ymgHDB/qo0IwQDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0T
AQH/BAUwAwEB/zAdBgNVHQ4EFgQUfEKWrt5LSDv6kviejM9ti6lyN5UwCgYIKoZI
zj0EAwMDaAAwZQIwe3lORlCEwkSHRhtFcP9Ymd70/aTSVaYgLXTWNLxBo1BfASdW
tL4ndQavEi51mI38AjEAi/V3bNTIZargCyzuFJ0nN6T5U6VR5CmD1/iQMVtCnwr1
/q4AaOeMSQ+2b1tbFfLn
-----END CERTIFICATE-----"""
    private val EXTRA_ROOTS = listOf(ISRG_X1, ISRG_X2)

    private val mergedTrust: TrustManagerFactory by lazy {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        try {
            val sys = KeyStore.getInstance("AndroidCAStore")
            sys.load(null, null)
            val en = sys.aliases()
            while (en.hasMoreElements()) {
                val a = en.nextElement()
                val cert = sys.getCertificate(a)
                if (cert != null) ks.setCertificateEntry("sys_$a", cert)
            }
        } catch (e: Exception) { /* fall back to extra roots only */ }
        val cf = CertificateFactory.getInstance("X.509")
        var i = 0
        for (pem in EXTRA_ROOTS) {
            ks.setCertificateEntry("extra_$i", cf.generateCertificate(ByteArrayInputStream(pem.toByteArray())))
            i++
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        tmf
    }

    private fun open(url: String, conscrypt: Boolean): HttpsURLConnection {
        val c = URL(url).openConnection() as HttpsURLConnection
        if (conscrypt) {
            // Modern TLS stack bundled in the app (used when the OS stack fails the handshake)
            val sc = SSLContext.getInstance("TLS", Conscrypt.newProvider())
            sc.init(null, mergedTrust.trustManagers, null)
            c.sslSocketFactory = sc.socketFactory
        } else if (Build.VERSION.SDK_INT < 21) {
            val sc = SSLContext.getInstance("TLSv1.2")
            sc.init(null, mergedTrust.trustManagers, null)
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

    private fun gemini(key: String, prompt: String, needJson: Boolean, temp: Double, models: List<String>): String {
        val req = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", temp))
        return tryModels(models, needJson, { m ->
            postJson("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
                mapOf("x-goog-api-key" to key), req.toString())
        }, { t ->
            JSONObject(t).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        })
    }

    private fun openAiStyle(url: String, key: String, models: List<String>, prompt: String, needJson: Boolean, temp: Double): String {
        try {
            return tryModels(models, needJson, { m ->
                val req = JSONObject().put("model", m).put("max_tokens", 2000).put("temperature", temp)
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
    fun ask(provider: String, key: String, prompt: String, needJson: Boolean = false, temp: Double = 0.7, strong: Boolean = false): String = when (provider) {
        "Gemini" -> gemini(key, prompt, needJson, temp, if (strong) GEMINI_STRONG else GEMINI_MODELS)
        "Groq" -> openAiStyle("https://api.groq.com/openai/v1/chat/completions", key, if (strong) GROQ_STRONG else GROQ_MODELS, prompt, needJson, temp)
        "OpenRouter" -> openAiStyle("https://openrouter.ai/api/v1/chat/completions", key, OPENROUTER_MODELS, prompt, needJson, temp)
        else -> throw RuntimeException("Unknown provider $provider")
    }

    /** Auto: tries every provider that has a key, in order. Returns Pair(providerUsed, text). */
    fun askAuto(keys: Map<String, String>, prompt: String, needJson: Boolean = false, temp: Double = 0.7, strong: Boolean = false, avoid: String? = null): Pair<String, String> {
        val errors = StringBuilder()
        val order = if (avoid == null) PROVIDERS else PROVIDERS.filter { it != avoid } + PROVIDERS.filter { it == avoid }
        for (p in order) {
            val k = keys[p] ?: ""
            if (k.isEmpty()) continue
            try { return Pair(p, ask(p, k, prompt, needJson, temp, strong)) }
            catch (e: Exception) { errors.append("$p: ${e.javaClass.simpleName}: ${e.message}\n") }
        }
        throw RuntimeException(if (errors.isEmpty()) "No keys saved." else "All providers failed:\n$errors")
    }

    /** GET with TLS fallback. Throws unless the server answers 200. Wikimedia asks for a descriptive User-Agent. */
    fun httpGet(url: String): String {
        fun once(conscrypt: Boolean): String {
            val c = open(url, conscrypt)
            c.setRequestProperty("User-Agent", "CuriosityEngine/0.1 (personal learning app on Android 4.4)")
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val st = if (code < 400) c.inputStream else c.errorStream
            val text = st?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: ""
            c.disconnect()
            if (code != 200) throw RuntimeException("HTTP $code from ${URL(url).host}")
            return text
        }
        return try { once(false) } catch (e: javax.net.ssl.SSLException) { once(true) }
    }

    /** Intro text of an English Wikipedia article: Pair(resolved title, text), or null if missing or ambiguous. */
    fun wikiExtract(title: String): Pair<String, String>? {
        if (title.isBlank()) return null
        val u = "https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&redirects=1&format=json&titles=" +
            URLEncoder.encode(title, "UTF-8")
        val pages = JSONObject(httpGet(u)).getJSONObject("query").getJSONObject("pages")
        val key = pages.keys().next()
        if (key == "-1") return null
        val pg = pages.getJSONObject(key)
        val ex = pg.optString("extract").trim()
        if (ex.length < 250 || ex.contains("may refer to")) return null
        return Pair(pg.optString("title", title), ex.take(3500))
    }

    /** Top search hit title on English Wikipedia, or null. */
    fun wikiSearch(q: String): String? {
        val u = "https://en.wikipedia.org/w/api.php?action=query&list=search&srlimit=1&format=json&srsearch=" + URLEncoder.encode(q, "UTF-8")
        val arr = JSONObject(httpGet(u)).getJSONObject("query").getJSONArray("search")
        return if (arr.length() > 0) arr.getJSONObject(0).optString("title") else null
    }

    /** Pulls the JSON object out of text that may include code fences or chatter. */
    fun extractJson(t: String): JSONObject {
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) throw RuntimeException("No JSON in reply: ${t.take(120)}")
        return JSONObject(t.substring(a, b + 1))
    }
}
