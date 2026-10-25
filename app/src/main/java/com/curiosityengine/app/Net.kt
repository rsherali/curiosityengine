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
    const val MODEL = "gemini-flash-latest"

    private fun open(url: String): HttpsURLConnection {
        val c = URL(url).openConnection() as HttpsURLConnection
        if (Build.VERSION.SDK_INT < 21) {
            val sc = SSLContext.getInstance("TLSv1.2")
            sc.init(null, null, null)
            c.sslSocketFactory = Tls12SocketFactory(sc.socketFactory)
        }
        c.connectTimeout = 15000
        c.readTimeout = 60000
        return c
    }

    /** Returns Pair(httpCode, bodyText). Throws on network/TLS failure. */
    fun postJson(url: String, apiKey: String, body: String): Pair<Int, String> {
        val c = open(url)
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", apiKey)
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

    /** Calls Gemini and returns the model's text. */
    fun gemini(apiKey: String, prompt: String, json: Boolean): String {
        val req = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        if (json) req.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
        val (code, text) = postJson(url, apiKey, req.toString())
        if (code != 200) throw RuntimeException("HTTP $code: ${text.take(300)}")
        return JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }
}
