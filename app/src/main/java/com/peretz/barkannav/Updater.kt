package com.peretz.barkannav

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Updates are published as GitHub releases in the public repository
 * pbarkan-coder/barkan-nav.
 *
 * Before installing, the downloaded APK must have this app's package name,
 * a higher version and exactly the same signing certificate as the installed
 * app. Android enforces the same signature check again at install time.
 */
object Updater {
    private const val REPO = "pbarkan-coder/barkan-nav"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val MAX_BYTES = 80L * 1024 * 1024
    private val TAG_RE = Regex("""v1\.0\.(\d{1,7})""")

    data class Info(val versionCode: Int, val versionName: String, val url: String, val notes: String)

    private fun open(url: String): HttpsURLConnection {
        val conn = URL(url).openConnection() as? HttpsURLConnection
            ?: throw IllegalStateException("HTTPS only")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "BarkanNav")
        return conn
    }

    /** Returns the newer release, or null when the installed version is current. */
    fun check(): Info? {
        val conn = open(LATEST)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = conn.inputStream.bufferedReader().use { it.readText().take(500_000) }
            val obj = JSONObject(text)
            val tag = obj.optString("tag_name")
            val code = TAG_RE.matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (code <= BuildConfig.VERSION_CODE) return null
            val assets = obj.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val url = a.optString("browser_download_url")
                if (a.optString("name").endsWith(".apk") && url.startsWith(DOWNLOAD_PREFIX)) {
                    return Info(code, tag.removePrefix("v"), url, obj.optString("body").take(1000))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    fun download(ctx: Context, info: Info): File {
        require(info.url.startsWith(DOWNLOAD_PREFIX))
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "BarkanNav-${info.versionName}.apk")
        val conn = open(info.url)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("download failed")
            if (conn.url.protocol != "https") error("not https")
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) error("file too large")
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        return target
    }

    @Suppress("DEPRECATION")
    private fun signatures(pm: PackageManager, info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val s = info.signingInfo ?: return emptySet()
            if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else {
            info.signatures
        }) ?: return emptySet()
        return sigs.map { it.toCharsString() }.toSet()
    }

    /** True only for our package, a newer version, and a signer of the installed app. */
    @Suppress("DEPRECATION")
    fun verify(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        if (archive.packageName != ctx.packageName) return false
        val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        if (newCode <= BuildConfig.VERSION_CODE) return false
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        val mine = signatures(pm, installed)
        val theirs = signatures(pm, archive)
        return mine.isNotEmpty() && theirs.isNotEmpty() && theirs.any { it in mine }
    }

    /** Returns false when the user first has to allow installs from this app. */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return true
    }
}
