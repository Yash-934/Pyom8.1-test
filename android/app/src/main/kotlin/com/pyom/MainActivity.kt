package com.pyom

import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

class MainActivity : FlutterActivity() {
    private val mainHandler   = Handler(Looper.getMainLooper())
    private var currentProcess: Process? = null
    private val isSetupCancelled = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    // ── Storage paths ──────────────────────────────────────────────────────
    // CRITICAL: Linux rootfs MUST be on external storage.
    // /data is mounted noexec on Android 10+ — any binary there cannot be
    // execve()'d. External storage (/sdcard/Android/data/…) is NOT noexec.
    private val extDir       get() = getExternalFilesDir(null) ?: filesDir
    private val envRoot      get() = File(extDir, "linux_env")
    private val binDir       get() = File(filesDir, "bin")   // metadata only
    private val prootVersionFile get() = File(binDir, "proot.version")

    // proot binary lives in nativeLibraryDir — the ONLY /data dir Android
    // marks executable (for .so files). We ship it as libproot.so.
    private val prootBin     get() = File(applicationInfo.nativeLibraryDir, "libproot.so")

    private val PROOT_CURRENT_VERSION = "5.3.0"

    // ── proot download sources (fallback chain) ────────────────────────────
    private fun prootSources(arch: String): List<Pair<String, String>> {
        val a = if (arch == "x86_64") "x86_64" else "aarch64"
        return listOf(
            "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-$a"              to "binary",
            "https://github.com/proot-me/proot/releases/download/v5.1.107-android/proot-$a"   to "binary",
            "https://raw.githubusercontent.com/AndronixApp/AndronixOrigin/master/repo/$a/proot" to "binary",
            "https://packages.termux.dev/apt/termux-main/pool/stable/main/p/proot/proot_5.3.0-1_$a.deb" to "deb",
            "https://github.com/CypherpunkArmory/UserLAnd-Assets-Support/raw/main/fs-assets/$a/proot"    to "binary",
        )
    }

    private val rootfsSources = mapOf(
        "alpine" to listOf(
            "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            "https://github.com/alpinelinux/docker-alpine/raw/main/aarch64/alpine-minirootfs-3.19.0-aarch64.tar.gz",
        ),
        "ubuntu" to listOf(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.3-base-arm64.tar.gz",
            "https://github.com/debuerreotype/docker-debian-artifacts/raw/dist-arm64/buster/rootfs.tar.gz",
        ),
    )

