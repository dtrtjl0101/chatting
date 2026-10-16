package chatting.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import chatting.repository.ConversationRepository;
import chatting.repository.MessageRepository;
import chatting.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {
    private final UserRepository users;
    private final ConversationRepository conversations;
    private final MessageRepository messages;

    public ChatService(UserRepository users, ConversationRepository conversations, MessageRepository messages) {
        this.users = users;
        this.conversations = conversations;
        this.messages = messages;
    }

    public List<Map<String, Object>> searchUsers(UUID requester, String query) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.isBlank()) return List.of();
        return users.searchByDisplayName(requester, normalized);
    }

    public List<Map<String, Object>> listConversations(UUID userId) {
        return conversations.findAllForUser(userId);
    }

    @Transactional
    public Map<String, Object> createDirectConversation(UUID creator, String otherUserId, String requestedTitle) {
        UUID other;
        try { other = UUID.fromString(otherUserId); }
        catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사용자 ID가 올바르지 않습니다."); }
        if (creator.equals(other)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자기 자신과 대화방을 만들 수 없습니다.");
        if (!users.exists(other)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.");

        String title = requestedTitle == null ? "" : requestedTitle.strip();
        if (title.isBlank()) title = conversations.findDisplayName(other);
        if (title.length() > 100) title = title.substring(0, 100);

        String first = creator.toString().compareTo(other.toString()) < 0 ? creator.toString() : other.toString();
        String second = creator.toString().compareTo(other.toString()) < 0 ? other.toString() : creator.toString();
        String directKey = first + ":" + second;
        conversations.insertIfAbsent(UUID.randomUUID(), title, directKey, creator);
        String conversationId = conversations.findIdByDirectKey(directKey);
        conversations.addMemberIfAbsent(conversationId, creator);
        conversations.addMemberIfAbsent(conversationId, other);
        return conversations.findSummary(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "대화방을 만들지 못했습니다."));
    }

    public List<Map<String, Object>> getMessages(UUID conversationId, UUID requester, Instant since) {
        requireMember(conversationId, requester);
        return since == null ? messages.findLatest(conversationId) : messages.findSince(conversationId, since);
    }

    @Transactional
    public Map<String, Object> saveMessage(UUID conversationId, UUID senderId, String rawBody) {
        requireMember(conversationId, senderId);
        String body = rawBody == null ? "" : rawBody.strip();
        if (body.isBlank()) return null;
        if (body.length() > 4000) body = body.substring(0, 4000);
        return messages.insertAndFind(UUID.randomUUID(), conversationId, senderId, body);
    }

    public void requireMember(UUID conversationId, UUID userId) {
        if (!conversations.isMember(conversationId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "대화방 참여자만 접근할 수 있습니다.");
        }
    }
}
