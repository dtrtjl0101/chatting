package chatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import chatting.service.ChatService;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler implements MessageListener {
    private final JsonMapper json;
    private final ChatService chats;
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> joinedRooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(JsonMapper json, ChatService chats, StringRedisTemplate redis) {
        this.json = json; this.chats = chats; this.redis = redis;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws IOException {
        Object userId = raw.getAttributes().get("appUserId");
        if (userId == null) { raw.close(CloseStatus.NOT_ACCEPTABLE.withReason("로그인이 필요합니다.")); return; }
        raw.getAttributes().put("userId", UUID.fromString(userId.toString()));
        activeSessions.put(raw.getId(), new ConcurrentWebSocketSessionDecorator(raw, 10_000, 64 * 1024));
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws IOException {
        WebSocketSession session = activeSessions.get(raw.getId());
        if (session == null) { raw.close(CloseStatus.SERVER_ERROR); return; }
        JsonNode input = json.readTree(message.getPayload());
        UUID userId = (UUID) raw.getAttributes().get("userId");
        if (userId == null) { raw.close(CloseStatus.NOT_ACCEPTABLE); return; }
        if (input.path("type").asText().equals("join")) {
            UUID roomId;
            try { roomId = UUID.fromString(input.path("conversationId").asText()); }
            catch (RuntimeException ex) { session.sendMessage(new TextMessage(error("대화방 ID가 올바르지 않습니다."))); return; }
            try { chats.requireMember(roomId, userId); }
            catch (ResponseStatusException ex) { session.sendMessage(new TextMessage(error("이 대화방의 참여자가 아닙니다."))); session.close(CloseStatus.POLICY_VIOLATION); return; }
            UUID previous = joinedRooms.put(session.getId(), roomId);
            if (previous != null) {
                leave(previous, session);
                removePresence(previous, userId.toString(), session.getId());
                broadcastCount(previous);
            }
            rooms.computeIfAbsent(roomId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
            addPresence(roomId, userId.toString(), session.getId());
            send(session, json.createObjectNode().put("type", "joined").put("conversationId", roomId.toString()));
            broadcastCount(roomId);
            return;
        }
        UUID roomId = joinedRooms.get(session.getId());
        if (roomId == null) { session.sendMessage(new TextMessage(error("먼저 대화방에 들어가세요."))); return; }
        try { chats.requireMember(roomId, userId); }
        catch (ResponseStatusException ex) { session.sendMessage(new TextMessage(error("이 대화방의 참여자가 아닙니다."))); session.close(CloseStatus.POLICY_VIOLATION); return; }
        var saved = chats.saveMessage(roomId, userId, input.path("text").asText(""));
        if (saved == null) return;
        var output = json.valueToTree(saved);
        var envelope = json.createObjectNode().put("type", "message").put("conversationId", roomId.toString());
        envelope.setAll((tools.jackson.databind.node.ObjectNode) output);
        redis.convertAndSend("chat:events", json.writeValueAsString(envelope));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            var event = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            UUID room = UUID.fromString(event.path("conversationId").asText());
            ((tools.jackson.databind.node.ObjectNode) event).remove("conversationId");
            broadcast(room, event);
        } catch (Exception ignored) { }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        activeSessions.remove(session.getId());
        UUID room = joinedRooms.remove(session.getId());
        if (room != null) {
            Object user = session.getAttributes().get("userId");
            if (user != null) removePresence(room, user.toString(), session.getId());
            leave(room, activeSessions.getOrDefault(session.getId(), session));
            broadcastCount(room);
        }
    }

    private void leave(UUID room, WebSocketSession session) {
        Set<WebSocketSession> members = rooms.get(room);
        if (members != null) { members.removeIf(candidate -> candidate.getId().equals(session.getId())); if (members.isEmpty()) rooms.remove(room, members); }
    }
    private void broadcastCount(UUID room) {
        String key = presenceKey(room);
        long now = System.currentTimeMillis();
        redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now);
        Set<String> activeUsers = redis.opsForZSet().range(key, 0, -1);
        long count = activeUsers == null ? 0 : activeUsers.stream().map(value -> value.substring(0, value.indexOf('|'))).distinct().count();
        var event = json.createObjectNode().put("type", "count").put("count", count).put("conversationId", room.toString());
        redis.convertAndSend("chat:events", json.writeValueAsString(event));
    }

    @Scheduled(fixedRate = 15_000)
    public void refreshPresence() {
        joinedRooms.forEach((sessionId, room) -> {
            WebSocketSession session = activeSessions.get(sessionId);
            if (session != null && session.isOpen()) {
                Object userId = session.getAttributes().get("userId");
                if (userId != null) addPresence(room, userId.toString(), sessionId);
            }
        });
        joinedRooms.values().stream().distinct().forEach(this::broadcastCount);
    }

    private void addPresence(UUID room, String user, String sessionId) {
        redis.opsForZSet().add(presenceKey(room), user + "|" + sessionId, System.currentTimeMillis() + 45_000);
    }
    private void removePresence(UUID room, String user, String sessionId) {
        redis.opsForZSet().remove(presenceKey(room), user + "|" + sessionId);
    }
    private String presenceKey(UUID room) { return "chat:presence:" + room; }
    private void broadcast(UUID room, tools.jackson.databind.JsonNode data) {
        TextMessage message = new TextMessage(json.writeValueAsString(data));
        for (WebSocketSession session : rooms.getOrDefault(room, Set.of())) send(session, message.getPayload());
    }
    private void send(WebSocketSession session, tools.jackson.databind.JsonNode data) { send(session, json.writeValueAsString(data)); }
    private void send(WebSocketSession session, String data) {
        try { if (session.isOpen()) session.sendMessage(new TextMessage(data)); }
        catch (IOException ex) { try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) { } }
    }
    private String error(String text) { return json.createObjectNode().put("type", "error").put("text", text).toString(); }
}
