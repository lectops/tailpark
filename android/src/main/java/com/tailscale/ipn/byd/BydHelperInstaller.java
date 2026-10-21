// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import android.os.Process;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

/** Installs the APK's supervisor without requiring unzip or a large ADB OPEN packet. */
public final class BydHelperInstaller {
    public static void main(String[] args) {
        if (args.length != 2 || !args[0].matches("[A-Za-z0-9_.]+")
                || !args[1].matches("[0-9a-f-]{72}") || Process.myUid() != 2000) return;
        try {
            String apk = System.getProperty("java.class.path");
            BydRuntimeFiles runtime = new BydRuntimeFiles(args[0]);
            File directory = runtime.directory;
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create runtime directory");
            if (!directory.setReadable(false, false) || !directory.setWritable(false, false)
                    || !directory.setExecutable(false, false) || !directory.setReadable(true, true)
                    || !directory.setWritable(true, true) || !directory.setExecutable(true, true)) {
                throw new IllegalStateException("Cannot protect runtime directory");
            }
            try (RandomAccessFile file = new RandomAccessFile(runtime.file("installer.lock"), "rw");
                    FileLock lock = file.getChannel().lock()) {
                // Check again after waiting for another installer: a stop may have happened meanwhile.
                if (BydRuntimeFiles.desiredConfig(args[0], args[1]) == null) return;
                String body;
                try (ZipFile zip = new ZipFile(apk);
                        InputStream input = zip.getInputStream(zip.getEntry("assets/byd-watchdog.sh"))) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    body = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
                String script = "#!/system/bin/sh\nPKG=" + quote(args[0]) + "\nTOKEN=" + quote(args[1])
                        + "\nDIR=" + quote(directory.getPath()) + "\nIDENTITY="
                        + quote(BydRuntimeFiles.fingerprint(args[1])) + "\n" + body;
                runtime.write("watchdog.sh", script);
                runtime.write("version", BydRuntimeFiles.fingerprint(script + apk));
                if (runtime.file("disabled").exists() && !runtime.file("disabled").delete()) {
                    throw new IllegalStateException("Cannot enable watchdog");
                }
                // This grant allows the app to reopen BYD's cleared local debugging listener after boot.
                try { BydShell.run(5, "/system/bin/pm", "grant", args[0], "android.permission.WRITE_SECURE_SETTINGS"); }
                catch (Exception ignored) { }
                java.lang.Process watchdog = new ProcessBuilder("/system/bin/sh", runtime.file("watchdog.sh").getPath())
                        .redirectInput(new File("/dev/null"))
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(runtime.file("helper.log")))
                        .redirectErrorStream(true).start();
                // Serialize installer launches until singleton ownership is visible.
                long until = android.os.SystemClock.elapsedRealtime() + 5000;
                while (watchdog.isAlive() && !runtime.watchdogAlive()
                        && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50);
            }
        } catch (Exception e) {
            // Do not log command output or the installation token.
            Log.e("Tailpark", "Watchdog setup failed: " + e.getClass().getSimpleName());
        }
    }

    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