    private var eventSink: EventChannel.EventSink? = null
    private val CHANNEL        = "com.pyom/linux_environment"
    private val OUTPUT_CHANNEL = "com.pyom/process_output"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, OUTPUT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(a: Any?, sink: EventChannel.EventSink?) { eventSink = sink }
                override fun onCancel(a: Any?) { eventSink = null }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "setupEnvironment"        -> {
                        isSetupCancelled.set(false)
                        val distro = call.argument<String>("distro") ?: "alpine"
                        val envId  = call.argument<String>("envId")  ?: "alpine"
                        executor.execute { setupEnvironment(distro, envId, result) }
                    }
                    "cancelSetup"             -> { isSetupCancelled.set(true); result.success(null) }
                    "executeCommand"          -> executeCommand(call, result)
                    "isEnvironmentInstalled"  -> {
                        val envId = call.argument<String>("envId") ?: ""
                        val dir   = File(envRoot, envId)
                        result.success(dir.exists() && (File(dir,"bin/sh").exists() || File(dir,"usr/bin/sh").exists()))
                    }
                    "listEnvironments"        -> result.success(listEnvironments())
                    "deleteEnvironment"       -> {
                        val envId = call.argument<String>("envId") ?: ""
                        File(envRoot, envId).deleteRecursively()
                        result.success(true)
                    }
                    "getStorageInfo"          -> result.success(mapOf(
                        "filesDir"       to filesDir.absolutePath,
                        "envRoot"        to envRoot.absolutePath,
                        "freeSpaceMB"    to (extDir.freeSpace / 1048576L),
                        "totalSpaceMB"   to (extDir.totalSpace / 1048576L),
                        "prootVersion"   to (prootVersionFile.takeIf { it.exists() }?.readText()?.trim() ?: "bundled"),
                        "prootPath"      to prootBin.absolutePath,
                        "prootExists"    to prootBin.exists(),
                    ))
                    "checkProotUpdate"        -> executor.execute { tryUpdateProot() }
                    "saveFileToDownloads"     -> {
                        val src  = call.argument<String>("sourcePath") ?: ""
                        val name = call.argument<String>("fileName")   ?: "file.py"
                        saveFileToDownloads(src, name, result)
                    }
                    "shareFile"               -> {
                        result.success(null) // implemented via Flutter share_plus if needed
                    }
                    else                      -> result.notImplemented()
                }
            }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ENVIRONMENT SETUP
    // ══════════════════════════════════════════════════════════════════════

    private fun sendProgress(msg: String, progress: Double) {
        mainHandler.post {
            flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                MethodChannel(messenger, CHANNEL).invokeMethod(
                    "onSetupProgress",
                    mapOf("message" to msg, "progress" to progress)
                )
            }
        }
    }

    private fun setupEnvironment(distro: String, envId: String, result: MethodChannel.Result) {
        try {
            envRoot.mkdirs(); binDir.mkdirs()
            val envDir = File(envRoot, envId); envDir.mkdirs()

            // ── Step 1: Ensure proot is available ──────────────────────────
            sendProgress("Checking proot binary…", 0.03)
            if (!prootBin.exists()) {
                result.error("SETUP_ERROR",
                    "libproot.so not found in native lib dir.\n" +
                    "Make sure libproot.so is in jniLibs/arm64-v8a/ and " +
                    "android:extractNativeLibs=\"true\" is set in AndroidManifest.xml.", null)
                return
            }
            sendProgress("✅ proot ready (bundled as libproot.so)", 0.06)

            if (isSetupCancelled.get()) { mainHandler.post { result.error("CANCELLED","Cancelled",null) }; return }

            // ── Step 2: Download rootfs ────────────────────────────────────
            sendProgress("Downloading $distro rootfs…", 0.10)
            val tarFile = File(extDir, "rootfs_${envId}.tar.gz")
            val sources = rootfsSources[distro] ?: rootfsSources["alpine"]!!

            var downloaded = false
            for ((i, url) in sources.withIndex()) {
                try {
                    sendProgress("Trying source ${i + 1}/${sources.size}…", 0.10 + i * 0.05)
                    downloadWithProgress(url, tarFile, 0.10, 0.60)
                    downloaded = true; break
                } catch (_: Exception) { tarFile.delete() }
            }
            if (!downloaded) { mainHandler.post { result.error("SETUP_ERROR", "Failed to download rootfs from all sources.", null) }; return }

            if (isSetupCancelled.get()) { tarFile.delete(); mainHandler.post { result.error("CANCELLED","Cancelled",null) }; return }

            // ── Step 3: Extract rootfs ─────────────────────────────────────
            sendProgress("Extracting rootfs (may take a minute)…", 0.62)
            extractTarGz(tarFile, envDir)
            tarFile.delete()

            if (isSetupCancelled.get()) { mainHandler.post { result.error("CANCELLED","Cancelled",null) }; return }

            // ── Step 4: Configure DNS ──────────────────────────────────────
            sendProgress("Configuring environment…", 0.75)
            try {
                File(envDir, "etc").mkdirs()
                File(envDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            } catch (_: Exception) {}

            // ── Step 5: Install Python ─────────────────────────────────────
            sendProgress("Installing Python (this takes 2-5 minutes)…", 0.78)
            val installCmd = when (distro) {
                "ubuntu" -> "apt-get update -qq 2>&1 | tail -3 && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3 python3-pip python3-dev build-essential 2>&1"
                else     -> "apk update -q 2>&1 && apk add --no-cache -q python3 py3-pip gcc musl-dev linux-headers python3-dev 2>&1"
            }
            runInProot(envId, installCmd)
            sendProgress("Upgrading pip…", 0.90)
            runInProot(envId, "pip3 install --upgrade pip setuptools wheel --quiet 2>&1 || true")

            sendProgress("✅ Environment ready!", 1.0)
            mainHandler.post {
                result.success(mapOf("success" to true, "distro" to distro, "envId" to envId))
            }
        } catch (e: Exception) {
            mainHandler.post { result.error("SETUP_ERROR", e.message ?: "Unknown error", null) }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // EXECUTE COMMAND
    // ══════════════════════════════════════════════════════════════════════

    private fun executeCommand(call: MethodCall, result: MethodChannel.Result) {
        executor.execute {
            try {
                val envId      = call.argument<String>("environmentId") ?: ""
                val command    = call.argument<String>("command") ?: ""
                val workingDir = call.argument<String>("workingDir") ?: "/"
                val timeoutMs  = call.argument<Int>("timeoutMs") ?: 300000

                // Safety: empty envId means wrong key or no env set — give clear error
                if (envId.isEmpty()) {
                    mainHandler.post {
                        result.error("EXEC_ERROR",
                            "environmentId is empty. No Linux environment selected or wrong channel key.",
                            null)
                    }
                    return@execute
                }
                mainHandler.post { result.success(runCommandInProot(envId, command, workingDir, timeoutMs)) }
            } catch (e: Exception) {
                mainHandler.post { result.error("EXEC_ERROR", e.message, null) }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PROOT RUNNER  — the core of all execution
    // ══════════════════════════════════════════════════════════════════════

    private fun runInProot(envId: String, cmd: String) =
        runCommandInProot(envId, cmd, "/", 300_000).let { "${it["stdout"]}\n${it["stderr"]}" }

    private fun runCommandInProot(
        envId: String, command: String, workingDir: String, timeoutMs: Int
    ): Map<String, Any> {
        val envDir = File(envRoot, envId)
        val tmpDir = File(envDir, "tmp").also { it.mkdirs() }

        if (!prootBin.exists()) return mapOf(
            "stdout" to "", "exitCode" to -1,
            "stderr" to "libproot.so not found in ${prootBin.absolutePath}. Rebuild APK."
        )

        val shell = when {
            File(envDir, "bin/bash").exists()     -> "/bin/bash"
            File(envDir, "usr/bin/bash").exists() -> "/usr/bin/bash"
            File(envDir, "bin/sh").exists()       -> "/bin/sh"
            else                                  -> "/bin/sh"
        }

        // ── FIX 1: No --env flag (only in proot 5.3+) → use ProcessBuilder.environment()
        // ── FIX 2: -k fakes kernel ver → prevents seccomp probe crash (signal 11)
        // ── FIX 3: --link2symlink → avoids hard-link syscalls many ROMs reject
        val cmd = mutableListOf(
            prootBin.absolutePath, "--kill-on-exit",
            "-k", "4.14.111",
            "--link2symlink",
            "-r", envDir.absolutePath,
            "-w", workingDir,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            // Bind internal filesDir so scripts written there are visible inside proot
            "-b", "${filesDir.absolutePath}:/data_internal",
            "-0",
            shell, "-c", command
        )

        val pb = ProcessBuilder(cmd).apply {
            directory(filesDir)
            redirectErrorStream(false)
            environment().apply {
                put("HOME",                    "/root")
                put("PATH",                    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("LANG",                    "C.UTF-8")
                put("TERM",                    "xterm-256color")
                put("PROOT_TMP_DIR",           tmpDir.absolutePath)
                put("PYTHONDONTWRITEBYTECODE", "1")
                put("PIP_NO_CACHE_DIR",        "off")
                put("PROOT_NO_SECCOMP",        "1")  // disable seccomp filter entirely
                put("PROOT_LOADER",            "/proc/self/exe")
            }
        }

        val process = pb.start(); currentProcess = process
        val stdout  = StringBuilder()
        val stderr  = StringBuilder()

        val t1 = Thread {
            process.inputStream.bufferedReader().lines().forEach { line ->
                stdout.append(line).append("\n")
                mainHandler.post { eventSink?.success(line) }
            }
        }
        val t2 = Thread {
            process.errorStream.bufferedReader().lines().forEach { line ->
                stderr.append(line).append("\n")
                mainHandler.post { eventSink?.success("[err] $line") }
            }
        }
        t1.start(); t2.start()
        val done = process.waitFor(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        t1.join(3000); t2.join(3000)

        return if (done)
            mapOf("stdout" to stdout.toString(), "stderr" to stderr.toString(), "exitCode" to process.exitValue())
        else {
            process.destroyForcibly()
            mapOf("stdout" to stdout.toString(), "stderr" to "Timed out after ${timeoutMs}ms", "exitCode" to -1)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // proot SELF-UPDATE (silent background)
    // ══════════════════════════════════════════════════════════════════════

    private fun tryUpdateProot() {
        // proot is now bundled as libproot.so — no runtime update needed.
        // But we can check if the bundled version needs upgrade via app update.
        mainHandler.post {
            flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                MethodChannel(messenger, CHANNEL).invokeMethod(
                    "onProotUpdated",
                    mapOf("version" to "bundled", "updated" to false)
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DOWNLOAD / EXTRACT HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private fun downloadWithProgress(urlStr: String, dest: File, progressStart: Double, progressEnd: Double) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout    = 120_000
        conn.instanceFollowRedirects = true
        conn.connect()

        val total = conn.contentLengthLong.toDouble()
        var downloaded = 0L

        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) {
                        val p = progressStart + (downloaded / total) * (progressEnd - progressStart)
                        sendProgress("Downloading… ${(downloaded / 1048576.0).toInt()} MB", p)
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun extractTarGz(tarFile: File, destDir: File) {
        destDir.mkdirs()
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(tarFile.inputStream()))
        ).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (!tar.canReadEntryData(entry)) { entry = tar.nextEntry; continue }
                val name = entry.name.removePrefix("./")
                if (name.isEmpty() || name == ".") { entry = tar.nextEntry; continue }

                val target = File(destDir, name)
                // Prevent path traversal
                if (!target.canonicalPath.startsWith(destDir.canonicalPath)) {
                    entry = tar.nextEntry; continue
                }

                when {
                    entry.isDirectory    -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        try {
                            val linkTarget = entry.linkName
                            // Create a shell symlink using native ln -s (via Runtime)
                            Runtime.getRuntime().exec(arrayOf("ln", "-sf", linkTarget, target.absolutePath)).waitFor()
                        } catch (_: Exception) { /* skip broken symlinks */ }
                    }
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { tar.copyTo(it) }
                        if (entry.mode and 0b001001001 != 0) target.setExecutable(true, false)
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    private fun listEnvironments(): List<Map<String, Any>> {
        if (!envRoot.exists()) return emptyList()
        return envRoot.listFiles()?.filter { it.isDirectory }?.map { dir ->
            mapOf(
                "id"     to dir.name,
                "path"   to dir.absolutePath,
                "exists" to (File(dir, "bin/sh").exists() || File(dir, "usr/bin/sh").exists()),
            )
        } ?: emptyList()
    }

    // ══════════════════════════════════════════════════════════════════════
    // SAVE FILE TO DOWNLOADS
    // ══════════════════════════════════════════════════════════════════════

    private fun saveFileToDownloads(sourcePath: String, fileName: String, result: MethodChannel.Result) {
        executor.execute {
            try {
                val src = File(sourcePath)
                if (!src.exists()) {
                    mainHandler.post { result.error("NOT_FOUND", "File not found: $sourcePath", null) }
                    return@execute
                }
                val savedPath: String
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val mime = when {
                        fileName.endsWith(".py")   -> "text/x-python"
                        fileName.endsWith(".txt")  -> "text/plain"
                        fileName.endsWith(".json") -> "application/json"
                        fileName.endsWith(".md")   -> "text/markdown"
                        else                       -> "application/octet-stream"
                    }
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Pyom")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openOutputStream(uri)!!.use { os -> src.inputStream().use { it.copyTo(os) } }
                    savedPath = "Downloads/Pyom/$fileName"
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Pyom")
                    dir.mkdirs()
                    src.copyTo(File(dir, fileName), overwrite = true)
                    savedPath = "${dir.absolutePath}/$fileName"
                }
                mainHandler.post { result.success(mapOf("success" to true, "path" to savedPath)) }
            } catch (e: Exception) {
                mainHandler.post { result.error("SAVE_ERROR", e.message, null) }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private fun getAndroidArch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return if (abi.contains("x86_64")) "x86_64" else "aarch64"
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentProcess?.destroyForcibly()
        executor.shutdown()
    }
}
