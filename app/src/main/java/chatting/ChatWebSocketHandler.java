package chatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
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
public class ChatWebSocketHandler extends TextWebSocketHandler implements MessageListener {//웹소켓 연결을 처리하는 핸들러로, TextWebSocketHandler를 상속받아 텍스트 메시지를 처리하고, MessageListener를 구현하여 Redis 메시지를 수신
    private final JsonMapper json;
    private final ChatService chats;
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();//대화방별 접속자 연결
    private final ConcurrentHashMap<String, UUID> joinedRooms = new ConcurrentHashMap<>();//세션별 접속한 대화방
    private final ConcurrentHashMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();//세션 ID별 연결된 WebSocketSession

    public ChatWebSocketHandler(JsonMapper json, ChatService chats, StringRedisTemplate redis) {//생성자주입
        this.json = json; this.chats = chats; this.redis = redis;
    }

    /** 로그인 세션을 확인하고 WebSocket 연결을 이 서버 인스턴스의 활성 세션에 등록한다. */
    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws IOException {//웹소켓 연결이 성립됐을때의 초기설정
        Object userId = raw.getAttributes().get("appUserId");
        if (userId == null) { raw.close(CloseStatus.NOT_ACCEPTABLE.withReason("로그인이 필요합니다.")); return; }
        raw.getAttributes().put("userId", UUID.fromString(userId.toString()));
        activeSessions.put(raw.getId(), new ConcurrentWebSocketSessionDecorator(raw, 10_000, 64 * 1024));//WebSocket 세션에 동시에 메시지를 보내는 상황 등을 처리하기 위한 래퍼
    }

