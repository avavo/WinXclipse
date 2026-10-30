package com.winlator.cmod.contents;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class DownloaderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static class Reply implements AutoCloseable {
        final ServerSocket server;
        final Thread thread;
        volatile Exception failure;

        Reply(int status, long length, byte[] payload) throws Exception {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(5000);
            thread = new Thread(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(5000);
                    BufferedReader request = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    String line;
                    while ((line = request.readLine()) != null && !line.isEmpty()) {}
                    OutputStream out = client.getOutputStream();
                    String headers = "HTTP/1.1 " + status + " Response\r\nConnection: close\r\n"
                            + (length >= 0 ? "Content-Length: " + length + "\r\n" : "") + "\r\n";
                    out.write(headers.getBytes(StandardCharsets.US_ASCII));
                    out.write(payload);
                    out.flush();
                }
                catch (Exception error) { failure = error; }
            }, "test-download-server");
            thread.setDaemon(true);
            thread.start();
        }

        String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/package.wcp"; }
        @Override public void close() throws Exception {
            thread.join(6000);
            server.close();
            assertFalse("HTTP fixture did not finish", thread.isAlive());
            if (failure != null) throw failure;
        }
    }

    @Test public void publishesOnlyTheCompletePackage() throws Exception {
        byte[] payload = new byte[150000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte)i;
        File file = new File(temp.getRoot(), "new/package.wcp");
        List<Integer> progress = new ArrayList<>();
        try (Reply reply = new Reply(200, payload.length, payload)) {
            assertTrue(Downloader.downloadFile(reply.url(), file, progress::add));
        }
        assertArrayEquals(payload, Files.readAllBytes(file.toPath()));
        assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
        assertEquals(1, java.util.Collections.frequency(progress, 100));
        assertEquals(1, file.getParentFile().list().length);
    }

    @Test public void truncatedResponsePreservesThePreviousCompleteFile() throws Exception {
        File file = temp.newFile("package.wcp");
        Files.write(file.toPath(), new byte[]{9, 8, 7});
        List<Integer> progress = new ArrayList<>();
        try (Reply reply = new Reply(200, 1000, new byte[100])) {
            assertFalse(Downloader.downloadFile(reply.url(), file, progress::add));
        }
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(file.toPath()));
        assertFalse(progress.contains(100));
        assertEquals(1, temp.getRoot().list().length);
    }

    @Test public void rejectsEmptySuccess() throws Exception {
        File file = new File(temp.getRoot(), "package.wcp");
        try (Reply reply = new Reply(200, 0, new byte[0])) {
            assertFalse(Downloader.downloadFile(reply.url(), file));
        }
        assertFalse(file.exists());
    }

    @Test public void refusesUnexpectedPartialOrErrorResponse() throws Exception {
        for (int status : new int[]{206, 404}) {
            File file = new File(temp.getRoot(), "package-" + status + ".wcp");
            try (Reply reply = new Reply(status, 3, new byte[]{1, 2, 3})) {
                assertFalse(Downloader.downloadFile(reply.url(), file));
            }
            assertFalse(file.exists());
        }
    }

    @Test public void supportsNonEmptyResponseWithoutContentLength() throws Exception {
        File file = new File(temp.getRoot(), "package.wcp");
        try (Reply reply = new Reply(200, -1, new byte[]{1, 2, 3})) {
            assertTrue(Downloader.downloadFile(reply.url(), file));
        }
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(file.toPath()));
    }
}
