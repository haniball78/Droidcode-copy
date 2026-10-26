package com.droiddeck.launcher.core.privileged;

import android.os.Process;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * DroidDeck Embedded Privileged Server.
 * Runs under UID 2000 (shell) via app_process to grant DroidDeck elevated ADB-level
 * system privileges (child-process limits removal, OOM protection, fixed performance mode)
 * independently without requiring external helper apps.
 */
public class DroidDeckPrivilegedServer {

    public static final int PORT = 28751;
    private static final String TAG = "DroidDeckPrivServer";
    private static String sessionToken = null;
    private static volatile boolean deadmanArmed = false;
    private static volatile boolean deadmanExpired = false;
    private static volatile long deadmanLastBeatMs = -1L;
    private static final long DEADMAN_WATCHDOG_MS = 45000L;

    public static void main(String[] args) {
        if (args.length > 0 && args[0] != null && !args[0].isEmpty()) {
            sessionToken = args[0];
        }
        int uid = Process.myUid();
        int pid = Process.myPid();
        System.out.println(TAG + ": Starting daemon with UID=" + uid + ", PID=" + pid);

        try (ServerSocket serverSocket = new ServerSocket(PORT, 10, InetAddress.getByName("127.0.0.1"))) {
            System.out.println(TAG + ": Listening on 127.0.0.1:" + PORT);

            // Auto-apply essential system optimizations on daemon launch
            disablePhantomProcessKillerInternal();

            Thread watchdog = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (deadmanArmed && System.currentTimeMillis() - deadmanLastBeatMs > DEADMAN_WATCHDOG_MS) {
                        deadmanArmed = false;
                        deadmanExpired = true;
                        watchdogRestore();
                    }
                }
            }, "droiddeck-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            while (true) {
                try {
                    Socket client = serverSocket.accept();
                    handleClient(client);
                } catch (Exception e) {
                    System.err.println(TAG + ": Client handle error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println(TAG + ": Server failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void handleClient(Socket socket) {
        new Thread(() -> {
            try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
            ) {
                String line = reader.readLine();
                if (line == null) return;

                if (line.equals("PING")) {
                    writer.println("PONG uid=" + Process.myUid() + " pid=" + Process.myPid());
                    return;
                }
                boolean authenticated = false;
                if (line.startsWith("AUTH:")) {
                    String token = line.substring(5);
                    if (sessionToken == null || !sessionToken.equals(token)) {
                        writer.println("UNAUTHORIZED");
                        return;
                    }
                    authenticated = true;
                    line = reader.readLine();
                    if (line == null) return;
                } else if (sessionToken != null) {
                    writer.println("UNAUTHORIZED");
                    return;
                }
                if (line.equals("ARM") || line.equals("BEAT") || line.equals("DISARM")) {
                    if (!authenticated || sessionToken == null) {
                        writer.println("UNAUTHORIZED");
                        return;
                    }
                    if (line.equals("ARM")) {
                        deadmanArmed = true;
                        deadmanExpired = false;
                        deadmanLastBeatMs = System.currentTimeMillis();
                        writer.println("OK");
                    } else if (line.equals("BEAT")) {
                        if (deadmanExpired || !deadmanArmed) {
                            writer.println("EXPIRED");
                        } else {
                            deadmanLastBeatMs = System.currentTimeMillis();
                            writer.println("OK");
                        }
                    } else {
                        deadmanArmed = false;
                        deadmanExpired = false;
                        deadmanLastBeatMs = -1L;
                        writer.println("OK");
                    }
                    return;
                }
                if (line.startsWith("EXEC:")) {
                    String base64Cmd = line.substring(5);
                    byte[] decoded = Base64.getDecoder().decode(base64Cmd);
                    String command = new String(decoded, StandardCharsets.UTF_8);
                    runCommand(command, writer);
                } else if (line.equals("DISABLE_PHANTOM")) {
                    boolean ok = disablePhantomProcessKillerInternal();
                    writer.println(ok ? "OK" : "ERROR");
                } else if (line.startsWith("BOOST_PERFORMANCE_TURBO:")) {
                    String pidStr = line.substring(24);
                    int targetPid = Integer.parseInt(pidStr);
                    boolean ok = boostPerformanceInternal(targetPid, true);
                    writer.println(ok ? "OK" : "ERROR");
                } else if (line.startsWith("BOOST_PERFORMANCE:")) {
                    String pidStr = line.substring(18);
                    int targetPid = Integer.parseInt(pidStr);
                    boolean ok = boostPerformanceInternal(targetPid, false);
                    writer.println(ok ? "OK" : "ERROR");
                } else if (line.startsWith("GRANT_PERMS:")) {
                    String pkg = line.substring(12);
                    boolean ok = grantPermissionsInternal(pkg);
                    writer.println(ok ? "OK" : "ERROR");
                } else {
                    writer.println("UNKNOWN_COMMAND");
                }
            } catch (Exception e) {
                System.err.println(TAG + ": Socket error: " + e.getMessage());
            }
        }).start();
    }

    private static void runCommand(String command, PrintWriter writer) {
        try {
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", command});
            StringBuilder output = new StringBuilder();

            try (BufferedReader procReader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String l;
                while ((l = procReader.readLine()) != null) {
                    output.append(l).append("\n");
                }
            }

            int exitCode = process.waitFor();
            String encodedOutput = Base64.getEncoder().encodeToString(output.toString().getBytes(StandardCharsets.UTF_8));
            writer.println("EXIT:" + exitCode + ":" + encodedOutput);
        } catch (Exception e) {
            writer.println("EXIT:-1:" + Base64.getEncoder().encodeToString(e.getMessage().getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static void watchdogRestore() {
        try {
            Runtime.getRuntime().exec(new String[]{
                "/system/bin/sh", "-c",
                "cmd power set-fixed-performance-mode-enabled false 2>/dev/null || true; " +
                "settings put global droiddeck_deadman_expired 1 2>/dev/null || true"
            }).waitFor();
            System.out.println(TAG + ": dead-man watchdog fired, fixed-performance disabled");
        } catch (Exception e) {
            System.err.println(TAG + ": dead-man watchdog restore failed: " + e.getMessage());
        }
    }

    private static boolean disablePhantomProcessKillerInternal() {
        try {
            Runtime.getRuntime().exec(new String[]{
                "/system/bin/sh", "-c",
                "device_config set_sync_disabled_for_tests persistent && " +
                "device_config put activity_manager max_phantom_processes 2147483647 && " +
                "settings put global settings_enable_monitor_phantom_procs false && " +
                "cmd activity disable-child-process-restrictions 2>/dev/null || true"
            }).waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean boostPerformanceInternal(int targetPid, boolean aggressive) {
        try {
            int nice = aggressive ? -20 : -10;
            String fixed = aggressive
                ? "cmd power set-fixed-performance-mode-enabled true 2>/dev/null || true"
                : "true";
            String script = String.format(
                "echo -1000 > /proc/%d/oom_score_adj 2>/dev/null || true; " +
                "renice -n %d -p %d 2>/dev/null || true; " +
                "%s",
                targetPid, nice, targetPid, fixed
            );
            Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", script}).waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean grantPermissionsInternal(String pkg) {
        try {
            String[] perms = {
                "android.permission.WRITE_SECURE_SETTINGS",
                "android.permission.DUMP",
                "android.permission.PACKAGE_USAGE_STATS",
            };
            for (String p : perms) {
                Runtime.getRuntime().exec(new String[]{"/system/bin/pm", "grant", pkg, p}).waitFor();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