    /** 브라우저의 방 입장 요청과 채팅 텍스트를 처리해 권한 확인 후 저장한다. */
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
                leave(previous, session);//이전 대화방에서 나간 것으로 처리
                removePresence(previous, userId.toString(), session.getId());//이전 대화방에서 나간 것으로 처리
                broadcastCount(previous);//기존 대화방의 참여자 수를 갱신
            }
            rooms.computeIfAbsent(roomId, ignored -> ConcurrentHashMap.newKeySet()).add(session);//rooms에 해당 방이 있으면 기존 세션 집합을 가져와.해당 방이 없다면 새로운 동시성 세트를 만들어 넣어.마지막으로 현재 세션을 그 집합에 추가해.
            addPresence(roomId, userId.toString(), session.getId());
            send(session, json.createObjectNode().put("type", "joined").put("conversationId", roomId.toString()));
            broadcastCount(roomId);
            return;
        }
        UUID roomId = joinedRooms.get(session.getId());
        if (roomId == null) { session.sendMessage(new TextMessage(error("먼저 대화방에 들어가세요."))); return; }
        try { chats.requireMember(roomId, userId); }
        catch (ResponseStatusException ex) { session.sendMessage(new TextMessage(error("이 대화방의 참여자가 아닙니다."))); session.close(CloseStatus.POLICY_VIOLATION); return; }
        Map<String, Object> saved;
        try { saved = chats.saveMessage(roomId, userId, input.path("text").asText("")); }
        catch (ResponseStatusException ex) {
            session.sendMessage(new TextMessage(error(ex.getReason() == null ? "메시지를 보낼 수 없습니다." : ex.getReason())));
            return;
        }
        if (saved == null) return;
        var output = json.valueToTree(saved);
        var envelope = json.createObjectNode().put("type", "message").put("conversationId", roomId.toString());
        envelope.setAll((tools.jackson.databind.node.ObjectNode) output);
        redis.convertAndSend("chat:events", json.writeValueAsString(envelope));
    }

    /** Redis 이벤트를 수신해 방 생성 알림은 사용자에게, 채팅 이벤트는 방 세션에 전달한다. */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            var event = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            if (event.path("type").asText().equals("memberLeft")) {
                UUID roomId = UUID.fromString(event.path("conversationId").asText());
                String userId = event.path("userId").asText();
                for (var joined : joinedRooms.entrySet()) {
                    if (!joined.getValue().equals(roomId)) continue;
                    WebSocketSession session = activeSessions.get(joined.getKey());
                    if (session == null || !userId.equals(String.valueOf(session.getAttributes().get("userId")))) continue;
                    if (!joinedRooms.remove(joined.getKey(), roomId)) continue;
                    removePresence(roomId, userId, joined.getKey());
                    leave(roomId, session);
                    send(session, json.createObjectNode().put("type", "roomLeft").put("conversationId", roomId.toString()));
                }
                broadcastCount(roomId);
                return;
            }
            if (event.path("type").asText().equals("conversationClosed")) {
                UUID roomId = UUID.fromString(event.path("conversationId").asText());
                List<String> recipients = new java.util.ArrayList<>();
                event.path("recipientUserIds").forEach(value -> recipients.add(value.asText()));
                ((tools.jackson.databind.node.ObjectNode) event).remove("recipientUserIds");
                String payload = json.writeValueAsString(event);
                Set<WebSocketSession> targets = new LinkedHashSet<>(rooms.getOrDefault(roomId, Set.of()));
                activeSessions.values().stream()
                        .filter(session -> recipients.contains(String.valueOf(session.getAttributes().get("userId"))))
                        .forEach(targets::add);
                targets.forEach(session -> send(session, payload));
                removeRoomSubscriptions(roomId);
                return;
            }
            if (event.path("type").asText().equals("roomCreated")) {
                List<String> recipients = new java.util.ArrayList<>();
                event.path("recipientUserIds").forEach(value -> recipients.add(value.asText()));
                ((tools.jackson.databind.node.ObjectNode) event).remove("recipientUserIds");
                String payload = json.writeValueAsString(event);
                activeSessions.values().stream()
                        .filter(session -> recipients.contains(String.valueOf(session.getAttributes().get("userId"))))
                        .forEach(session -> send(session, payload));
                return;
            }
            UUID room = UUID.fromString(event.path("conversationId").asText());
            // 브라우저가 현재 방의 메시지·접속자 수 이벤트인지 구분할 수 있도록 conversationId를 유지한다.
            broadcast(room, event);
        } catch (Exception ignored) { }
    }

    /** 새 대화방 정보를 Redis로 발행해 참여자가 연결된 모든 앱 슬롯에 알린다. */
    public void publishRoomCreated(UUID creator, List<UUID> memberIds, Map<String, Object> summary) {
        var event = json.createObjectNode().put("type", "roomCreated").put("createdBy", creator.toString());
        event.set("conversation", json.valueToTree(summary));
        event.set("recipientUserIds", json.valueToTree(memberIds.stream().map(UUID::toString).toList()));
        redis.convertAndSend("chat:events", json.writeValueAsString(event));
    }

    /** 저장된 일반 메시지나 첨부 메시지를 Redis Pub/Sub으로 모든 슬롯에 전달한다. */
    public void publishMessage(UUID conversationId, Map<String, Object> savedMessage) {
        var event = json.createObjectNode().put("type", "message").put("conversationId", conversationId.toString());
        event.setAll((tools.jackson.databind.node.ObjectNode) json.valueToTree(savedMessage));
        redis.convertAndSend("chat:events", json.writeValueAsString(event));
    }

    /** 참여자가 방을 나갔음을 Redis로 발행해 blue/green 양쪽 연결과 접속자 수를 정리한다. */
    public void publishMemberLeft(UUID conversationId, UUID userId) {
        var event = json.createObjectNode().put("type", "memberLeft")
                .put("conversationId", conversationId.toString()).put("userId", userId.toString());
        redis.convertAndSend("chat:events", json.writeValueAsString(event));
    }

    /** 종료된 1:1 대화방의 남은 참여자들에게 채팅 종료 알림을 전달한다. */
    public void publishConversationClosed(UUID conversationId, List<UUID> recipientIds) {
        var event = json.createObjectNode().put("type", "conversationClosed")
                .put("conversationId", conversationId.toString())
                .put("notice", "상대방이 방을 나갔습니다. 더 이상 대화할 수 없는 상태입니다.");
        event.set("recipientUserIds", json.valueToTree(recipientIds.stream().map(UUID::toString).toList()));
        redis.convertAndSend("chat:events", json.writeValueAsString(event));
    }

    /** 종료된 방의 각 앱 슬롯 WebSocket 구독과 접속자 수 기록을 모두 제거한다. */
    private void removeRoomSubscriptions(UUID roomId) {
        for (var joined : joinedRooms.entrySet()) {
            if (!joined.getValue().equals(roomId) || !joinedRooms.remove(joined.getKey(), roomId)) continue;
            WebSocketSession session = activeSessions.get(joined.getKey());
            if (session == null) continue;
            Object userId = session.getAttributes().get("userId");
            if (userId != null) removePresence(roomId, userId.toString(), joined.getKey());
            leave(roomId, session);
        }
        broadcastCount(roomId);
    }

    /** WebSocket 연결이 끝나면 세션과 접속자 수 기록을 정리한다. */
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

    /** 이 서버 인스턴스의 방 연결 목록에서 세션을 제거한다. */
    private void leave(UUID room, WebSocketSession session) {
        Set<WebSocketSession> members = rooms.get(room);
        if (members != null) { members.removeIf(candidate -> candidate.getId().equals(session.getId())); if (members.isEmpty()) rooms.remove(room, members); }
    }
    /** Redis에 기록된 활성 접속자를 세어 방에 현재 접속자 수 이벤트를 발행한다. */
    private void broadcastCount(UUID room) {
        String key = presenceKey(room);
        long now = System.currentTimeMillis();
        redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now);
        Set<String> activeUsers = redis.opsForZSet().range(key, 0, -1);
        long count = activeUsers == null ? 0 : activeUsers.stream().map(value -> value.substring(0, value.indexOf('|'))).distinct().count();
        var event = json.createObjectNode().put("type", "count").put("count", count).put("conversationId", room.toString());
        redis.convertAndSend("chat:events", json.writeValueAsString(event));
    }

    /** 접속 기록 만료를 막고 각 연결 방의 인원 수를 주기적으로 갱신한다. */
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

    /** 사용자와 WebSocket 세션 조합을 일정 시간 후 만료될 Redis 접속 목록에 추가한다. */
    private void addPresence(UUID room, String user, String sessionId) {
        redis.opsForZSet().add(presenceKey(room), user + "|" + sessionId, System.currentTimeMillis() + 45_000);
    }
    /** 닫히거나 다른 방으로 이동한 세션의 Redis 접속 기록을 제거한다. */
    private void removePresence(UUID room, String user, String sessionId) {
        redis.opsForZSet().remove(presenceKey(room), user + "|" + sessionId);
    }
    /** 대화방 접속자 집합에 사용하는 Redis 키를 만든다. */
    private String presenceKey(UUID room) { return "chat:presence:" + room; }
    /** 이 서버 인스턴스에서 해당 방을 연 모든 WebSocket 세션에 이벤트를 보낸다. */
    private void broadcast(UUID room, tools.jackson.databind.JsonNode data) {
        TextMessage message = new TextMessage(json.writeValueAsString(data));
        for (WebSocketSession session : rooms.getOrDefault(room, Set.of())) send(session, message.getPayload());
    }
    /** JSON 이벤트를 한 WebSocket 세션에 전송한다. */
    private void send(WebSocketSession session, tools.jackson.databind.JsonNode data) { send(session, json.writeValueAsString(data)); }
    /** 문자열 이벤트를 열려 있는 WebSocket 세션에 보내고 실패한 연결을 닫는다. */
    private void send(WebSocketSession session, String data) {
        try { if (session.isOpen()) session.sendMessage(new TextMessage(data)); }
        catch (IOException ex) { try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) { } }
    }
    /** 브라우저에 표시할 WebSocket 오류 이벤트 JSON을 만든다. */
    private String error(String text) { return json.createObjectNode().put("type", "error").put("text", text).toString(); }
}
//https://chatgpt.com/s/t_6ad3c48fddd88191b9c023687f68ac87 웹소켓이해하기
