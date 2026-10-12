package chatting;

import java.io.IOException;
import java.net.ServerSocket;

public class App {
    public static void main(String[] args) throws IOException {
        if (args.length == 0 || !"server".equals(args[0])) {
            System.out.println("Usage: server [port]");
            return;
        }

        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;

        try (ChatServer server = new ChatServer(new ServerSocket(port))) {
            System.out.println("Chat server listening on port " + server.getPort());
            server.serveOnce();
        }
    }
}
