// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

/** Bounded, allowlisted observations. No addresses, credentials, shell output or exception messages. */
public final class BydFlightRecorder {
    public static final int MAX_EVENTS = 384;
    public static final int MAX_BYTES = 96 * 1024;
    public static final int MAX_LINE_BYTES = 256;
    public enum Source { APP, HELPER }
    public enum Code {
        SESSION_STARTED, SESSION_STOPPED, BOOT_OBSERVED, SERVICE, NETWORK, VPN, BACKEND,
        ACC, SCREEN, PHASE, POWER_ERROR, HELPER, WATCHDOG, RECOVERY, SUSPEND,
        PACKAGE_REPLACED, SHUTDOWN_OBSERVED, HISTORY_LIMITED, PARKED_ACCESS
    }
    public enum Value {
        NONE, STARTED, STOPPED, REVOKED, REJECTED, AVAILABLE, UNAVAILABLE, UNKNOWN, ON, OFF,
        WIFI, CELLULAR, WIFI_CELLULAR, ETHERNET, OTHER, NO_NETWORK, RUNNING, DOWN, NO_STATE,
        IN_USE_OTHER_USER, NEEDS_LOGIN, NEEDS_MACHINE_AUTH, STARTING, STOPPING,
        PARKED, WAITING, WAKING, DRIVING, TIMED_OUT, FAILED, OK, ATTEMPT, REQUESTED,
        INTERRUPTED, APK_CHANGED, DISABLED, UNINSTALLED, RESTART, HISTORY_CLEARED, IDLE_EXPIRED
    }

    public static final class Event {
        public final long sequence, wallMs, elapsedMs, boot;
        public final String session, version;
        public final Source source;
        public final Code code;
        public final Value value;

        private Event(long sequence, String session, long wallMs, long elapsedMs, long boot,
                Source source, Code code, Value value, String version) {
            this.sequence = sequence; this.session = session; this.wallMs = wallMs;
            this.elapsedMs = elapsedMs; this.boot = boot; this.source = source;
            this.code = code; this.value = value; this.version = version;
        }

        public String encode() {
            return sequence + "|" + session + "|" + wallMs + "|" + elapsedMs + "|" + boot
                    + "|" + source + "|" + code + "|" + value + "|" + version;
        }

        public String identity() { return source + ":" + session + ":" + sequence; }

        public static Event parse(String line) {
            if (line == null || line.length() > MAX_LINE_BYTES) return null;
            String[] p = line.split("\\|", -1);
            if (p.length != 9 || !p[1].matches("[a-f0-9]{32}")
                    || !p[8].matches("[A-Za-z0-9.+_-]{1,80}")) return null;
            try {
                Event event = new Event(Long.parseLong(p[0]), p[1], Long.parseLong(p[2]),
                        Long.parseLong(p[3]), Long.parseLong(p[4]), Source.valueOf(p[5]),
                        Code.valueOf(p[6]), Value.valueOf(p[7]), p[8]);
                if (event.sequence < 1 || event.wallMs < 0 || event.elapsedMs < 0 || event.boot < -1)
                    return null;
                return event;
            } catch (IllegalArgumentException e) { return null; }
        }
    }

    private final File file;
    private final Source source;
    private final String version;
    private final long boot;
    private final String journalId;
    private String session = UUID.randomUUID().toString().replace("-", "");
    private final ArrayList<Event> events = new ArrayList<>();
    private final EnumMap<Code, Value> observed = new EnumMap<>(Code.class);
    private long nextSequence = 1;
    private int bytes;

