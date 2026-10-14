// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Minimal authenticated ADB transport, limited to the head unit's own loopback interface. */
public final class LocalAdb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;

    public static void launch(AdbKey key, String command) throws Exception {
        launch(key, command, 5555, true);
    }

    public static void launch(AdbKey key, String command, boolean allowPairing) throws Exception {
        launch(key, command, 5555, allowPairing);
    }

    static void launch(AdbKey key, String command, int port) throws Exception {
        launch(key, command, port, true);
    }

    static void launch(AdbKey key, String command, int port, boolean allowPairing) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            socket.setSoTimeout(5000);
            send(socket, CNXN, 0x01000000, 4096, "host::tailscale-byd\0".getBytes(StandardCharsets.UTF_8));
            boolean signatureSent = false;
            boolean publicSent = false;
            for (int attempts = 0; attempts < 8; attempts++) {
                Packet packet = read(socket.getInputStream());
                if (packet.command == CNXN) {
                    shell(socket, command);
                    return;
                }
                if (packet.command != AUTH || packet.arg0 != 1) throw new IOException("Unexpected ADB handshake");
                if (!signatureSent) {
                    send(socket, AUTH, 2, 0, key.sign(packet.payload));
                    signatureSent = true;
                } else if (!publicSent && allowPairing) {
                    send(socket, AUTH, 3, 0, key.publicKey());
                    publicSent = true;
                } else throw new IOException("Allow the Android debugging request on the car display");
            }
            throw new IOException("Android debugging authorization is pending");
        }
    }

    private static void shell(Socket socket, String command) throws Exception {
        send(socket, OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = 0;
        for (int packets = 0; packets < 64; packets++) {
            Packet packet = read(socket.getInputStream());
            if (packet.arg1 != 1) throw new IOException("Unexpected ADB stream");
            if (packet.command == OKAY) remote = packet.arg0;
            else if (packet.command == WRTE) {
                if (remote == 0) remote = packet.arg0;
                send(socket, OKAY, 1, remote, new byte[0]);
            } else if (packet.command == CLSE) {
                send(socket, CLSE, 1, packet.arg0, new byte[0]);
                return;
            } else throw new IOException("Unexpected ADB shell response");
        }
        throw new IOException("ADB shell did not complete");
    }

    private static void send(Socket socket, int command, int arg0, int arg1, byte[] payload) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte b : payload) sum += b & 255;
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length).putInt(sum).putInt(~command);
        socket.getOutputStream().write(header.array());
        socket.getOutputStream().write(payload);
        socket.getOutputStream().flush();
    }

    private static Packet read(InputStream input) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(readFully(input, 24)).order(ByteOrder.LITTLE_ENDIAN);
        int command = header.getInt(), arg0 = header.getInt(), arg1 = header.getInt();
        int length = header.getInt(), checksum = header.getInt(), magic = header.getInt();
        if (magic != ~command || length < 0 || length > 1024 * 1024) throw new IOException("Invalid ADB packet");
        byte[] payload = readFully(input, length);
        int sum = 0;
        for (byte b : payload) sum += b & 255;
        // New ADB versions omit checksums; old versions still require the actual sum.
        if (checksum != 0 && checksum != sum) throw new IOException("Invalid ADB checksum");
        return new Packet(command, arg0, arg1, payload);
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(data, offset, length - offset);
            if (count < 0) throw new EOFException("ADB disconnected");
            offset += count;
        }
        return data;
    }

    private static final class Packet {
        final int command, arg0, arg1;
        final byte[] payload;
        Packet(int command, int arg0, int arg1, byte[] payload) {
            this.command = command; this.arg0 = arg0; this.arg1 = arg1; this.payload = payload;
        }
    }
}
