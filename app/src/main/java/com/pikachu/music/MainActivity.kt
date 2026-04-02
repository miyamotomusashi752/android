package com.pikachu.music

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private var pendingDownload: PendingDownload? = null

    private data class PendingDownload(
        val url: String,
        val title: String,
        val artist: String,
        val referer: String,
    )

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

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(NativeBridge(), "Native")

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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_WRITE_STORAGE) return
        val granted =
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        val pending = pendingDownload
        pendingDownload = null
        if (!granted || pending == null) {
            Toast.makeText(this, "下载已取消", Toast.LENGTH_SHORT).show()
            return
        }
        startExportDownload(pending.url, pending.title, pending.artist, pending.referer)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun startExportDownload(url: String, title: String, artist: String, referer: String) {
        val originUrl = url.trim()
        if (originUrl.isEmpty() || !(originUrl.startsWith("http://") || originUrl.startsWith("https://"))) {
            Toast.makeText(this, "下载链接无效", Toast.LENGTH_SHORT).show()
            return
        }
        val ref = referer.trim()

        val userAgent = try {
            webView.settings.userAgentString
        } catch (_: Throwable) {
            null
        }

        Toast.makeText(this, "开始准备下载…", Toast.LENGTH_SHORT).show()
        Thread {
            val entry = audioCache.ensureCached(originUrl, userAgent, ref)
            if (entry == null || !entry.file.exists() || entry.file.length() <= 0L) {
                runOnUiThread {
                    Toast.makeText(this, "下载失败（音源可能限制访问）", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }

            val baseName = buildFileBaseName(title, artist).ifBlank {
                URLUtil.guessFileName(originUrl, null, entry.mime ?: guessMimeByExt(entry.ext))
                    .substringBeforeLast('.')
            }
            val displayName = "$baseName.${entry.ext}"
            val ok = exportToDownloads(entry.file, entry.mime ?: guessMimeByExt(entry.ext), displayName)
            runOnUiThread {
                Toast.makeText(this, if (ok) "已保存到下载目录：$displayName" else "保存到下载目录失败", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun exportToDownloads(input: File, mime: String, displayName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PikachuMusic")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri =
                    contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return false
                val ok = try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        input.inputStream().use { it.copyTo(out) }
                    } ?: return false
                    true
                } catch (_: Throwable) {
                    false
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                try {
                    contentResolver.update(uri, values, null, null)
                } catch (_: Throwable) {
                }
                ok
            } else {
                val granted =
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return false
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, displayName)
                input.copyTo(outFile, overwrite = true)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildFileBaseName(title: String, artist: String): String {
        val t = title.trim()
        val a = artist.trim()
        val raw = when {
            t.isNotEmpty() && a.isNotEmpty() -> "$t - $a"
            t.isNotEmpty() -> t
            a.isNotEmpty() -> a
            else -> ""
        }
        return raw
            .replace(Regex("""[\\/:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(120)
    }

    private fun guessMimeByExt(ext: String): String {
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

    private inner class NativeBridge {
        @JavascriptInterface
        fun download(url: String, title: String, artist: String, referer: String) {
            runOnUiThread {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val granted =
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        pendingDownload = PendingDownload(url, title, artist, referer)
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                            REQUEST_WRITE_STORAGE,
                        )
                        return@runOnUiThread
                    }
                }
                startExportDownload(url, title, artist, referer)
            }
        }
    }

    private companion object {
        private const val REQUEST_WRITE_STORAGE = 1001
    }
}

private class AudioCache(private val baseDir: File) {
    private val cacheDir = File(baseDir, "audio_cache")
    private val locks = ConcurrentHashMap<String, Any>()
    private data class DownloadInfo(val ok: Boolean, val mime: String?)
    data class CacheEntry(
        val file: File,
        val ext: String,
        val mime: String?,
    )

    fun handle(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val path = uri.path.orEmpty()
        if (!path.startsWith("/cache/audio")) return null
        val originUrl = uri.getQueryParameter("url")?.trim().orEmpty()
        val referer = uri.getQueryParameter("ref")?.trim().orEmpty()
        if (originUrl.isEmpty() || !(originUrl.startsWith("http://") || originUrl.startsWith("https://"))) {
            return errorResponse(400, "Bad Request")
        }

        if (!cacheDir.exists()) cacheDir.mkdirs()

        val ext = guessExtension(originUrl)
        val key = sha256(originUrl)
        val file = File(cacheDir, "$key.$ext")
        val meta = File(cacheDir, "$key.meta")

        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            if (!file.exists() || file.length() <= 0L) {
                val tmp = File(cacheDir, "$key.$ext.tmp")
                if (tmp.exists()) tmp.delete()
                val headers = mergeHeaders(request.requestHeaders, referer)
                val info = downloadToFile(originUrl, tmp, headers)
                if (!info.ok) {
                    if (file.exists() && file.length() > 0L) {
                        return fileResponse(file, ext, meta, request.requestHeaders)
                    }
                    return errorResponse(502, "Download Failed")
                }
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return errorResponse(500, "Cache Write Failed")
                }
                val mime = sanitizeMime(info.mime)
                if (!mime.isNullOrBlank()) {
                    try {
                        meta.writeText(mime, Charsets.UTF_8)
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        return fileResponse(file, ext, meta, request.requestHeaders)
    }

    fun ensureCached(originUrl: String, userAgent: String?, referer: String?): CacheEntry? {
        val cleanUrl = originUrl.trim()
        if (cleanUrl.isEmpty() || !(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://"))) return null
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val ref = referer?.trim().orEmpty()

        val ext = guessExtension(cleanUrl)
        val key = sha256(cleanUrl)
        val file = File(cacheDir, "$key.$ext")
        val meta = File(cacheDir, "$key.meta")

        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            if (!file.exists() || file.length() <= 0L) {
                val tmp = File(cacheDir, "$key.$ext.tmp")
                if (tmp.exists()) tmp.delete()
                val headers = buildHeaders(userAgent, ref)
                val info = downloadToFile(cleanUrl, tmp, headers)
                if (!info.ok) return null
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return null
                }
                val mime = sanitizeMime(info.mime)
                if (!mime.isNullOrBlank()) {
                    try {
                        meta.writeText(mime, Charsets.UTF_8)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        val mime = readMime(meta)
        return CacheEntry(file, ext, mime)
    }

    private fun fileResponse(file: File, ext: String, meta: File, headers: Map<String, String>): WebResourceResponse {
        val mime = readMime(meta) ?: guessMime(ext)
        val length = file.length()
        val range = headers.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
        if (range.isNullOrBlank()) {
            val stream = BufferedInputStream(FileInputStream(file))
            val resp = WebResourceResponse(mime, null, stream)
            resp.setStatusCodeAndReasonPhrase(200, "OK")
            resp.responseHeaders = mapOf(
                "Accept-Ranges" to "bytes",
                "Content-Length" to length.toString(),
                "Content-Type" to mime,
            )
            return resp
        }

        val parsed = parseRange(range, length) ?: return errorResponse(416, "Range Not Satisfiable")
        val (start, end) = parsed
        val chunkLen = end - start + 1
        val fis = FileInputStream(file)
        skipFully(fis, start)
        val bounded: InputStream = BoundedInputStream(BufferedInputStream(fis), chunkLen)

        val resp = WebResourceResponse(mime, null, bounded)
        resp.setStatusCodeAndReasonPhrase(206, "Partial Content")
        resp.responseHeaders = mapOf(
            "Accept-Ranges" to "bytes",
            "Content-Range" to "bytes $start-$end/$length",
            "Content-Length" to chunkLen.toString(),
            "Content-Type" to mime,
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

    private fun downloadToFile(url: String, outFile: File, reqHeaders: Map<String, String>): DownloadInfo {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                useCaches = true

                val ua = reqHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                if (!ua.isNullOrBlank()) setRequestProperty("User-Agent", ua)
                val referer =
                    reqHeaders.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                if (!referer.isNullOrBlank()) {
                    setRequestProperty("Referer", referer)
                    val origin = originFromReferer(referer)
                    if (!origin.isNullOrBlank()) setRequestProperty("Origin", origin)
                }
                val cookie =
                    reqHeaders.entries.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value
                        ?: CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Connection", "close")
            }

            val code = conn.responseCode
            if (code !in 200..299) return DownloadInfo(false, null)
            val mime = conn.contentType

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
            DownloadInfo(outFile.length() > 0L, mime)
        } catch (_: Throwable) {
            DownloadInfo(false, null)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun buildHeaders(userAgent: String?, referer: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (!userAgent.isNullOrBlank()) out["User-Agent"] = userAgent
        if (referer.isNotBlank()) out["Referer"] = referer
        return out
    }

    private fun mergeHeaders(headers: Map<String, String>, referer: String): Map<String, String> {
        if (referer.isBlank()) return headers
        val out = LinkedHashMap<String, String>()
        headers.forEach { (k, v) -> out[k] = v }
        out["Referer"] = referer
        return out
    }

    private fun originFromReferer(referer: String): String? {
        return try {
            val u = URL(referer)
            u.protocol + "://" + u.host + (if (u.port > 0 && u.port != u.defaultPort) ":" + u.port else "")
        } catch (_: Throwable) {
            null
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

    private fun readMime(meta: File): String? {
        return try {
            if (!meta.exists()) return null
            val s = meta.readText(Charsets.UTF_8).trim()
            sanitizeMime(s)
        } catch (_: Throwable) {
            null
        }
    }

    private fun sanitizeMime(mime: String?): String? {
        val raw = mime?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val v = raw.substringBefore(';').trim()
        if (v.isEmpty()) return null
        if (!v.contains('/')) return null
        return v
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
