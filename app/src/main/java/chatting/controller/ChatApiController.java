package chatting.controller;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import chatting.ChatWebSocketHandler;
import chatting.service.ChatService;
import chatting.service.FileStorageService;
import chatting.service.UserService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ChatApiController {
    private final ChatService chats;
    private final UserService users;
    private final ChatWebSocketHandler events;//새 대화방이 생성됐다는 이벤트를 WebSocket을 통해 다른 사용자에게 알려주기 위해 추가

    public ChatApiController(ChatService chats, UserService users, ChatWebSocketHandler events) {
        this.chats = chats;
        this.users = users;
        this.events = events;
    }

    /** 브라우저가 이후 변경 요청에 첨부할 CSRF 토큰을 반환한다. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }

    /** 세션에 연결된 현재 사용자의 프로필을 반환한다. */
    @GetMapping("/me")
    public Map<String, Object> me(@SessionAttribute(name = "appUserId", required = false) String id) {
        if (id == null) return Map.of("authenticated", false);
        Map<String, Object> profile = users.findProfile(UUID.fromString(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        Map<String, Object> response = new HashMap<>(profile);
        response.put("authenticated", true);
        return response;
    }

    /** 현재 사용자의 닉네임과 사용자 코드를 수정한다. */
    @PutMapping("/me")
    public Map<String, Object> updateProfile(@SessionAttribute("appUserId") String id, @RequestBody ProfileRequest request) {
        return users.updateProfile(UUID.fromString(id), request.displayName(), request.userCode());
    }

    /** 사용자가 입력하는 동안 사용자 코드가 저장 가능한지 확인한다. */
    @GetMapping("/me/user-code-availability")
    public Map<String, Object> userCodeAvailability(@SessionAttribute("appUserId") String id,
                                                    @RequestParam String code) {
        return users.checkUserCode(UUID.fromString(id), code);
    }

    /** 업로드한 이미지 파일을 저장하고 현재 사용자의 프로필 사진 경로를 갱신한다. */
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadAvatar(@SessionAttribute("appUserId") String id, @RequestParam("file") MultipartFile file) {
        UUID userId = UUID.fromString(id);
        FileStorageService.StoredFile stored = chats.storeAvatar(file);
        users.updateAvatar(userId, stored.key());
        return users.findProfile(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** 로그인한 사용자에게 요청한 프로필 사진 파일을 응답한다. */
    @GetMapping("/users/{userId}/avatar")
    public ResponseEntity<Resource> avatar(@SessionAttribute("appUserId") String id, @PathVariable UUID userId) {
        users.findProfile(UUID.fromString(id)).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        String key = users.findAvatarPath(userId);
        Resource resource = chats.loadAvatar(key);
        return ResponseEntity.ok().contentType(MediaTypeFactorySafe.fromFilename(resource.getFilename()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300").body(resource);
    }

    /** 닉네임 또는 사용자 코드로 다른 사용자를 검색한다. */
    @GetMapping("/users")
    public List<Map<String, Object>> users(@SessionAttribute("appUserId") String id,
                                           @RequestParam(defaultValue = "") String q) {
        return chats.searchUsers(UUID.fromString(id), q);
    }

    /** 현재 사용자가 참여한 대화방 목록을 반환한다. */
    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations(@SessionAttribute("appUserId") String id) {
        return chats.listConversations(UUID.fromString(id));
    }

    /** 새 1:1 또는 그룹 대화방을 만들고 모든 참여자에게 실시간 생성 이벤트를 발행한다. */
    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createConversation(@SessionAttribute("appUserId") String id,
                                                   @RequestBody CreateConversationRequest request) {
        var created = chats.createConversation(UUID.fromString(id), request.memberIds(), request.otherUserId(), request.title());
        events.publishRoomCreated(UUID.fromString(id), created.memberIds(), created.summary());
        return created.summary();
    }

    /** 대화방 참여 권한을 확인한 뒤 메시지 기록을 반환한다. */
    @GetMapping("/conversations/{conversationId}/messages")
    public List<Map<String, Object>> messages(@SessionAttribute("appUserId") String id,
                                               @PathVariable UUID conversationId,
                                               @RequestParam(required = false) String since) {
        Instant cursor = null;
        if (since != null && !since.isBlank()) {
            try { cursor = Instant.parse(since); }
            catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시각 형식이 올바르지 않습니다."); }
        }
        return chats.getMessages(conversationId, UUID.fromString(id), cursor);
    }

    /** 첨부 파일을 메시지로 저장하고 해당 방에 실시간 메시지 이벤트를 보낸다. */
    @PostMapping(value = "/conversations/{conversationId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> uploadAttachment(@SessionAttribute("appUserId") String id,
                                                @PathVariable UUID conversationId,
                                                @RequestParam("file") MultipartFile file) {
        Map<String, Object> message = chats.saveAttachment(conversationId, UUID.fromString(id), file);
        events.publishMessage(conversationId, message);
        return message;
    }

    /** 대화방 참여자에게 저장된 첨부 파일을 반환한다. */
    @GetMapping("/conversations/{conversationId}/attachments/{messageId}")
    public ResponseEntity<Resource> attachment(@SessionAttribute("appUserId") String id,
                                                @PathVariable UUID conversationId,
                                                @PathVariable UUID messageId) throws IOException {
        ChatService.AttachmentResource attachment = chats.getAttachment(conversationId, messageId, UUID.fromString(id));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(attachment.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + attachment.name().replace("\"", "") + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600").body(attachment.resource());
    }

    public record ProfileRequest(String displayName, String userCode) {}
    public record CreateConversationRequest(List<String> memberIds, String otherUserId, String title) {}

    private static class MediaTypeFactorySafe {
        static MediaType fromFilename(String filename) {
            if (filename == null) return MediaType.APPLICATION_OCTET_STREAM;
            String lower = filename.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
            if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
            return MediaType.IMAGE_JPEG;
        }
    }
}