    public BydFlightRecorder(File file, Source source, String version, long boot,
            long wallMs, long elapsedMs) throws IOException {
        this.file = file;
        this.source = source;
        this.version = version.matches("[A-Za-z0-9.+_-]{1,80}") ? version : "unknown";
        this.boot = Math.max(-1, boot);
        File identity = new File(file.getParentFile(), file.getName() + ".id");
        String savedId = null;
        if (file.isFile() && identity.isFile() && identity.length() == 32) {
            savedId = new String(Files.readAllBytes(identity.toPath()), StandardCharsets.UTF_8);
            if (!savedId.matches("[a-f0-9]{32}")) savedId = null;
        }
        journalId = savedId != null ? savedId : UUID.randomUUID().toString().replace("-", "");
        if (savedId == null) {
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Recorder directory unavailable");
            Files.write(identity.toPath(), journalId.getBytes(StandardCharsets.UTF_8));
        }
        load();
        Event previous = null;
        for (Event event : events) {
            if (event.source == source) {
                nextSequence = Math.max(nextSequence, event.sequence + 1);
                previous = event;
            }
        }
        if (previous != null && previous.boot >= 0 && boot >= 0 && previous.boot != boot)
            record(Code.BOOT_OBSERVED, Value.STARTED, wallMs, elapsedMs);
        record(Code.SESSION_STARTED, Value.STARTED, wallMs, elapsedMs);
    }

    private void load() throws IOException {
        if (!file.exists()) return;
        // Do not allocate an unbounded line/buffer when a file is corrupt or unexpectedly replaced.
        if (file.length() > MAX_BYTES + MAX_LINE_BYTES) { rewrite(); return; }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Event event = Event.parse(line);
                if (event != null) { events.add(event); bytes += event.encode().length() + 1; }
            }
        }
        trim();
        // Remove a torn final append before another record is written.
        rewrite();
    }

    public synchronized void observe(Code code, Value value, long wallMs, long elapsedMs)
            throws IOException {
        if (observed.get(code) == value) return;
        record(code, value, wallMs, elapsedMs);
        observed.put(code, value);
    }

    public synchronized void record(Code code, Value value, long wallMs, long elapsedMs)
            throws IOException {
        Event event = new Event(nextSequence++, session, Math.max(0, wallMs),
                Math.max(0, elapsedMs), boot, source, code, value, version);
        append(event);
    }

    public synchronized void importEvent(Event event) throws IOException {
        if (event == null || event.source == source) return;
        for (Event old : events) if (old.identity().equals(event.identity())) return;
        append(event);
    }

    private void append(Event event) throws IOException {
        byte[] encoded = (event.encode() + "\n").getBytes(StandardCharsets.UTF_8);
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())
            throw new IOException("Recorder directory unavailable");
        if (events.size() >= MAX_EVENTS || bytes + encoded.length > MAX_BYTES) {
            events.add(event);
            bytes += encoded.length;
            trim();
            // Leave room for another burst instead of rewriting the full journal on every event.
            while (events.size() > MAX_EVENTS * 3 / 4) {
                bytes -= events.remove(0).encode().length() + 1;
            }
            rewrite();
        } else {
            try (FileOutputStream output = new FileOutputStream(file, true)) { output.write(encoded); }
            events.add(event);
            bytes += encoded.length;
        }
    }

    private void trim() {
        while (events.size() > MAX_EVENTS || bytes > MAX_BYTES) {
            bytes -= events.remove(0).encode().length() + 1;
        }
    }

    private void rewrite() throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Recorder directory unavailable");
        File temp = new File(parent, file.getName() + ".new");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            for (Event event : events)
                output.write((event.encode() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (!temp.renameTo(file)) { temp.delete(); throw new IOException("Recorder rotation failed"); }
    }

    public synchronized List<Event> snapshot() { return new ArrayList<>(events); }

    public synchronized List<Event> after(long cursor, int limit) {
        ArrayList<Event> result = new ArrayList<>();
        for (Event event : events) {
            if (event.source == source && event.sequence > cursor) {
                result.add(event);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    public synchronized long lastSequence() { return nextSequence - 1; }
    public String journalId() { return journalId; }

    public synchronized void clear(long wallMs, long elapsedMs) throws IOException {
        events.clear(); bytes = 0; observed.clear();
        session = UUID.randomUUID().toString().replace("-", "");
        rewrite();
        record(Code.SESSION_STARTED, Value.HISTORY_CLEARED, wallMs, elapsedMs);
    }
}
