// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

/** Reflects the actual BYD framework; no OEM placeholder classes are shipped in the APK. */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
public final class BydPlatform implements ParkedPowerState.Actions {
    private final Context context;
    private final PowerManager powerManager;
    private final WifiManager wifiManager;
    private final PowerManager.WakeLock wakeLock;
    private final WifiManager.WifiLock wifiLock;
    private final ConnectivityManager connectivity;
    private final BydPanelControl panel;
    private final IBinder displayToken = new Binder();
    private Object bodywork;
    private Method powerGetter;
    private Method wakeMethod;
    private final Method[] backlightMethods = new Method[2];
    private final boolean[] backlightResolved = new boolean[2];
    private Method screenGetter;
    private boolean screenResolved;
    private Object setting;
    private Object powerService;
    private Method lockedOn;
    private Method lockedOff;
    private boolean lockResolved;
    private long nextBodyworkAttempt;
    private long lastWakeLock;
    private String lastError;

    @SuppressWarnings("deprecation")
    public BydPlatform(Context context) {
        this.context = new BydContext(context);
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                context.getPackageName() + ":parked");
        wakeLock.setReferenceCounted(false);
        wifiLock = wifiManager == null ? null : wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL, context.getPackageName() + ":parked-wifi");
        if (wifiLock != null) wifiLock.setReferenceCounted(false);
        panel = new BydPanelControl(new BydPanelControl.Device() {
            @Override public Integer powerLevel() { return BydPlatform.this.powerLevel(); }
            @Override public Integer screenStatus() { return BydPlatform.this.screenStatus(); }
            @Override public boolean plainBacklight(boolean on) { return setPlainBacklight(on); }
            @Override public boolean supportsDisplayLock() { return resolveDisplayLock(); }
            @Override public void lockedBacklight(boolean on) {
                try {
                    if (!resolveDisplayLock()) throw new IllegalStateException("Vendor display lock unavailable");
                    if (on) lockedOn.invoke(powerService, displayToken, "Tailpark");
                    else lockedOff.invoke(powerService, displayToken);
                } catch (Exception e) { throw failure("Restore display lock", e); }
            }
        });
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
            bodywork = null;
            nextBodyworkAttempt = SystemClock.elapsedRealtime() + 5000;
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    public Boolean screenOn() {
        Integer status = screenStatus();
        return status == null ? powerManager.isScreenOn() : status == 1;
    }

    public Integer screenStatus() {
        try {
            if (!screenResolved) {
                screenResolved = true;
                try { screenGetter = PowerManager.class.getMethod("getPowerScreenStatus"); }
                catch (NoSuchMethodException ignored) { }
            }
            if (screenGetter == null) return null;
            int value = ((Number) screenGetter.invoke(powerManager)).intValue();
            return value == 0 || value == 1 ? value : null;
        } catch (Exception e) { record("Read display state", e); return null; }
    }

    @Override public void wake() {
        try {
            if (wakeMethod == null) wakeMethod = PowerManager.class.getMethod(
                    "wakeUp", long.class, int.class, String.class);
            wakeMethod.invoke(powerManager, SystemClock.uptimeMillis(), 9, "ACC_ON");
        } catch (Exception e) { throw failure("Keep Android awake", e); }
    }

    @Override public void backlightOff() {
        panel.darken();
    }

    @Override public boolean restoreDisplay() { return panel.restore(false); }

    private boolean setPlainBacklight(boolean on) {
        int index = on ? 1 : 0;
        try {
            if (!backlightResolved[index]) {
                backlightResolved[index] = true;
                String suffix = on ? "On" : "Off";
                for (String prefix : new String[]{"TurnBacklight", "turnBacklight"}) {
                    try {
                        backlightMethods[index] = PowerManager.class.getMethod(prefix + suffix, long.class);
                        break;
                    } catch (NoSuchMethodException ignored) { }
                }
            }
            if (backlightMethods[index] != null) {
                backlightMethods[index].invoke(powerManager, SystemClock.uptimeMillis());
                return true;
            }
        } catch (Exception e) { record("Set display backlight", e); }
        try {
            Class<?> type = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice",
                    true, PowerManager.class.getClassLoader());
            if (setting == null) setting = type.getMethod("getInstance", Context.class).invoke(null, context);
            if (setting != null) {
                type.getMethod(on ? "turnBacklightOn" : "turnBacklightOff").invoke(setting);
                return true;
            }
        } catch (Exception e) { record("Set OEM display backlight", e); }
        return false;
    }

    private boolean resolveDisplayLock() {
        if (!lockResolved) {
            try {
                Field service = PowerManager.class.getDeclaredField("mService");
                service.setAccessible(true);
                powerService = service.get(powerManager);
                lockedOn = powerService.getClass().getMethod("TurnBacklightOnWithLock", IBinder.class, String.class);
                lockedOff = powerService.getClass().getMethod("TurnBacklightOffWithLock", IBinder.class);
                lockResolved = true;
            } catch (NoSuchFieldException | NoSuchMethodException e) {
                lockResolved = true;
                lockedOn = null;
                lockedOff = null;
            } catch (Exception e) { record("Resolve display lock", e); }
        }
        return lockedOn != null && lockedOff != null;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    @Override public void enableWifi() {
        if (!Integer.valueOf(0).equals(powerLevel())) return;
        if (wifiManager == null) throw new IllegalStateException("Wi-Fi service unavailable");
        if (wifiManager.isWifiEnabled()) return;
        try {
            if (wifiManager.setWifiEnabled(true)) return;
        } catch (RuntimeException e) {
            if (Process.myUid() != 2000) throw e;
        }
        if (Process.myUid() == 2000) {
            try {
                if (BydShell.run(5, "/system/bin/svc", "wifi", "enable").exitCode == 0) return;
            } catch (Exception e) { throw failure("Restore Wi-Fi", e); }
        }
        throw new IllegalStateException("Car firmware refused Wi-Fi retention");
    }

    @SuppressLint("WakelockTimeout")
    @SuppressWarnings("deprecation")
    public synchronized void holdLocks(boolean keepWifi) {
        long now = SystemClock.elapsedRealtime();
        if (!wakeLock.isHeld() || now - lastWakeLock >= 30000) {
            wakeLock.acquire(60000);
            lastWakeLock = now;
        }
        boolean connectedWifi = keepWifi && wifiConnected();
        if (wifiLock != null) {
            if (connectedWifi && !wifiLock.isHeld()) wifiLock.acquire();
            else if (!connectedWifi && wifiLock.isHeld()) wifiLock.release();
        }
    }

    @SuppressWarnings("deprecation")
    private boolean wifiConnected() {
        if (connectivity == null) return false;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }

    public void release() {
        try { panel.restore(true); }
        catch (RuntimeException e) { Log.w("Tailpark", "Display restoration failed", e); }
        synchronized (this) {
            if (wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        }
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
        if (!message.equals(lastError)) Log.w("Tailpark", message);
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
