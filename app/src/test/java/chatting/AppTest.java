package chatting;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppTest {
    @Test
    void serverEchoesMessagesBackToClient() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (ServerSocket serverSocket = new ServerSocket(0);
             ChatServer server = new ChatServer(serverSocket)) {
            Future<?> serverTask = executor.submit(() -> {
                try {
                    server.serveOnce();
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });

            try (Socket client = new Socket("localhost", server.getPort());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write("hello");
                writer.newLine();
                writer.flush();

                assertEquals("echo: hello", reader.readLine());
            }

            serverTask.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
