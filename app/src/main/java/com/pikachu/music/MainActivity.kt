package com.pikachu.music

import android.os.Bundle
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private val startUrl = "https://appassets.androidplatform.net/assets/index.html#home"
    private val audioCache by lazy { AudioCache(filesDir) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.mediaPlaybackRequiresUserGesture = false

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val uri = request.url
                val path = uri.path.orEmpty()
                if (uri.host == "appassets.androidplatform.net" && path.startsWith("/cache/")) {
                    return audioCache.handle(request)
                }
                return assetLoader.shouldInterceptRequest(uri)
            }
        }

        setContentView(webView)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        val url = webView.url.orEmpty()
                        val fragment = url.substringAfter('#', "")
                        if (fragment.isNotEmpty() && fragment != "home") {
                            webView.loadUrl(startUrl)
                            return
                        }
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        if (savedInstanceState == null) {
            webView.loadUrl(startUrl)
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}

private class AudioCache(private val baseDir: File) {
    private val cacheDir = File(baseDir, "audio_cache")
    private val locks = ConcurrentHashMap<String, Any>()

    fun handle(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val path = uri.path.orEmpty()
        if (!path.startsWith("/cache/audio")) return null
        val originUrl = uri.getQueryParameter("url")?.trim().orEmpty()
        if (originUrl.isEmpty() || !(originUrl.startsWith("http://") || originUrl.startsWith("https://"))) {
            return errorResponse(400, "Bad Request")
        }

        if (!cacheDir.exists()) cacheDir.mkdirs()

        val ext = guessExtension(originUrl)
        val key = sha256(originUrl)
        val file = File(cacheDir, "$key.$ext")

        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            if (!file.exists() || file.length() <= 0L) {
                val tmp = File(cacheDir, "$key.$ext.tmp")
                if (tmp.exists()) tmp.delete()
                val ok = downloadToFile(originUrl, tmp, request.requestHeaders)
                if (!ok) {
                    if (file.exists() && file.length() > 0L) {
                        return fileResponse(file, ext, request.requestHeaders)
                    }
                    return errorResponse(502, "Download Failed")
                }
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return errorResponse(500, "Cache Write Failed")
                }
            }
        }

        return fileResponse(file, ext, request.requestHeaders)
    }

    private fun fileResponse(file: File, ext: String, headers: Map<String, String>): WebResourceResponse {
        val mime = guessMime(ext)
        val length = file.length()
        val range = headers.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
        if (range.isNullOrBlank()) {
            val stream = BufferedInputStream(FileInputStream(file))
            val resp = WebResourceResponse(mime, "utf-8", stream)
            resp.setStatusCodeAndReasonPhrase(200, "OK")
            resp.responseHeaders = mapOf(
                "Accept-Ranges" to "bytes",
                "Content-Length" to length.toString(),
            )
            return resp
        }

        val parsed = parseRange(range, length) ?: return errorResponse(416, "Range Not Satisfiable")
        val (start, end) = parsed
        val chunkLen = end - start + 1
        val fis = FileInputStream(file)
        skipFully(fis, start)
        val bounded: InputStream = BoundedInputStream(BufferedInputStream(fis), chunkLen)

        val resp = WebResourceResponse(mime, "utf-8", bounded)
        resp.setStatusCodeAndReasonPhrase(206, "Partial Content")
        resp.responseHeaders = mapOf(
            "Accept-Ranges" to "bytes",
            "Content-Range" to "bytes $start-$end/$length",
            "Content-Length" to chunkLen.toString(),
        )
        return resp
    }

    private fun errorResponse(code: Int, message: String): WebResourceResponse {
        val stream = ByteArrayInputStream(message.toByteArray(Charsets.UTF_8))
        val resp = WebResourceResponse("text/plain", "utf-8", stream)
        resp.setStatusCodeAndReasonPhrase(code, message)
        resp.responseHeaders = mapOf("Content-Type" to "text/plain; charset=utf-8")
        return resp
    }

    private fun downloadToFile(url: String, outFile: File, reqHeaders: Map<String, String>): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                useCaches = true

                val ua = reqHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                if (!ua.isNullOrBlank()) setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Connection", "close")
            }

            val code = conn.responseCode
            if (code !in 200..299) return false

            conn.inputStream.use { input ->
                BufferedInputStream(input).use { bis ->
                    BufferedOutputStream(outFile.outputStream()).use { bos ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = bis.read(buf)
                            if (n <= 0) break
                            bos.write(buf, 0, n)
                        }
                        bos.flush()
                    }
                }
            }
            outFile.length() > 0L
        } catch (_: Throwable) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun guessExtension(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val last = clean.substringAfterLast('/', "")
        val ext = last.substringAfterLast('.', "").lowercase(Locale.US).trim()
        if (ext.isBlank()) return "mp3"
        if (ext.length > 8) return "mp3"
        if (!ext.all { it.isLetterOrDigit() }) return "mp3"
        return ext
    }

    private fun guessMime(ext: String): String {
        val e = ext.lowercase(Locale.US)
        val mt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e)
        if (!mt.isNullOrBlank()) return mt
        return when (e) {
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun parseRange(rangeHeader: String, total: Long): Pair<Long, Long>? {
        val v = rangeHeader.trim()
        if (!v.startsWith("bytes=", ignoreCase = true)) return null
        val part = v.substringAfter('=').trim()
        val dash = part.indexOf('-')
        if (dash <= 0) return null
        val startStr = part.substring(0, dash).trim()
        val endStr = part.substring(dash + 1).trim()
        val start = startStr.toLongOrNull() ?: return null
        if (start < 0 || start >= total) return null
        val end = if (endStr.isBlank()) (total - 1) else (endStr.toLongOrNull() ?: return null)
        val clampedEnd = minOf(end, total - 1)
        if (clampedEnd < start) return null
        return start to clampedEnd
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() == -1) break
                remaining -= 1
            }
        }
    }
}

private class BoundedInputStream(
    private val input: InputStream,
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = input.read()
        if (b >= 0) remaining -= 1
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val n = input.read(b, off, toRead)
        if (n > 0) remaining -= n.toLong()
        return n
    }

    override fun close() {
        input.close()
    }
}
