package com.droiddeck.launcher.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.ArrayDeque

object WirelessAdbFix {
    private const val CERT_FILE = "wireless-adb-cert.pem"
    private const val KEY_FILE = "wireless-adb-key.pem"
    private const val HOST_FILE = "wireless-adb-host.txt"
    private const val CONNECTION_FILE = "wireless-adb-connection.txt"
    const val LOOPBACK = "127.0.0.1"

    private data class SavedConnection(val host: String, val port: Int?)

    @Synchronized
    private fun loadOrCreateIdentity(context: Context) {
        val directory = context.noBackupFilesDir
        val cert = File(directory, CERT_FILE)
        val key = File(directory, KEY_FILE)
        if (cert.isFile && key.isFile) {
            KadbCert.set(cert.readBytes(), key.readBytes())
            return
        }

        val identity = KadbCert.get(notAfter = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650))
        KadbCert.set(identity.first, identity.second)
        writeAtomically(context, cert, identity.first)
        writeAtomically(context, key, identity.second)
    }

    private fun writeAtomically(context: Context, destination: File, contents: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeBytes(contents)
        check(temporary.renameTo(destination)) { context.getString(R.string.adbfix_save_failed) }
    }

    suspend fun pair(context: Context, host: String, port: Int, pairingCode: String) {
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.attach(context)
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(context, "adb.pair", "started", fields = mapOf("port" to port.toString()))
        loadOrCreateIdentity(context.applicationContext)
        Kadb.pair(host, port, pairingCode, "DroidDeck")
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(context, "adb.pair", "succeeded", fields = mapOf("port" to port.toString()))
        writeAtomically(context, File(context.noBackupFilesDir, HOST_FILE), host.toByteArray())
        writeAtomically(context, File(context.noBackupFilesDir, CONNECTION_FILE), ByteArray(0))
    }

    private fun savedConnection(context: Context): SavedConnection? {
        val directory = context.applicationContext.noBackupFilesDir
        if (!File(directory, CERT_FILE).isFile || !File(directory, KEY_FILE).isFile) return null
        val host = File(directory, HOST_FILE).takeIf(File::isFile)?.readText()?.trim()
            ?.takeIf(String::isNotEmpty) ?: return null
        val lines = File(directory, CONNECTION_FILE).takeIf(File::isFile)?.readLines()
        val port = lines?.takeIf { it.size == 2 && it[0] == host }
            ?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
        return SavedConnection(host, port)
    }

    fun savedConnectionAddress(context: Context): String? = savedConnection(context)?.let { saved ->
        val host = if (':' in saved.host) "[${saved.host}]" else saved.host
        "$host:${saved.port ?: ""}"
    }

    private fun saveConnection(context: Context, host: String, port: Int) {
        writeAtomically(
            context,
            File(context.applicationContext.noBackupFilesDir, CONNECTION_FILE),
            "$host\n$port".toByteArray(),
        )
    }

    fun isLocalAddress(address: InetAddress): Boolean = runCatching {
        if (address.isLoopbackAddress) return true
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { network ->
            network.inetAddresses.toList().any { it.address.contentEquals(address.address) }
        }
    }.getOrDefault(address is Inet4Address)

    fun localConnectPort(context: Context): Int? =
        tlsPortProperty()
            ?: findConnectPort(context, LOOPBACK)

    private fun tlsPortProperty(): Int? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "service.adb.tls.port") as? String
    }.getOrNull()?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    fun findConnectPort(context: Context, pairedHost: String, timeoutSeconds: Long = 12): Int? {
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val paired = runCatching { InetAddress.getByName(pairedHost) }.getOrNull() ?: return null
        val matches: (InetAddress) -> Boolean =
            if (paired.isLoopbackAddress) ::isLocalAddress else { address -> address.address.contentEquals(paired.address) }
        val resolvedPort = AtomicInteger(-1)
        val finished = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val queue = ArrayDeque<NsdServiceInfo>()
        val seen = mutableSetOf<String>()
        var resolving = false
        val lock = Any()

        lateinit var resolveNext: () -> Unit
        resolveNext = {
            val next = synchronized(lock) {
                if (finished.get() || resolving || queue.isEmpty()) null
                else queue.removeFirst().also { resolving = true }
            }
            if (next != null) {
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { resolving = false }
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val address = serviceInfo.host
                        if (address != null && matches(address) && finished.compareAndSet(false, true)) {
                            resolvedPort.set(serviceInfo.port)
                            latch.countDown()
                        } else {
                            synchronized(lock) { resolving = false }
                            resolveNext()
                        }
                    }
                }
                runCatching { manager.resolveService(next, listener) }.onFailure {
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains("_adb-tls-connect._tcp") == true) {
                    synchronized(lock) {
                        if (seen.add(serviceInfo.serviceName)) queue.addLast(serviceInfo)
                    }
                    resolveNext()
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) { latch.countDown() }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
        }

        try {
            manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { manager.stopServiceDiscovery(discoveryListener) }
        }
        return resolvedPort.get().takeIf { it in 1..65535 }
    }

    fun setChildProcessLimit(context: Context, host: String, port: Int, enabled: Boolean) {
        loadOrCreateIdentity(context.applicationContext)
        Kadb.create(host, port).use { adb ->
            PhantomProcessLimit.shellCommands(enabled).forEach { command ->
                val change = adb.shell(command)
                check(change.exitCode == 0) { change.allOutput.ifBlank { context.getString(R.string.adbfix_command_failed, change.exitCode) } }
            }
            val result = adb.shell(PhantomProcessLimit.verifyCommand())
            check(result.exitCode == 0 && PhantomProcessLimit.verified(result.output, enabled)) {
                context.getString(R.string.adbfix_not_confirmed, result.allOutput.trim())
            }
        }
        if (PhantomProcessLimit.usesDeviceConfig()) PhantomProcessLimit.rememberAndroid12(context, !enabled)
        saveConnection(context, host, port)
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "phantom.apply", "succeeded",
            fields = mapOf("action" to if (enabled) "re-enable" else "disable"),
        )
    }

    fun setUsingSavedPairing(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val saved = savedConnection(appContext)
            ?: error(appContext.getString(R.string.adbfix_no_pairing))
        val cachedPort = saved.port
        if (cachedPort != null) {
            val cachedResult = runCatching {
                setChildProcessLimit(appContext, saved.host, cachedPort, enabled)
            }
            if (cachedResult.isSuccess) return
            val discoveredPort = findConnectPort(appContext, saved.host)
            if (discoveredPort != null && discoveredPort != cachedPort) {
                setChildProcessLimit(appContext, saved.host, discoveredPort, enabled)
            } else {
                cachedResult.getOrThrow()
            }
        } else {
            val discoveredPort = findConnectPort(appContext, saved.host)
                ?: error(appContext.getString(R.string.adbfix_enter_address))
            setChildProcessLimit(appContext, saved.host, discoveredPort, enabled)
        }
    }

    fun startPrivilegedDaemonViaAdb(context: Context, host: String, port: Int) {
        loadOrCreateIdentity(context.applicationContext)
        val starterCmd = com.droiddeck.launcher.core.privileged.PrivilegedBridge.getStarterAdbCommand(context)
        val steps = StringBuilder()
        Kadb.create(host, port).use { adb ->
            if (SessionPrefs.phantomDisableEnabled(context)) {
                steps.append("phantom:").append(adb.shell("device_config set_sync_disabled_for_tests persistent && device_config put activity_manager max_phantom_processes 2147483647 && settings put global settings_enable_monitor_phantom_procs false").exitCode).append(",")
            } else {
                steps.append("phantom:skipped,")
            }
            steps.append("grant-secure:").append(adb.shell("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true").exitCode).append(",")
            steps.append("grant-dump:").append(adb.shell("pm grant ${context.packageName} android.permission.DUMP 2>/dev/null || true").exitCode).append(",")
            steps.append("grant-usage:").append(adb.shell("pm grant ${context.packageName} android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true").exitCode).append(",")
            steps.append("whitelist:").append(adb.shell("dumpsys deviceidle whitelist +${context.packageName} 2>/dev/null || true").exitCode).append(",")
            steps.append("appops:").append(adb.shell("cmd appops set ${context.packageName} RUN_IN_BACKGROUND allow 2>/dev/null || true").exitCode).append(",")
            steps.append("starter:").append(adb.shell(starterCmd).exitCode)
        }
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "device.change", "daemon-start-steps",
            fields = mapOf("steps" to steps.toString()),
        )
        saveConnection(context, host, port)
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "adb.daemon", "started", fields = mapOf("port" to port.toString()),
        )
    }

    fun isPaired(context: Context): Boolean {
        val directory = context.applicationContext.noBackupFilesDir
        val cert = File(directory, CERT_FILE)
        val key = File(directory, KEY_FILE)
        val host = File(directory, HOST_FILE).takeIf(File::isFile)?.readText()?.trim()
        return cert.isFile && key.isFile && !host.isNullOrEmpty()
    }

    suspend fun pairAndStartPrivilegedDaemon(context: Context, port: Int, pairingCode: String): Result<Unit> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val result = runCatching {
                pair(context, LOOPBACK, port, pairingCode)
                val baselinePort = localConnectPort(context)
                if (baselinePort != null) {
                    runCatching { com.droiddeck.launcher.core.privileged.DeviceBaselineCapture.ensureAfterPairing(context, LOOPBACK, baselinePort) }
                }
                kotlinx.coroutines.delay(800)
                val connectPort = localConnectPort(context)
                    ?: error("Connect port not discovered. Ensure Wireless Debugging is active.")
                startPrivilegedDaemonViaAdb(context, LOOPBACK, connectPort)
            }
            if (result.isFailure) {
                com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                    context, "adb.pair", "failed",
                    fields = mapOf("reason" to (result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")),
                )
            }
            result
        }

    fun clearPairing(context: Context) {
        val directory = context.applicationContext.noBackupFilesDir
        File(directory, HOST_FILE).delete()
        File(directory, CONNECTION_FILE).delete()
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "adb.pair", "cleared",
        )
    }

    fun startPrivilegedDaemonWithSavedPairing(context: Context): Boolean {
        val appContext = context.applicationContext
        runCatching { com.droiddeck.launcher.core.privileged.DeviceBaselineCapture.ensureViaSavedPairing(appContext) }
        val saved = savedConnection(appContext)
        val host = saved?.host ?: LOOPBACK
        // Always try to discover current live connect port first (since port changes on reboot / toggle)
        val discovered = localConnectPort(appContext)
        val port = discovered ?: saved?.port ?: return false
        val firstAttempt = runCatching {
            startPrivilegedDaemonViaAdb(appContext, host, port)
            true
        }.getOrDefault(false)
        if (firstAttempt) return true

        // If the first attempt failed and we used discovered port, try saved port (or vice versa)
        val altPort = if (port == discovered) saved?.port else discovered
        if (altPort != null && altPort != port) {
            val secondAttempt = runCatching {
                startPrivilegedDaemonViaAdb(appContext, host, altPort)
                true
            }.getOrDefault(false)
            if (secondAttempt) return true
        }
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            appContext, "adb.daemon", "failed", fields = mapOf("port" to port.toString()),
        )
        return false
    }
}
