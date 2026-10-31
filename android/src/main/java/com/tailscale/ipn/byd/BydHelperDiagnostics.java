// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.SystemClock;
import android.provider.Settings;
import com.tailscale.ipn.BuildConfig;
import com.tailscale.ipn.byd.BydFlightRecorder.Code;
import com.tailscale.ipn.byd.BydFlightRecorder.Event;
import com.tailscale.ipn.byd.BydFlightRecorder.Value;
import java.util.List;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Best-effort helper journal; errors must never prevent power control or recovery. */
final class BydHelperDiagnostics {
    private BydFlightRecorder journal;
    private String lastWatchdog;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    BydHelperDiagnostics(Context context, BydRuntimeFiles runtime) {
        try {
            long boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            journal = new BydFlightRecorder(runtime.file("events.log"), BydFlightRecorder.Source.HELPER,
                    BuildConfig.VERSION_NAME, boot, System.currentTimeMillis(), SystemClock.elapsedRealtime());
        } catch (Exception ignored) { }
    }

    void observe(Code code, Value value) {
        try {
            if (journal != null) journal.observe(code, value, System.currentTimeMillis(), SystemClock.elapsedRealtime());
        } catch (Exception ignored) { }
    }

    void record(Code code, Value value) {
        try {
            if (journal != null) journal.record(code, value, System.currentTimeMillis(), SystemClock.elapsedRealtime());
        } catch (Exception ignored) { }
    }

    void power(Integer level, Boolean screen, String phase, boolean failed) {
        observe(Code.ACC, level == null ? Value.UNKNOWN : level == 0 ? Value.OFF : Value.ON);
        observe(Code.SCREEN, screen == null ? Value.UNKNOWN : screen ? Value.ON : Value.OFF);
        try { observe(Code.PHASE, Value.valueOf(phase)); }
        catch (IllegalArgumentException ignored) { observe(Code.PHASE, Value.UNKNOWN); }
        observe(Code.POWER_ERROR, failed ? Value.FAILED : Value.OK);
    }

    void watchdog(String identity) {
        if (identity != null && lastWatchdog != null && !identity.equals(lastWatchdog))
            record(Code.WATCHDOG, Value.RESTART);
        observe(Code.WATCHDOG, identity == null ? Value.UNAVAILABLE : Value.AVAILABLE);
        lastWatchdog = identity;
    }

    void startNetworks(Context context) {
        try {
            connectivity = context.getSystemService(ConnectivityManager.class);
            if (connectivity == null) { observe(Code.NETWORK, Value.UNKNOWN); return; }
            HashMap<Network, NetworkCapabilities> networks = new HashMap<>();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    synchronized (networks) { networks.put(network, caps); report(); }
                }
                @Override public void onLost(Network network) {
                    synchronized (networks) {
                        if (networks.remove(network) != null) report();
                    }
                }
                private void report() {
                    boolean wifi = false, cellular = false, ethernet = false, other = false;
                    for (NetworkCapabilities caps : networks.values()) {
                        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue;
                        wifi |= caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                        cellular |= caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                        ethernet |= caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                        other = true;
                    }
                    observe(Code.NETWORK, wifi && cellular ? Value.WIFI_CELLULAR : wifi ? Value.WIFI
                            : cellular ? Value.CELLULAR : ethernet ? Value.ETHERNET
                            : other ? Value.OTHER : Value.NO_NETWORK);
                }
            };
            observe(Code.NETWORK, Value.UNKNOWN);
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback);
            // Registration delivers existing capabilities. No callback is not evidence of no network.
        } catch (Exception ignored) { observe(Code.NETWORK, Value.UNKNOWN); }
    }

    void closeNetworks() {
        try {
            if (connectivity != null && networkCallback != null)
                connectivity.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) { }
    }

    JSONObject page(long cursor) throws Exception {
        JSONObject result = new JSONObject().put("diagnostics", 1);
        JSONArray entries = new JSONArray();
        if (journal == null) return result.put("events", entries).put("last", 0)
                .put("more", false).put("unavailable", true);
        List<Event> events = journal.after(cursor, 10);
        for (Event event : events) entries.put(event.encode());
        long last = journal.lastSequence();
        return result.put("events", entries).put("last", last)
                .put("journal", journal.journalId())
                .put("more", !events.isEmpty() && events.get(events.size() - 1).sequence < last);
    }

    JSONObject clear() throws Exception {
        if (journal == null) return new JSONObject().put("cleared", false);
        journal.clear(System.currentTimeMillis(), SystemClock.elapsedRealtime());
        return new JSONObject().put("cleared", true);
    }
}
