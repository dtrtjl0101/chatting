package chatting.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {
    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> findLatest(UUID conversationId) {
        return jdbc.queryForList("""
            SELECT m.id, m.sender_id AS senderId, u.display_name AS senderName, m.body AS text,
                   m.created_at AS createdAt
            FROM (SELECT * FROM messages WHERE conversation_id=? ORDER BY created_at DESC, id DESC LIMIT 100) m
            JOIN app_users u ON u.id=m.sender_id ORDER BY m.created_at ASC, m.id ASC
            """, conversationId.toString());
    }

    public List<Map<String, Object>> findSince(UUID conversationId, Instant since) {
        return jdbc.queryForList("""
            SELECT m.id, m.sender_id AS senderId, u.display_name AS senderName, m.body AS text,
                   m.created_at AS createdAt
            FROM messages m JOIN app_users u ON u.id=m.sender_id
            WHERE m.conversation_id=? AND m.created_at>=? ORDER BY m.created_at ASC, m.id ASC LIMIT 500
            """, conversationId.toString(), Timestamp.from(since));
    }

    public Map<String, Object> insertAndFind(UUID messageId, UUID conversationId, UUID senderId, String body) {
        jdbc.update("INSERT INTO messages(id,conversation_id,sender_id,body) VALUES (?,?,?,?)",
                messageId.toString(), conversationId.toString(), senderId.toString(), body);
        return jdbc.queryForMap("""
            SELECT m.id, m.sender_id AS senderId, u.display_name AS senderName, m.body AS text,
                   m.created_at AS createdAt
            FROM messages m JOIN app_users u ON u.id=m.sender_id WHERE m.id=?
            """, messageId.toString());
    }
}
