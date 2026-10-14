// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Detached app_process entrypoint. ADB launches it with shell's actual power permission. */
public final class BydPowerHelper {
    private final Context context;
    private final String token;
    private final String apkPath;
    private final BydPlatform platform;
    private final ParkedPowerState state;
    public static final int PORT = 41642;
    private final ServerSocket server;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean alive = true;
    private volatile boolean keepWifi = true;
    private volatile long heartbeat = SystemClock.elapsedRealtime();
    private long lastRecovery;

    private BydPowerHelper(Context context, String token) throws IOException, PackageManager.NameNotFoundException {
        this.context = context;
        this.token = token;
        apkPath = context.getApplicationInfo().sourceDir;
        // Binding first gives one process ownership; a duplicate exits without acquiring locks.
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", PORT));
        // Framework/AppOps attribution must match the actual shell UID, not the VPN's app UID.
        Context shellContext = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        platform = new BydPlatform(shellContext);
        state = new ParkedPowerState(platform);
    }

    public static void main(String[] args) {
        if (args.length != 2 || !args[0].matches("[A-Za-z0-9_.]+") || args[1].length() != 72) return;
        try {
            Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            Context context = system.createPackageContext(args[0],
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            BydPowerHelper helper = new BydPowerHelper(context, args[1]);
            helper.ticker.scheduleWithFixedDelay(helper::tick, 0, 500, TimeUnit.MILLISECONDS);
            new Thread(helper::serve, "byd-helper-control").start();
            Runtime.getRuntime().addShutdownHook(new Thread(helper::shutdown));
            Looper.loop();
        } catch (Exception e) {
            Log.e("TailscaleBYD", "Power helper could not start", e);
        }
    }

    private synchronized void tick() {
        if (!alive) return;
        try {
            platform.holdLocks(keepWifi);
            state.tick(true, keepWifi, platform.powerLevel(), platform.screenOn(), SystemClock.elapsedRealtime());
            long now = SystemClock.elapsedRealtime();
            if (now - heartbeat >= 30000 && now - lastRecovery >= 30000) {
                lastRecovery = now;
                // Exit after uninstall or APK replacement; the new app launches its own code.
                if (!apkPath.equals(context.getPackageManager()
                        .getApplicationInfo(context.getPackageName(), 0).sourceDir)) {
                    shutdown();
                    return;
                }
                // Use am's genuine shell attribution; a package Context does not change UID.
                new Thread(this::recoverVpn, "byd-vpn-recovery").start();
            }
        } catch (PackageManager.NameNotFoundException e) {
            shutdown();
        } catch (Exception e) {
            Log.w("TailscaleBYD", "Power helper tick failed", e);
        }
    }

    private void recoverVpn() {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/am", "broadcast", "--receiver-foreground",
                    "--include-stopped-packages", "-n", context.getPackageName()
                    + "/com.tailscale.ipn.byd.BydRecoveryReceiver", "-a",
                    context.getPackageName() + ".BYD_RECOVER", "--es", "token", token)
                    .redirectOutput(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")))
                    .redirectError(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")))
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroy();
        } catch (Exception e) {
            Log.w("TailscaleBYD", "VPN recovery failed: " + e.getClass().getSimpleName());
        } finally {
            if (process != null && process.isAlive()) process.destroy();
        }
    }

    private void serve() {
        while (alive) {
            try (Socket client = server.accept()) {
                client.setSoTimeout(1500);
                String line = readLine(client.getInputStream());
                int separator = line.indexOf(' ');
                if (separator < 0 || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        line.substring(0, separator).getBytes(StandardCharsets.UTF_8))) continue;
                String command = line.substring(separator + 1);
                boolean stop = command.equals("STOP");
                if (command.equals("CONFIG 1") || command.equals("CONFIG 0")) {
                    keepWifi = command.endsWith("1");
                    heartbeat = SystemClock.elapsedRealtime();
                } else if (command.equals("PING")) heartbeat = SystemClock.elapsedRealtime();
                else if (!stop && !command.equals("STATUS")) continue;
                client.getOutputStream().write((status().toString() + "\n").getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                if (stop) shutdown();
            } catch (Exception e) {
                if (alive) Log.w("TailscaleBYD", "Helper client disconnected: " + e.getClass().getSimpleName());
            }
        }
    }

    private JSONObject status() throws Exception {
        return new JSONObject().put("protocol", 1).put("uid", Process.myUid())
                .put("phase", state.phase().name())
                .put("devicePower", context.checkSelfPermission("android.permission.DEVICE_POWER")
                        == PackageManager.PERMISSION_GRANTED)
                .put("error", state.error() != null ? state.error() : platform.error());
    }

    private synchronized void shutdown() {
        if (!alive) return;
        alive = false;
        ticker.shutdownNow();
        platform.release();
        try { server.close(); } catch (IOException ignored) {}
        Process.killProcess(Process.myPid());
    }

    public static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') return new String(result.toByteArray(), StandardCharsets.UTF_8);
            if (result.size() >= 4096) throw new IOException("Helper message too long");
            result.write(next);
        }
        throw new IOException("Helper closed connection");
    }
}
