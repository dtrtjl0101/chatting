package chatting.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import chatting.repository.ConversationRepository;
import chatting.repository.MessageRepository;
import chatting.repository.UserRepository;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {
    private final UserRepository users;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final FileStorageService files;

    /** 사용자·대화방·메시지 저장소와 파일 저장 서비스를 주입받는다. */
    public ChatService(UserRepository users, ConversationRepository conversations, MessageRepository messages, FileStorageService files) {
        this.users = users;
        this.conversations = conversations;
        this.messages = messages;
        this.files = files;
    }

    /** 대화 상대 검색어를 정리한 뒤 본인을 제외한 사용자 검색 결과를 반환한다. */
    public List<Map<String, Object>> searchUsers(UUID requester, String query) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.isBlank()) return List.of();
        return users.searchByDisplayName(requester, normalized);
    }

    /** 사용자가 참여 중인 대화방 요약 목록을 반환한다. */
    public List<Map<String, Object>> listConversations(UUID userId) { return conversations.findAllForUser(userId); }

    /** 참여자를 검증하고 1:1 또는 그룹 대화방과 구성원을 생성한다. */
    @Transactional
    public CreatedConversation createConversation(UUID creator, List<String> requestedMembers, String fallbackOtherId, String requestedTitle) {
        List<String> ids = requestedMembers == null ? new ArrayList<>() : new ArrayList<>(requestedMembers);
        if (ids.isEmpty() && fallbackOtherId != null && !fallbackOtherId.isBlank()) ids.add(fallbackOtherId);
        LinkedHashSet<UUID> otherMembers = new LinkedHashSet<>();
        for (String value : ids) {
            try { otherMembers.add(UUID.fromString(value)); }
            catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사용자 ID가 올바르지 않습니다."); }
        }
        if (otherMembers.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "대화에 초대할 사용자를 선택해 주세요.");
        if (otherMembers.contains(creator)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자기 자신은 초대할 수 없습니다.");
        if (otherMembers.size() > 49) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "대화방에는 최대 50명까지 참여할 수 있습니다.");
        for (UUID member : otherMembers) {
            if (!users.exists(member)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }

        boolean direct = otherMembers.size() == 1;
        String title = requestedTitle == null ? "" : requestedTitle.strip();
        if (title.isBlank()) {
            if (direct) title = conversations.findDisplayName(otherMembers.iterator().next());
            else title = conversations.findDisplayName(otherMembers.iterator().next()) + " 외 " + (otherMembers.size() - 1) + "명";
        }
        if (title.length() > 100) title = title.substring(0, 100);

        UUID roomId = UUID.randomUUID();
        String directKey;
        if (direct) {
            UUID other = otherMembers.iterator().next();
            String first = creator.toString().compareTo(other.toString()) < 0 ? creator.toString() : other.toString();
            String second = creator.toString().compareTo(other.toString()) < 0 ? other.toString() : creator.toString();
            directKey = first + ":" + second;
        } else {
            directKey = "group:" + roomId;
        }
        conversations.insertIfAbsent(roomId, title, directKey, direct ? "DIRECT" : "GROUP", creator);
        String conversationId = conversations.findIdByDirectKey(directKey);
        conversations.addMemberIfAbsent(conversationId, creator);
        for (UUID member : otherMembers) conversations.addMemberIfAbsent(conversationId, member);
        UUID actualRoomId = UUID.fromString(conversationId);
        Map<String, Object> summary = conversations.findSummary(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "대화방을 만들지 못했습니다."));
        return new CreatedConversation(summary, conversations.findMemberIds(actualRoomId));
    }

    /** 참여자만 지정 대화방의 최근 메시지 또는 시각 이후 메시지를 읽게 한다. */
    public List<Map<String, Object>> getMessages(UUID conversationId, UUID requester, Instant since) {
        requireMember(conversationId, requester);
        return since == null ? messages.findLatest(conversationId) : messages.findSince(conversationId, since);
    }

    /** 참여 권한을 확인하고 일반 텍스트 메시지를 MySQL에 저장한다. */
    @Transactional
    public Map<String, Object> saveMessage(UUID conversationId, UUID senderId, String rawBody) {
        requireMember(conversationId, senderId);
        String body = rawBody == null ? "" : rawBody.strip();
        if (body.isBlank()) return null;
        if (body.length() > 4000) body = body.substring(0, 4000);
        return messages.insertAndFind(UUID.randomUUID(), conversationId, senderId, body);
    }

    /** 참여 권한을 확인하고 검증된 첨부 파일과 메시지 메타데이터를 저장한다. */
    @Transactional
    public Map<String, Object> saveAttachment(UUID conversationId, UUID senderId, MultipartFile upload) {
        requireMember(conversationId, senderId);
        FileStorageService.StoredFile file = files.storeAttachment(upload);
        String kind = file.contentType().startsWith("image/") ? "IMAGE" : "AUDIO";
        return messages.insertAttachmentAndFind(UUID.randomUUID(), conversationId, senderId, kind,
                file.originalName(), file.key(), file.originalName(), file.contentType(), file.size());
    }

    /** 방 참여자만 메시지에 연결된 첨부 파일을 읽도록 권한을 검사한다. */
    public AttachmentResource getAttachment(UUID conversationId, UUID messageId, UUID requester) {
        requireMember(conversationId, requester);
        MessageRepository.Attachment attachment = messages.findAttachment(conversationId, messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "첨부 파일을 찾을 수 없습니다."));
        return new AttachmentResource(files.load(attachment.path()), attachment.name(), attachment.contentType());
    }

    /** 프로필 이미지 저장을 파일 저장 서비스에 위임한다. */
    public FileStorageService.StoredFile storeAvatar(MultipartFile file) { return files.storeAvatar(file); }
    /** 저장된 프로필 이미지의 안전한 파일 리소스를 반환한다. */
    public org.springframework.core.io.Resource loadAvatar(String key) { return files.load(key); }

    /** 요청자가 방 구성원인지 검사하고 아니면 403 오류를 발생시킨다. */
    public void requireMember(UUID conversationId, UUID userId) {
        if (!conversations.isMember(conversationId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "대화방 참여자만 접근할 수 있습니다.");
        }
    }

    /** 생성된 대화방 요약과 생성 알림을 받을 참여자 ID 목록을 함께 반환한다. */
    public record CreatedConversation(Map<String, Object> summary, List<UUID> memberIds) {}
    /** 참여자 확인 후 파일 다운로드에 필요한 파일 리소스와 응답 메타데이터를 전달한다. */
    public record AttachmentResource(Resource resource, String name, String contentType) {}
}
