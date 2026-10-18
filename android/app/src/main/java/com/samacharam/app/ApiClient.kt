package com.samacharam.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

class ApiClient(context: Context) {
    private val vault = SecureStore(context)
    val base: String get() = vault.get("host") ?: "http://10.0.2.2:8787"
    fun configure(host: String) {
        val u = URI(host.trim())
        require(u.host != null && u.userInfo == null && u.query == null && u.fragment == null) { "Enter a host URL without credentials or query." }
        require(u.scheme == "https" || (BuildConfig.DEBUG && u.scheme == "http")) { "Release builds require HTTPS." }
        vault.put("host", host.trim().trimEnd('/')); vault.put("cookie", "")
    }
    @Synchronized fun request(path: String, body: JSONObject? = null): JSONObject {
        require(path.startsWith("/api/"))
        val c = URI(base + path).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 120000; c.instanceFollowRedirects = false
        c.setRequestProperty("X-Samacha", "1")
        vault.get("cookie")?.let { c.setRequestProperty("Cookie", it) }
        try {
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=12_000_000){"Response exceeds cache limit"};output.write(buffer,0,n)}
                output.toByteArray()
            } ?: ByteArray(0)
            require(bytes.size <= 12_000_000) { "Response exceeds cache limit." }
            val data = JSONObject(String(bytes))
            require(status in 200..299) { data.optString("error", "Request failed ($status)") }
            c.getHeaderField("Set-Cookie")?.let { vault.put("cookie", it.substringBefore(';')) }
            return data
        } finally { c.disconnect() }
    }
    fun login(password: String) = request("/api/login", JSONObject().put("password", password))
}
