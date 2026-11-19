package dev.vibex.companion.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.vibex.companion.app.BuildConfig
import dev.vibex.companion.app.MainActivity
import dev.vibex.companion.app.R
import dev.vibex.companion.app.data.VibeXAccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class MobileReleaseInfo(
    val id: String,
    val version: String,
    val buildNumber: Int,
    val changelog: String,
    val forceUpdate: Boolean,
    val publishedAt: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String,
) {
    val releaseKey: String
        get() = "${version}_${buildNumber}"
}

sealed interface ApkDownloadState {
    data object Idle : ApkDownloadState
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progress: Float,
    ) : ApkDownloadState
    data class PendingInstallPermission(val apkFile: File) : ApkDownloadState
    data class ReadyToInstall(val apkFile: File) : ApkDownloadState
    data class Error(val message: String) : ApkDownloadState
}

data class AppUpdateUiState(
    val currentVersionName: String = BuildConfig.VERSION_NAME,
    val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    val checking: Boolean = false,
    val hasUpdate: Boolean = false,
    val release: MobileReleaseInfo? = null,
    val dialogVisible: Boolean = false,
    val downloadState: ApkDownloadState = ApkDownloadState.Idle,
    val statusToast: String? = null,
)

class AppUpdateManager private constructor(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private var lastAutoCheckMs: Long = 0L
    private var downloadJob: Job? = null

    init {
        ensureNotificationChannel()
        scope.launch(Dispatchers.IO) {
            cleanupInstalledOrObsoleteApks()
        }
    }

    fun checkIfDue(fromBackground: Boolean = false) {
        val now = System.currentTimeMillis()
        val interval = if (fromBackground) BACKGROUND_CHECK_INTERVAL_MS else FOREGROUND_CHECK_INTERVAL_MS
        if (now - lastAutoCheckMs < interval) return
        lastAutoCheckMs = now
        checkForUpdate(manual = false, fromBackground = fromBackground)
    }

    fun checkForUpdate(manual: Boolean = false, fromBackground: Boolean = false) {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, statusToast = null) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchLatestAndroidRelease() }
            result.fold(
                onSuccess = { (release, hasUpdate) ->
                    if (release != null && hasUpdate && release.apkUrl.isNotBlank()) {
                        val dismissedKey = prefs.getString(KEY_DISMISSED_RELEASE, "") ?: ""
                        val shouldPopDialog = !fromBackground && (
                            manual || release.forceUpdate || dismissedKey != release.releaseKey
                        )
                        _state.update { prev ->
                            prev.copy(
                                checking = false,
                                hasUpdate = true,
                                release = release,
                                dialogVisible = prev.dialogVisible || shouldPopDialog,
                            )
                        }
                        maybePostUpdateNotification(release, forceNotify = fromBackground)
                    } else {
                        _state.update { prev ->
                            prev.copy(
                                checking = false,
                                hasUpdate = false,
                                release = release,
                                dialogVisible = false,
                                statusToast = if (manual) "当前已是最新版本 v${BuildConfig.VERSION_NAME}" else null,
                            )
                        }
                    }
                },
                onFailure = {
                    _state.update { prev ->
                        prev.copy(
                            checking = false,
                            statusToast = if (manual) "检查更新失败，请稍后重试" else null,
                        )
                    }
                },
            )
        }
    }

    fun openUpdateDialog() {
        val rel = _state.value.release
        if (_state.value.hasUpdate && rel != null) {
            _state.update { it.copy(dialogVisible = true) }
        } else {
            checkForUpdate(manual = true)
        }
    }

    fun dismissDialog() {
        val rel = _state.value.release
        if (rel?.forceUpdate == true) return
        if (rel != null) {
            prefs.edit().putString(KEY_DISMISSED_RELEASE, rel.releaseKey).apply()
        }
        _state.update { it.copy(dialogVisible = false) }
    }

    fun consumeStatusToast() {
        _state.update { it.copy(statusToast = null) }
    }

    fun startDownloadAndInstall() {
        val release = _state.value.release ?: return
        if (downloadJob?.isActive == true) return

        val existingState = _state.value.downloadState
        if (existingState is ApkDownloadState.ReadyToInstall && existingState.apkFile.exists()) {
            installOrRequestPermission(existingState.apkFile)
            return
        }
        if (existingState is ApkDownloadState.PendingInstallPermission && existingState.apkFile.exists()) {
            installOrRequestPermission(existingState.apkFile)
            return
        }

        downloadJob = scope.launch {
            _state.update {
                it.copy(
                    downloadState = ApkDownloadState.Downloading(
                        downloadedBytes = 0L,
                        totalBytes = release.apkSize,
                        progress = 0f,
                    ),
                )
            }
            val outcome = withContext(Dispatchers.IO) {
                downloadAndVerifyApk(release) { downloaded, total ->
                    val frac = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                    _state.update { prev ->
                        prev.copy(
                            downloadState = ApkDownloadState.Downloading(
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                progress = frac,
                            ),
                        )
                    }
                }
            }
            outcome.fold(
                onSuccess = { apkFile ->
                    installOrRequestPermission(apkFile)
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(
                            downloadState = ApkDownloadState.Error(
                                err.message?.takeIf { m -> m.isNotBlank() } ?: "安装包下载失败，请重试",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun onActivityResumed() {
        val current = _state.value.downloadState
        if (current is ApkDownloadState.PendingInstallPermission && current.apkFile.exists()) {
            if (canInstallPackages()) {
                launchSystemInstaller(current.apkFile)
            }
        }
    }

    private fun installOrRequestPermission(apkFile: File) {
        if (!canInstallPackages()) {
            _state.update {
                it.copy(downloadState = ApkDownloadState.PendingInstallPermission(apkFile))
            }
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${appContext.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }.onFailure {
                _state.update {
                    it.copy(downloadState = ApkDownloadState.Error("无法打开安装未知应用授权设置，请在系统设置中手动开启"))
                }
            }
            return
        }
        launchSystemInstaller(apkFile)
    }

    private fun launchSystemInstaller(apkFile: File) {
        _state.update { it.copy(downloadState = ApkDownloadState.ReadyToInstall(apkFile)) }
        runCatching {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile,
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(installIntent)
        }.onFailure {
            _state.update {
                it.copy(downloadState = ApkDownloadState.Error("无法调起系统安装器，请重试"))
            }
        }
    }

    private fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun downloadAndVerifyApk(
        release: MobileReleaseInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<File> = runCatching {
        val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val safeVer = release.version.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val targetFile = File(updatesDir, "vibex-${safeVer}-b${release.buildNumber}.apk")
        if (targetFile.exists() && targetFile.length() > 1024L) {
            if (release.apkSha256.isBlank() || sha256File(targetFile).equals(release.apkSha256, ignoreCase = true)) {
                onProgress(targetFile.length(), targetFile.length())
                return@runCatching targetFile
            }
            targetFile.delete()
        }

        val partFile = File(updatesDir, "${targetFile.name}.part")
        if (partFile.exists()) partFile.delete()

        val req = Request.Builder().url(release.apkUrl).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("下载请求失败（HTTP ${resp.code}）")
            }
            val body = resp.body ?: throw IllegalStateException("服务器返回空安装包")
            val total = body.contentLength().takeIf { it > 0L } ?: release.apkSize
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastEmit = 0L

            body.byteStream().use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 60L) {
                            lastEmit = now
                            onProgress(downloaded, total)
                        }
                    }
                    output.flush()
                }
            }
            onProgress(downloaded, total)

            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.apkSha256.isNotBlank() && !actualHash.equals(release.apkSha256, ignoreCase = true)) {
                partFile.delete()
                throw IllegalStateException("安装包完整性校验未通过，请重新下载")
            }
        }

        if (targetFile.exists()) targetFile.delete()
        if (!partFile.renameTo(targetFile)) {
            partFile.copyTo(targetFile, overwrite = true)
            partFile.delete()
        }
        targetFile
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetchLatestAndroidRelease(): Result<Pair<MobileReleaseInfo?, Boolean>> = runCatching {
        val url = "${VibeXAccountRepository.SITE_BASE_URL}/api/mobile/releases/latest" +
            "?platform=android&version=${Uri.encode(BuildConfig.VERSION_NAME)}&build=${BuildConfig.VERSION_CODE}"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val raw = resp.body?.string().orEmpty()
            val root = JSONObject(raw)
            val hasUpdate = root.optBoolean("hasUpdate", false)
            val relObj = root.optJSONObject("release") ?: return@use (null to false)
            val info = MobileReleaseInfo(
                id = relObj.optString("id", ""),
                version = relObj.optString("version", ""),
                buildNumber = relObj.optInt("buildNumber", 0),
                changelog = relObj.optString("changelog", ""),
                forceUpdate = relObj.optBoolean("forceUpdate", false),
                publishedAt = relObj.optString("publishedAt", ""),
                apkUrl = relObj.optString("apkUrl", ""),
                apkSize = relObj.optLong("apkSize", 0L),
                apkSha256 = relObj.optString("apkSha256", ""),
            )
            val localNewer = info.buildNumber > BuildConfig.VERSION_CODE ||
                (info.buildNumber == BuildConfig.VERSION_CODE && compareSemver(info.version, BuildConfig.VERSION_NAME) > 0)
            info to (hasUpdate && localNewer)
        }
    }

    private fun compareSemver(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.trim().removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(pa.size, pb.size, 3)
        for (i in 0 until len) {
            val da = pa.getOrElse(i) { 0 }
            val db = pb.getOrElse(i) { 0 }
            if (da != db) return da.compareTo(db)
        }
        return 0
    }

    private fun cleanupInstalledOrObsoleteApks() {
        val dir = File(appContext.cacheDir, "updates")
        if (!dir.exists()) return
        val buildRegex = Regex("""-b(\d+)\.apk$""")
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".part")) {
                f.delete()
                return@forEach
            }
            val m = buildRegex.find(f.name)
            val fileBuild = m?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (fileBuild != null && fileBuild <= BuildConfig.VERSION_CODE) {
                f.delete()
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    UPDATE_CHANNEL_ID,
                    "版本更新提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "当 Dimo 发布新版本时推送更新日志与一键安装提醒"
                },
            )
        }
    }

    private fun maybePostUpdateNotification(release: MobileReleaseInfo, forceNotify: Boolean) {
        val alreadyNotified = prefs.getString(KEY_NOTIFIED_RELEASE, "") ?: ""
        if (!forceNotify && alreadyNotified == release.releaseKey) return
        if (alreadyNotified == release.releaseKey) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        prefs.edit().putString(KEY_NOTIFIED_RELEASE, release.releaseKey).apply()

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_UPDATE_DIALOG, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            UPDATE_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = release.changelog.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString("\n")
            .ifBlank { "点击查看更新日志并一键覆盖安装新版本" }

        val notification = NotificationCompat.Builder(appContext, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("发现 Dimo 新版本 v${release.version}")
            .setContentText(summary.lines().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext).notify(UPDATE_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val EXTRA_OPEN_UPDATE_DIALOG = "vibex_open_update_dialog"
        private const val PREFS_NAME = "vibex.app.update"
        private const val KEY_DISMISSED_RELEASE = "dismissed_release_key"
        private const val KEY_NOTIFIED_RELEASE = "notified_release_key"
        private const val UPDATE_CHANNEL_ID = "vibex-app-updates"
        private const val UPDATE_NOTIFICATION_ID = 9401
        private const val FOREGROUND_CHECK_INTERVAL_MS = 10 * 60 * 1000L
        private const val BACKGROUND_CHECK_INTERVAL_MS = 30 * 60 * 1000L

        @Volatile
        private var instance: AppUpdateManager? = null

        fun getInstance(context: Context): AppUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
