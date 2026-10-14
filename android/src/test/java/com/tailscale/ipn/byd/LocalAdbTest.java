// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import static org.junit.Assert.*;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Exercises the client against a fake adbd on an ephemeral port; never contacts the car. */
public class LocalAdbTest {
    @Test public void newlyAuthorizedKeyLaunchesDetachedCommand() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            Future<?> daemon = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    assertEquals(0x4e584e43, read(socket).getInt(0));
                    send(socket, 0x48545541, 1, 0, new byte[20]);
                    ByteBuffer signature = read(socket);
                    assertEquals(0x48545541, signature.getInt(0));
                    assertEquals(2, signature.getInt(4));
                    assertEquals(256, signature.getInt(12));
                    send(socket, 0x48545541, 1, 0, new byte[20]);
                    ByteBuffer publicKey = read(socket);
                    assertEquals(3, publicKey.getInt(4));
                    assertTrue(publicKey.getInt(12) > 524);
                    send(socket, 0x4e584e43, 0x01000000, 4096, "device::\0".getBytes(StandardCharsets.UTF_8));
                    ByteBuffer open = read(socket);
                    assertEquals(0x4e45504f, open.getInt(0));
                    assertEquals(1, open.getInt(4));
                    assertEquals("shell:launch-helper\0", new String(open.array(), 24,
                            open.getInt(12), StandardCharsets.UTF_8));
                    send(socket, 0x59414b4f, 37, 1, new byte[0]);
                    send(socket, 0x45545257, 37, 1, "started\n".getBytes(StandardCharsets.UTF_8));
                    ByteBuffer ack = read(socket);
                    assertEquals(0x59414b4f, ack.getInt(0));
                    assertEquals(1, ack.getInt(4));
                    assertEquals(37, ack.getInt(8));
                    send(socket, 0x45534c43, 37, 1, new byte[0]);
                    assertEquals(0x45534c43, read(socket).getInt(0));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            LocalAdb.launch(new AdbKey(generator.generateKeyPair()), "launch-helper", server.getLocalPort());
            daemon.get(5, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }

    private static ByteBuffer read(Socket socket) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        byte[] header = new byte[24];
        input.readFully(header);
        ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int length = fields.getInt(12);
        assertTrue(length >= 0 && length <= 4096);
        assertEquals(~fields.getInt(0), fields.getInt(20));
        byte[] packet = new byte[24 + length];
        System.arraycopy(header, 0, packet, 0, 24);
        input.readFully(packet, 24, length);
        return ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void send(Socket socket, int command, int arg0, int arg1, byte[] payload) throws Exception {
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        int checksum = 0;
        for (byte b : payload) checksum += b & 255;
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length).putInt(checksum).putInt(~command);
        socket.getOutputStream().write(header.array());
        socket.getOutputStream().write(payload);
        socket.getOutputStream().flush();
    }
}
