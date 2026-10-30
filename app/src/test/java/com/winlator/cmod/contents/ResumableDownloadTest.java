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
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ResumableDownloadTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    interface Handler { void reply(Map<String, String> headers, OutputStream out) throws Exception; }
    static class Server implements AutoCloseable {
        final ServerSocket socket;
        final Thread worker;
        volatile Throwable failure;

        Server(Handler... handlers) throws Exception {
            socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            socket.setSoTimeout(5000);
            worker = new Thread(() -> {
                try {
                    for (Handler handler : handlers) {
                        try (Socket client = socket.accept()) {
                            client.setSoTimeout(5000);
                            BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream()));
                            Map<String, String> headers = new HashMap<>();
                            String line;
                            while ((line = input.readLine()) != null && !line.isEmpty()) {
                                int split = line.indexOf(':');
                                if (split > 0) headers.put(line.substring(0, split).toLowerCase(java.util.Locale.ROOT),
                                        line.substring(split + 1).trim());
                            }
                            try { handler.reply(headers, client.getOutputStream()); }
                            catch (SocketException ignored) { /* Intentional pause/cancel closes the client. */ }
                        }
                    }
                }
                catch (Throwable error) { failure = error; }
            }, "resumable-download-test");
            worker.setDaemon(true);
            worker.start();
        }

        String url() { return "http://127.0.0.1:" + socket.getLocalPort() + "/runtime.wcp"; }
        @Override public void close() throws Exception {
            worker.join(6000);
            socket.close();
            assertFalse("HTTP fixture did not finish", worker.isAlive());
            if (failure != null) throw new AssertionError(failure);
        }
    }

    private static void respond(OutputStream out, int status, long length, String extra, byte[] bytes) throws Exception {
        out.write(("HTTP/1.1 " + status + " Response\r\nConnection: close\r\nContent-Length: " + length
                + "\r\n" + extra + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private ResumableDownload.Result run(String url, File output) {
        return ResumableDownload.download(url, output, new ResumableDownload.Control(), null);
    }

    @Test public void resumesTruncatedPackageUsingTheSameIdentity() throws Exception {
        byte[] payload = new byte[200000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        File output = new File(temp.getRoot(), "runtime.wcp");
        try (Server server = new Server(
                (headers, out) -> respond(out, 200, payload.length, "ETag: \"v1\"\r\n", Arrays.copyOf(payload, 12345)),
                (headers, out) -> {
                    assertEquals("bytes=12345-", headers.get("range"));
                    assertEquals("\"v1\"", headers.get("if-range"));
                    respond(out, 206, payload.length - 12345, "ETag: \"v1\"\r\nContent-Range: bytes 12345-199999/200000\r\n",
                            Arrays.copyOfRange(payload, 12345, payload.length));
                })) {
            assertEquals(ResumableDownload.Result.FAILED, run(server.url(), output));
            assertFalse(output.exists());
            assertTrue(ResumableDownload.hasSavedDownload(server.url(), output));
            assertEquals(ResumableDownload.Result.COMPLETE, run(server.url(), output));
        }
        assertArrayEquals(payload, Files.readAllBytes(output.toPath()));
    }

    @Test public void rangeIgnoredRestartsInsteadOfAppending() throws Exception {
        File output = new File(temp.getRoot(), "runtime.wcp");
        try (Server server = new Server(
                (headers, out) -> respond(out, 200, 10, "ETag: \"v1\"\r\n", new byte[]{1, 2, 3}),
                (headers, out) -> {
                    assertEquals("bytes=3-", headers.get("range"));
                    respond(out, 200, 4, "ETag: \"v2\"\r\n", new byte[]{8, 7, 6, 5});
                })) {
            assertEquals(ResumableDownload.Result.FAILED, run(server.url(), output));
            assertEquals(ResumableDownload.Result.COMPLETE, run(server.url(), output));
        }
        assertArrayEquals(new byte[]{8, 7, 6, 5}, Files.readAllBytes(output.toPath()));
    }

    @Test public void changedValidatorOnPartialRequiresFreshResponse() throws Exception {
        File output = new File(temp.getRoot(), "runtime.wcp");
        try (Server server = new Server(
                (headers, out) -> respond(out, 200, 5, "ETag: \"v1\"\r\n", new byte[]{1, 2}),
                (headers, out) -> respond(out, 206, 3, "ETag: \"v2\"\r\nContent-Range: bytes 2-4/5\r\n", new byte[]{9, 9, 9}),
                (headers, out) -> {
                    assertNull(headers.get("range"));
                    respond(out, 200, 5, "ETag: \"v2\"\r\n", new byte[]{9, 9, 9, 9, 9});
                })) {
            assertEquals(ResumableDownload.Result.FAILED, run(server.url(), output));
            assertEquals(ResumableDownload.Result.COMPLETE, run(server.url(), output));
        }
        assertArrayEquals(new byte[]{9, 9, 9, 9, 9}, Files.readAllBytes(output.toPath()));
    }

    @Test public void malformedRangeCannotReplacePreviousCompleteFile() throws Exception {
        File output = temp.newFile("runtime.wcp");
        Files.write(output.toPath(), new byte[]{42});
        try (Server server = new Server(
                (headers, out) -> respond(out, 200, 5, "ETag: \"v1\"\r\n", new byte[]{1, 2}),
                (headers, out) -> respond(out, 206, 3, "ETag: \"v1\"\r\nContent-Range: bytes 0-2/5\r\n", new byte[]{3, 4, 5}))) {
            assertEquals(ResumableDownload.Result.FAILED, run(server.url(), output));
            assertEquals(ResumableDownload.Result.FAILED, run(server.url(), output));
        }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(output.toPath()));
    }

    @Test public void pausePersistsBytesForANewControlAfterRestart() throws Exception {
        byte[] payload = new byte[512000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
        File output = new File(temp.getRoot(), "runtime.wcp");
        ResumableDownload.Control control = new ResumableDownload.Control();
        try (Server server = new Server(
                (headers, out) -> respond(out, 200, payload.length, "ETag: \"same\"\r\n", payload),
                (headers, out) -> {
                    int offset = Integer.parseInt(headers.get("range").replace("bytes=", "").replace("-", ""));
                    assertTrue(offset > 0 && offset < payload.length);
                    respond(out, 206, payload.length - offset, "ETag: \"same\"\r\nContent-Range: bytes " + offset
                            + "-511999/512000\r\n", Arrays.copyOfRange(payload, offset, payload.length));
                })) {
            assertEquals(ResumableDownload.Result.PAUSED,
                    ResumableDownload.download(server.url(), output, control, percent -> control.pause()));
            assertFalse(output.exists());
            assertTrue(ResumableDownload.hasSavedDownload(server.url(), output));
            assertEquals(ResumableDownload.Result.COMPLETE, run(server.url(), output));
        }
        assertArrayEquals(payload, Files.readAllBytes(output.toPath()));
    }

    @Test public void cancelRemovesSavedPartialAndReceipt() throws Exception {
        File output = new File(temp.getRoot(), "runtime.wcp");
        ResumableDownload.Control control = new ResumableDownload.Control();
        try (Server server = new Server((headers, out) -> respond(out, 200, 100000, "ETag: \"v1\"\r\n", new byte[100000]))) {
            assertEquals(ResumableDownload.Result.CANCELLED,
                    ResumableDownload.download(server.url(), output, control, percent -> control.cancel()));
            assertFalse(ResumableDownload.hasSavedDownload(server.url(), output));
        }
        assertEquals(0, temp.getRoot().list().length);
    }

    @Test public void completedPackageCanBeOpenedWithoutDownloadingAgain() throws Exception {
        File output = new File(temp.getRoot(), "runtime.wcp");
        try (Server server = new Server((headers, out) -> respond(out, 200, 3, "ETag: \"v1\"\r\n", new byte[]{1, 2, 3}))) {
            assertEquals(ResumableDownload.Result.COMPLETE, run(server.url(), output));
            assertEquals(ResumableDownload.Result.COMPLETE, run(server.url(), output));
        }
    }

    @Test public void noValidatorRequiresStartingFromZero() throws Exception {
        File output = new File(temp.getRoot(), "runtime.wcp");
        try (Server server = new Server(
                (headers, out) -> respond(out, 200, 5, "", new byte[]{1, 2}),
                (headers, out) -> {
                    assertNull(headers.get("range"));
                    respond(out, 200, 3, "", new byte[]{7, 8, 9});
                })) {
            assertEquals(ResumableDownload.Result.FAILED, run(server.url(), output));
            assertEquals(ResumableDownload.Result.COMPLETE, run(server.url(), output));
        }
        assertArrayEquals(new byte[]{7, 8, 9}, Files.readAllBytes(output.toPath()));
    }

    @Test public void expiredRangeRetriesOnceWithoutRange() throws Exception {
        File output = new File(temp.getRoot(), "runtime.wcp");
        try (Server server = new Server(
                (headers, out) -> respond(out, 200, 5, "ETag: \"v1\"\r\n", new byte[]{1, 2}),
                (headers, out) -> respond(out, 416, 0, "Content-Range: bytes */1\r\n", new byte[0]),
                (headers, out) -> {
                    assertNull(headers.get("range"));
                    respond(out, 200, 1, "ETag: \"v2\"\r\n", new byte[]{7});
                })) {
            assertEquals(ResumableDownload.Result.FAILED, run(server.url(), output));
            assertEquals(ResumableDownload.Result.COMPLETE, run(server.url(), output));
        }
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(output.toPath()));
    }
}
