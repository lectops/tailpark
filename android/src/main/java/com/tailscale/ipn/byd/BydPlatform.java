// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Reflects the actual BYD framework; no OEM placeholder classes are shipped in the APK. */
public final class BydPlatform implements ParkedPowerState.Actions {
    private final Context context;
    private final PowerManager powerManager;
    private final WifiManager wifiManager;
    private final PowerManager.WakeLock wakeLock;
    private final WifiManager.WifiLock wifiLock;
    private Object bodywork;
    private Method powerGetter;
    private Method wakeMethod;
    private Method backlightMethod;
    private long nextBodyworkAttempt;
    private long lastWakeLock;
    private String lastError;

    @SuppressWarnings("deprecation")
    public BydPlatform(Context context) {
        this.context = new BydContext(context);
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                context.getPackageName() + ":parked");
        wakeLock.setReferenceCounted(false);
        wifiLock = wifiManager == null ? null : wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, context.getPackageName() + ":parked-wifi");
        if (wifiLock != null) wifiLock.setReferenceCounted(false);
    }

    public static boolean available() {
        try {
            Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice", false,
                    PowerManager.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) { return false; }
    }

    public synchronized Integer powerLevel() {
        try {
            if (bodywork == null) {
                if (SystemClock.elapsedRealtime() < nextBodyworkAttempt) return null;
                nextBodyworkAttempt = SystemClock.elapsedRealtime() + 5000;
                Class<?> type = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
                        true, PowerManager.class.getClassLoader());
                bodywork = type.getMethod("getInstance", Context.class).invoke(null, context);
                powerGetter = type.getMethod("getPowerLevel");
                if (bodywork == null) throw new IllegalStateException("BYD power device unavailable");
            }
            int level = ((Number) powerGetter.invoke(bodywork)).intValue();
            return level >= 0 && level <= 4 ? level : null;
        } catch (Exception e) {
            record("Read car power", e);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    public Boolean screenOn() { return powerManager.isScreenOn(); }

    @Override public void wake() {
        try {
            if (wakeMethod == null) wakeMethod = PowerManager.class.getMethod(
                    "wakeUp", long.class, int.class, String.class);
            wakeMethod.invoke(powerManager, SystemClock.uptimeMillis(), 9, "ACC_ON");
        } catch (Exception e) { throw failure("Keep Android awake", e); }
    }

    @Override public void backlightOff() {
        try {
            if (backlightMethod == null) {
                try { backlightMethod = PowerManager.class.getMethod("TurnBacklightOff", long.class); }
                catch (NoSuchMethodException e) {
                    backlightMethod = PowerManager.class.getMethod("turnBacklightOff", long.class);
                }
            }
            backlightMethod.invoke(powerManager, SystemClock.uptimeMillis());
        } catch (Exception e) { throw failure("Keep display dark", e); }
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    @Override public void enableWifi() {
        if (wifiManager != null && !wifiManager.isWifiEnabled() && !wifiManager.setWifiEnabled(true)) {
            throw new IllegalStateException("Car firmware refused Wi-Fi retention");
        }
    }

    @SuppressLint("WakelockTimeout")
    @SuppressWarnings("deprecation")
    public synchronized void holdLocks(boolean keepWifi) {
        long now = SystemClock.elapsedRealtime();
        if (!wakeLock.isHeld() || now - lastWakeLock >= 30000) {
            wakeLock.acquire(60000);
            lastWakeLock = now;
        }
        boolean connectedWifi = keepWifi && wifiManager != null && wifiManager.isWifiEnabled()
                && wifiManager.getConnectionInfo().getNetworkId() >= 0;
        if (wifiLock != null) {
            if (connectedWifi && !wifiLock.isHeld()) wifiLock.acquire();
            else if (!connectedWifi && wifiLock.isHeld()) wifiLock.release();
        }
    }

    public synchronized void release() {
        if (wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    public synchronized String error() { return lastError; }

    private IllegalStateException failure(String operation, Exception e) {
        return new IllegalStateException(record(operation, e), e);
    }

    private synchronized String record(String operation, Exception e) {
        Throwable cause = e instanceof InvocationTargetException
                ? ((InvocationTargetException) e).getTargetException() : e;
        String message = operation + ": " + cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : " — " + cause.getMessage());
        if (!message.equals(lastError)) Log.w("TailscaleBYD", message);
        lastError = message;
        return message;
    }

    /** Adapts local OEM Context checks; Binder still checks the real process UID. */
    public static final class BydContext extends ContextWrapper {
        public BydContext(Context base) { super(base); }
        private boolean byd(String p) {
            return p == null || p.startsWith("android.permission.BYD")
                    || p.equals("android.permission.DEVICE_POWER") || p.contains("BYD_");
        }
        @Override public int checkSelfPermission(String p) {
            return byd(p) ? PackageManager.PERMISSION_GRANTED : super.checkSelfPermission(p);
        }
        @Override public int checkCallingPermission(String p) {
            return byd(p) ? PackageManager.PERMISSION_GRANTED : super.checkCallingPermission(p);
        }
        @Override public int checkCallingOrSelfPermission(String p) {
            return byd(p) ? PackageManager.PERMISSION_GRANTED : super.checkCallingOrSelfPermission(p);
        }
        @Override public int checkPermission(String p, int pid, int uid) {
            return byd(p) ? PackageManager.PERMISSION_GRANTED : super.checkPermission(p, pid, uid);
        }
        @Override public void enforcePermission(String p, int pid, int uid, String message) {
            if (!byd(p)) super.enforcePermission(p, pid, uid, message);
        }
        @Override public void enforceCallingPermission(String p, String message) {
            if (!byd(p)) super.enforceCallingPermission(p, message);
        }
        @Override public void enforceCallingOrSelfPermission(String p, String message) {
            if (!byd(p)) super.enforceCallingOrSelfPermission(p, message);
        }
    }
}
