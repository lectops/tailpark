// Copyright (c) Tailpark contributors
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
import com.tailscale.ipn.byd.BydFlightRecorder.Code;
import com.tailscale.ipn.byd.BydFlightRecorder.Value;

/** Detached app_process entrypoint. ADB launches it with shell's actual power permission. */
public final class BydPowerHelper {
    private final Context context;
    private final String token;
    private final String apkPath;
    private final BydPlatform platform;
    private final ParkedPowerState state;
    private final BydRuntimeFiles runtime;
    private final BydHelperDiagnostics diagnostics;
    private final BydRecoveryPolicy recovery = new BydRecoveryPolicy(SystemClock.elapsedRealtime());
    public static final int PORT = 41642;
    private final ServerSocket server;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean alive = true;
    private volatile boolean keepWifi = true;
    private long lastFileHeartbeat;
    private long lastPreferenceCheck;
    private volatile long suspendMs;
    private volatile Integer observedPower;
    private volatile Boolean observedScreenOn;
    private volatile long lastTickAt;
    private volatile String tickError;
    private long previousSleep = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis();

    private BydPowerHelper(Context context, String token) throws IOException, PackageManager.NameNotFoundException {
        this.context = context;
        this.token = token;
        apkPath = System.getProperty("java.class.path", context.getApplicationInfo().sourceDir);
        runtime = new BydRuntimeFiles(context.getPackageName());
        // Binding first gives one process ownership; a duplicate exits without acquiring locks.
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", PORT));
        diagnostics = new BydHelperDiagnostics(context, runtime);
        try { runtime.preserveStatusBeforeRestart(); }
        catch (IOException e) { Log.w("Tailpark", "Previous helper status could not be retained"); }
        runtime.write("helper.pid", Integer.toString(Process.myPid()));
        runtime.write("helper.apk", apkPath);
        runtime.write("helper.identity", BydRuntimeFiles.fingerprint(token));
        // Framework/AppOps attribution must match the actual shell UID, not the VPN's app UID.
        Context shellContext = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        diagnostics.startNetworks(shellContext);
        platform = new BydPlatform(shellContext);
        state = new ParkedPowerState(platform);
    }

