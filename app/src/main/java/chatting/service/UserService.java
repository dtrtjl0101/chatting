package chatting.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import chatting.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;

    public UserService(UserRepository users) { this.users = users; }

    @Transactional
    public UUID getOrCreateOAuthUser(String provider, String subject, String displayName) {
        Optional<String> found = users.findIdByOAuth(provider, subject);
        if (found.isPresent()) {
            UUID id = UUID.fromString(found.get());
            users.updateDisplayName(id, displayName);
            return id;
        }

        UUID newId = UUID.randomUUID();
        users.insertUser(newId, displayName);
        users.insertOAuthAccountIfAbsent(provider, subject, newId);
        UUID actualId = UUID.fromString(users.findIdByOAuth(provider, subject).orElseThrow());
        if (!actualId.equals(newId)) users.delete(newId);
        users.updateDisplayName(actualId, displayName);
        return actualId;
    }

    public Optional<Map<String, Object>> findProfile(UUID userId) { return users.findProfile(userId); }
}
