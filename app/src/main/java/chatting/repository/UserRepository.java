package chatting.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<String> findIdByOAuth(String provider, String subject) {
        return jdbc.query("SELECT user_id FROM oauth_accounts WHERE provider=? AND provider_subject=?",
                rs -> rs.next() ? Optional.of(rs.getString("user_id")) : Optional.empty(), provider, subject);
    }

    public Optional<Map<String, Object>> findProfile(UUID id) {
        return jdbc.query("SELECT id, display_name AS displayName FROM app_users WHERE id=?",
                rs -> rs.next() ? Optional.of(Map.of("id", rs.getString("id"), "displayName", rs.getString("displayName"))) : Optional.empty(), id.toString());
    }

    public boolean exists(UUID id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM app_users WHERE id=?", Integer.class, id.toString());
        return count != null && count > 0;
    }

    public List<Map<String, Object>> searchByDisplayName(UUID requester, String query) {
        return jdbc.queryForList("SELECT id, display_name AS displayName FROM app_users WHERE id<>? AND (display_name LIKE ? OR id LIKE ?) ORDER BY display_name LIMIT 20",
                requester.toString(), "%" + query + "%", query + "%");
    }

    public void insertUser(UUID id, String displayName) {
        jdbc.update("INSERT INTO app_users(id, display_name) VALUES (?, ?)", id.toString(), displayName);
    }

    public void insertOAuthAccountIfAbsent(String provider, String subject, UUID userId) {
        jdbc.update("INSERT IGNORE INTO oauth_accounts(provider, provider_subject, user_id) VALUES (?, ?, ?)", provider, subject, userId.toString());
    }

    public void updateDisplayName(UUID id, String displayName) {
        jdbc.update("UPDATE app_users SET display_name=? WHERE id=?", displayName, id.toString());
    }

    public void delete(UUID id) { jdbc.update("DELETE FROM app_users WHERE id=?", id.toString()); }
}