    public static void main(String[] args) {
        if (args.length != 2 || !args[0].matches("[A-Za-z0-9_.]+")
                || !args[1].matches("[0-9a-f-]{72}") || Process.myUid() != 2000) return;
        try {
            BydRuntimeFiles runtime = new BydRuntimeFiles(args[0]);
            if (runtime.file("disabled").exists()) return;
            String wifi = BydRuntimeFiles.desiredConfig(args[0], args[1]);
            if (wifi == null) { runtime.disable(); return; }
            Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            Context context = system.createPackageContext(args[0],
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            BydPowerHelper helper = new BydPowerHelper(context, args[1]);
            helper.keepWifi = wifi.equals("1");
            helper.ticker.execute(helper::poll);
            helper.maintenance.scheduleWithFixedDelay(helper::maintain, 0, 10, TimeUnit.SECONDS);
            new Thread(helper::serve, "byd-helper-control").start();
            Runtime.getRuntime().addShutdownHook(new Thread(helper::shutdown));
            Looper.loop();
        } catch (Exception e) {
            Log.e("Tailpark", "Power helper could not start", e);
            Process.killProcess(Process.myPid());
        }
    }

    private synchronized void poll() {
        if (!alive) return;
        tick();
        if (alive) ticker.schedule(this::poll, state.nextPollDelayMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized void tick() {
        if (!alive) return;
        try {
            platform.holdLocks(keepWifi);
            observedPower = platform.powerLevel();
            observedScreenOn = platform.screenOn();
            state.tick(true, keepWifi, observedPower, observedScreenOn, SystemClock.elapsedRealtime());
            long now = SystemClock.elapsedRealtime();
            lastTickAt = now;
            tickError = null;
            diagnostics.power(observedPower, observedScreenOn, state.phase().name(), state.error() != null);
            if (now - lastFileHeartbeat >= 5000) {
                runtime.heartbeat();
                // Remains available after connectivity is lost or Android restarts.
                runtime.write("last-status.json", status().toString() + "\n");
                lastFileHeartbeat = now;
            }
            long sleep = now - SystemClock.uptimeMillis();
            long suspended = Math.max(0, sleep - previousSleep);
            // Separate clock reads have small jitter; report only meaningful suspend episodes.
            if (suspended >= 30000) {
                suspendMs += suspended;
                diagnostics.record(Code.SUSPEND, Value.STARTED);
            }
            previousSleep = sleep;
        } catch (Exception e) {
            tickError = e.getClass().getSimpleName();
            diagnostics.observe(Code.POWER_ERROR, Value.FAILED);
            try { runtime.write("last-status.json", status().toString() + "\n"); }
            catch (Exception ignored) { }
            Log.w("Tailpark", "Power helper tick failed", e);
        }
    }

    private void maintain() {
        if (!alive) return;
        try {
            // Updates must be checked even when the updated app keeps sending heartbeats.
            if (!apkPath.equals(context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), 0).sourceDir)) {
                diagnostics.record(Code.SESSION_STOPPED, Value.APK_CHANGED);
                shutdown();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastPreferenceCheck >= 60000) {
                lastPreferenceCheck = now;
                String wifi = BydRuntimeFiles.desiredConfig(context.getPackageName(), token);
                if (wifi == null) {
                    diagnostics.record(Code.SESSION_STOPPED, Value.DISABLED);
                    runtime.disable();
                    shutdown();
                    return;
                }
                keepWifi = wifi.equals("1");
            }
            diagnostics.watchdog(runtime.watchdogIdentity());
            if (alive && recovery.shouldRecover(now)) recoverVpn();
        } catch (PackageManager.NameNotFoundException e) {
            diagnostics.record(Code.SESSION_STOPPED, Value.UNINSTALLED);
            shutdown();
        } catch (Exception e) {
            Log.w("Tailpark", "Helper maintenance failed: " + e.getClass().getSimpleName());
        }
    }

    private void recoverVpn() {
        diagnostics.record(Code.RECOVERY, Value.ATTEMPT);
        try {
            BydRecoveryCommands.Outcome outcome = BydRecoveryCommands.recoverObserved(context.getPackageName(), token);
            diagnostics.record(Code.RECOVERY, outcome == BydRecoveryCommands.Outcome.DISABLED ? Value.DISABLED
                    : outcome == BydRecoveryCommands.Outcome.UNAVAILABLE ? Value.UNAVAILABLE
                    : outcome == BydRecoveryCommands.Outcome.REJECTED ? Value.REVOKED : Value.REQUESTED);
        } catch (Exception e) {
            diagnostics.record(Code.RECOVERY, Value.FAILED);
            Log.w("Tailpark", "VPN recovery failed: " + e.getClass().getSimpleName());
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
                if (command.matches("DIAGNOSTICS [0-9]{1,18}") || command.equals("CLEAR_DIAGNOSTICS")) {
                    JSONObject response = command.equals("CLEAR_DIAGNOSTICS") ? diagnostics.clear()
                            : diagnostics.page(Long.parseLong(command.substring(12)));
                    client.getOutputStream().write((response.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                    continue;
                }
                boolean stop = command.equals("STOP");
                boolean restart = command.equals("RESTART");
                if (command.matches("CONFIG [01] [01]")) {
                    keepWifi = command.charAt(7) == '1';
                    recovery.heartbeat(SystemClock.elapsedRealtime(), command.charAt(9) == '1');
                    diagnostics.observe(Code.VPN, command.charAt(9) == '1' ? Value.RUNNING : Value.DOWN);
                } else if (command.equals("PING")) recovery.heartbeat(SystemClock.elapsedRealtime(), false);
                else if (!stop && !restart && !command.equals("STATUS")) continue;
                if (stop) runtime.disable();
                if (stop || restart) diagnostics.record(Code.SESSION_STOPPED,
                        stop ? Value.DISABLED : Value.RESTART);
                client.getOutputStream().write((status().toString() + "\n").getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                if (stop || restart) shutdown();
            } catch (Exception e) {
                if (alive) Log.w("Tailpark", "Helper client disconnected: " + e.getClass().getSimpleName());
            }
        }
    }

    private JSONObject status() throws Exception {
        return new JSONObject().put("protocol", 2).put("uid", Process.myUid())
                .put("apkPath", apkPath).put("watchdogAlive", runtime.watchdogAlive())
                .put("suspendMs", suspendMs)
                .put("powerLevel", observedPower == null ? JSONObject.NULL : observedPower)
                .put("screenOn", observedScreenOn == null ? JSONObject.NULL : observedScreenOn)
                .put("sampledAtElapsedMs", lastTickAt)
                .put("sampleAgeMs", lastTickAt == 0 ? JSONObject.NULL
                        : Math.max(0, SystemClock.elapsedRealtime() - lastTickAt))
                .put("tickError", tickError == null ? JSONObject.NULL : tickError)
                .put("phase", state.phase().name())
                .put("devicePower", context.checkSelfPermission("android.permission.DEVICE_POWER")
                        == PackageManager.PERMISSION_GRANTED)
                .put("error", state.error() != null ? state.error()
                        : state.phase() == ParkedPowerState.Phase.UNKNOWN ? platform.error() : null);
    }

    private synchronized void shutdown() {
        if (!alive) return;
        alive = false;
        diagnostics.closeNetworks();
        diagnostics.observe(Code.SESSION_STOPPED, Value.STOPPED);
        ticker.shutdownNow();
        maintenance.shutdownNow();
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
