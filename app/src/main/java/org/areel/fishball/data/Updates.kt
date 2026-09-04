package org.areel.fishball.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.areel.fishball.BuildConfig
import java.io.File

/**
 * What the site is publishing, as it is published.
 *
 * [versionCode] is what decides, not [versionName]: the name is for the person reading the
 * modal and can say anything, and comparing "0.1a" to "0.10a" as text is how an update stops
 * arriving three releases later.
 */
@Serializable
data class Release(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    /** What changed, in the product's own voice. Shown verbatim; keep it to a few lines. */
    val notes: String = "",
    /** Bytes, for the modal. Zero means the manifest did not say. */
    val size: Long = 0,
) {
    /** Newer than the build reading it, and installable. Compared on the code, never the name. */
    fun isNewer(): Boolean = versionCode > BuildConfig.VERSION_CODE && url.isNotBlank()
}

/**
 * Checking whether a newer FishBall exists, and handing one to the installer.
 *
 * Deliberately not a library and deliberately not a service. There is one APK, on one site,
 * for one family — Play Store machinery, background workers and delta patching would all be
 * more moving parts than the thing they update.
 *
 * The check is silent on every failure. An update is a nicety; a person who opened the app to
 * ask a question should never be shown a network error raised by something they did not ask
 * for. Nothing here throws into the UI.
 */
class Updates(private val context: Context) {

    private val http by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The published release, if it is newer than this build. Null for everything else —
     * up to date, unreachable, malformed, or a manifest that has not been put there yet.
     */
    suspend fun check(): Release? = published()?.takeIf { it.isNewer() }

    /**
     * What the site is publishing, whatever version it is. Null only when it could not be read.
     *
     * The launch check has no use for the distinction - up to date and unreachable both mean
     * nothing to say - but somebody who opened Settings and pressed 检查更新 asked a question,
     * and 「已经是最新版」 and 「这会儿连不上」 are different answers to it.
     */
    suspend fun published(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val body = http.get(BuildConfig.UPDATE_MANIFEST_URL).bodyAsText()
            json.decodeFromString<Release>(body)
        }.getOrNull()
    }

    /**
     * Fetch the installer, reporting progress in 0..1.
     *
     * Written to the cache directory, so a download interrupted half way costs nothing and the
     * system can reclaim it. [onProgress] gets -1f while the server declines to say how large
     * the file is, which the modal draws as an indeterminate bar rather than a lying one.
     */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Named for the version, so a stale part-file from an earlier attempt at a
            // different version is never mistaken for this one.
            val target = File(dir, "fishball-${release.versionCode}.apk")
            val part = File(dir, "fishball-${release.versionCode}.apk.part")

            runCatching {
                part.delete()
                http.prepareGet(release.url).execute { response ->
                    val total = release.size.takeIf { it > 0 }
                        ?: response.headers["Content-Length"]?.toLongOrNull()
                        ?: 0L
                    val channel = response.bodyAsChannel()
                    part.outputStream().use { out ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER)
                        var read = 0L
                        while (!channel.isClosedForRead) {
                            val n = channel.readAvailable(buffer, 0, buffer.size)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            read += n
                            onProgress(if (total > 0) (read.toFloat() / total) else -1f)
                        }
                    }
                }
                // Renamed only once it is whole. Anything that reads the cache directory sees
                // either a complete APK or a .part, never a truncated one wearing the real name.
                target.delete()
                // Spelled out rather than kotlin.check(), which reads as a call to this class's
                // own check() at a glance and is one refactor away from being one.
                if (!part.renameTo(target)) error("could not finish ${target.name}")
                target
            }.onFailure { part.delete() }.getOrNull()
        }

    /**
     * Hand the APK to the system installer.
     *
     * Since Android 8 this needs the user's per-app permission to install from an unknown
     * source, and there is no way to ask for it inline — [canInstall] reports it and
     * [openInstallPermission] takes them to the one screen where it can be granted. Sending
     * the install intent without it opens a dialog that simply fails, which reads as a broken
     * app rather than a missing permission.
     */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            // The installer is a different app reading a file in ours, so the grant travels
            // with the intent; without it the URI resolves to a permission denial.
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** Generous: this is a 2 MB file over whatever connection a phone happens to have. */
        const val DOWNLOAD_TIMEOUT_MS = 300_000L
        const val DOWNLOAD_BUFFER = 64 * 1024
    }
}
