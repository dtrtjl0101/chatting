package chatting;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private final JsonMapper json;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, String> names = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(JsonMapper json) { this.json = json; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(new ConcurrentWebSocketSessionDecorator(session, 10_000, 64 * 1024));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode input = json.readTree(message.getPayload());
        if (!names.containsKey(session.getId())) {
            String name = input.path("name").asText("익명").strip();
            if (name.isBlank()) name = "익명";
            name = name.substring(0, Math.min(name.length(), 24));
            names.put(session.getId(), name);
            broadcast(system(name + "님이 들어왔어요."));
            broadcast(count());
            return;
        }
        String text = input.path("text").asText("").strip();
        if (text.isBlank()) return;
        if (text.length() > 1000) text = text.substring(0, 1000);
        broadcast(json.createObjectNode().put("type", "message")
                .put("name", names.get(session.getId())).put("text", text).put("time", now()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.removeIf(s -> s.getId().equals(session.getId()));
        String name = names.remove(session.getId());
        if (name != null) {
            broadcast(system(name + "님이 나갔어요."));
            broadcast(count());
        }
    }

    private ObjectNode system(String text) {
        return json.createObjectNode().put("type", "system").put("text", text).put("time", now());
    }
    private ObjectNode count() { return json.createObjectNode().put("type", "count").put("count", names.size()); }
    private String now() { return LocalTime.now(ZoneId.of("Asia/Seoul")).format(TIME); }

    private void broadcast(JsonNode data) {
        TextMessage message;
        message = new TextMessage(json.writeValueAsString(data));
        for (WebSocketSession session : sessions) {
            try { if (session.isOpen()) session.sendMessage(message); }
            catch (IOException e) { try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) { } }
        }
    }
}
