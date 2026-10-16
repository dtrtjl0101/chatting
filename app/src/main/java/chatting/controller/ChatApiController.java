package chatting.controller;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import chatting.service.ChatService;
import chatting.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ChatApiController {
    private final ChatService chats;
    private final UserService users;

    public ChatApiController(ChatService chats, UserService users) {
        this.chats = chats;
        this.users = users;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }

    @GetMapping("/me")
    public Map<String, Object> me(@SessionAttribute(name = "appUserId", required = false) String id) {
        if (id == null) return Map.of("authenticated", false);
        Map<String, Object> profile = users.findProfile(UUID.fromString(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        Map<String, Object> response = new HashMap<>(profile);
        response.put("authenticated", true);
        return response;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(@SessionAttribute("appUserId") String id,
                                           @RequestParam(defaultValue = "") String q) {
        return chats.searchUsers(UUID.fromString(id), q);
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations(@SessionAttribute("appUserId") String id) {
        return chats.listConversations(UUID.fromString(id));
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createConversation(@SessionAttribute("appUserId") String id,
                                                   @RequestBody CreateConversationRequest request) {
        return chats.createDirectConversation(UUID.fromString(id), request.otherUserId(), request.title());
    }

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

    public record CreateConversationRequest(String otherUserId, String title) {}
}
