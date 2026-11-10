package com.finapp.daily.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 极简 HTTP 层。故意用 HttpURLConnection 而不是 OkHttp —— 少一个依赖，
 * 少一次版本冲突的机会。这个 App 的请求量很小，够用。
 */
object Http {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val TIMEOUT_MS = 15000
    private const val RETRIES = 2

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        charset: String = "UTF-8",
        timeoutMs: Int = TIMEOUT_MS
    ): String {
        var lastError: Exception? = null
        repeat(RETRIES + 1) { attempt ->
            try {
                val conn = open(url, "GET", headers, timeoutMs)
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw RuntimeException("HTTP $code")
                    return readAll(conn, charset)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < RETRIES) Thread.sleep(600L * (attempt + 1))
            }
        }
        throw lastError ?: RuntimeException("请求失败: $url")
    }

    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        charset: String = "UTF-8",
        timeoutMs: Int = TIMEOUT_MS
    ): String {
        val body = form.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        var lastError: Exception? = null
        repeat(RETRIES + 1) { attempt ->
            try {
                val hdrs = headers + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                )
                val conn = open(url, "POST", hdrs, timeoutMs).apply {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw RuntimeException("HTTP $code")
                    return readAll(conn, charset)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < RETRIES) Thread.sleep(600L * (attempt + 1))
            }
        }
        throw lastError ?: RuntimeException("请求失败: $url")
    }

    private fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
        timeoutMs: Int
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun readAll(conn: HttpURLConnection, charset: String): String {
        val stream = conn.inputStream
        return BufferedReader(InputStreamReader(stream, charset)).use { it.readText() }
    }
}
