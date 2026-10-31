// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import android.content.Context;
import android.os.Looper;
import android.os.Process;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Fixed shell update entrypoint. It never accepts a shell command, arbitrary path or package. */
public final class BydUpdateShell {
    public static void main(String[] args) {
        if (Process.myUid() != 2000 || args.length != 3 || !args[0].equals("dev.tailpark.byd")
                || !args[1].matches("[0-9]{1,5}") || !args[2].matches("[a-f0-9]{64}")) return;
        File staged = null;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[1])), 3000);
            socket.setSoTimeout(15000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF(args[2]); output.flush();
            long length = input.readLong();
            String checksum = input.readUTF(), version = input.readUTF();
            if (length <= 0 || length > 256 * 1024 * 1024L || !checksum.matches("[a-f0-9]{64}")
                    || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+-byd\\.[0-9]+")) return;
            BydRuntimeFiles runtime = new BydRuntimeFiles(args[0]);
            if (!runtime.directory.isDirectory() || runtime.file("disabled").exists()
                    || runtime.file("idle-expired").exists()) return;
            staged = File.createTempFile("update-", ".apk", runtime.directory);
            if (!staged.setReadable(false, false) || !staged.setWritable(false, false)
                    || !staged.setReadable(true, true) || !staged.setWritable(true, true)) return;
            try (FileOutputStream file = new FileOutputStream(staged)) {
                BydUpdateTransfer.copy(input, file, length);
                file.getFD().sync();
            }
            Looper.prepareMainLooper();
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("systemMain").invoke(null);
            Context system = (Context) type.getMethod("getSystemContext").invoke(thread);
            Context target = system.createPackageContext(args[0], Context.CONTEXT_IGNORE_SECURITY);
            BydUpdateVerifier.verify(target, staged, checksum, version);
            Context shell = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            BydPlatform platform = new BydPlatform(shell);
            output.writeUTF("READY"); output.flush();
            // The app authorizes only after rechecking persisted opt-in and a fresh helper sample.
            // Read the actual vendor screen status; the Android interactive fallback is insufficient.
            final File verifiedApk = staged;
            output.writeUTF(BydUpdateInstallGate.execute(input.readUTF(),
                    () -> runtime.file("disabled").exists() || runtime.file("idle-expired").exists(),
                    platform::powerLevel, platform::screenStatus, () -> {
                        BydShell.Result result = BydShell.run(120,
                                "/system/bin/pm", "install", "-r", verifiedApk.getPath());
                        return result.exitCode == 0 && result.output.trim().equals("Success");
                    }));
            output.flush();
        } catch (Exception ignored) {
            // The app retains the verified download and offers Android's normal installer.
            // Never log tokens, command output, APK paths or installation credentials.
        } finally {
            if (staged != null) staged.delete();
        }
    }
}
