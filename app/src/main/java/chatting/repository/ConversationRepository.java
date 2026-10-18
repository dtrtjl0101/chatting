package chatting.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {
    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> findAllForUser(UUID userId) {
        String user = userId.toString();
        return jdbc.queryForList("""
            SELECT c.id, c.kind,
                   CASE WHEN c.kind='GROUP' THEN c.title ELSE other.display_name END AS title,
                   CASE WHEN c.kind='GROUP' THEN '' ELSE COALESCE(other.display_name, '') END AS otherName,
                   (SELECT COUNT(*) FROM conversation_members all_members WHERE all_members.conversation_id=c.id) AS memberCount,
                   c.created_at AS createdAt,
                   (SELECT CASE WHEN m.message_type='IMAGE' THEN '사진' WHEN m.message_type='AUDIO' THEN '음성 메시지' ELSE m.body END FROM messages m WHERE m.conversation_id=c.id ORDER BY m.created_at DESC LIMIT 1) AS lastMessage
            FROM conversations c JOIN conversation_members cm ON cm.conversation_id=c.id
            LEFT JOIN conversation_members other_cm ON c.kind='DIRECT' AND other_cm.conversation_id=c.id AND other_cm.user_id<>cm.user_id
            LEFT JOIN app_users other ON other.id=other_cm.user_id
            WHERE cm.user_id=? ORDER BY COALESCE((SELECT MAX(m.created_at) FROM messages m WHERE m.conversation_id=c.id), c.created_at) DESC
            """, user);
    }

    public boolean existsByDirectKey(String directKey) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM conversations WHERE direct_key=?", Integer.class, directKey);
        return count != null && count > 0;
    }

    public void insertIfAbsent(UUID id, String title, String directKey, String kind, UUID creator) {
        jdbc.update("INSERT IGNORE INTO conversations(id,title,direct_key,kind,created_by) VALUES (?,?,?,?,?)",
                id.toString(), title, directKey, kind, creator.toString());
    }

    public String findIdByDirectKey(String directKey) {
        return jdbc.queryForObject("SELECT id FROM conversations WHERE direct_key=?", String.class, directKey);
    }

    public void addMemberIfAbsent(String conversationId, UUID userId) {
        jdbc.update("INSERT IGNORE INTO conversation_members(conversation_id,user_id) VALUES (?,?)", conversationId, userId.toString());
    }

    public boolean isMember(UUID conversationId, UUID userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM conversation_members WHERE conversation_id=? AND user_id=?",
                Integer.class, conversationId.toString(), userId.toString());
        return count != null && count > 0;
    }

    public String findDisplayName(UUID userId) {
        return jdbc.queryForObject("SELECT display_name FROM app_users WHERE id=?", String.class, userId.toString());
    }

    public Optional<Map<String, Object>> findSummary(String conversationId) {
        return jdbc.query("""
                SELECT c.id, c.title, c.kind,
                       (SELECT COUNT(*) FROM conversation_members cm WHERE cm.conversation_id=c.id) AS memberCount
                FROM conversations c WHERE c.id=?
                """, rs -> rs.next() ? Optional.of(Map.of(
                        "id", rs.getString("id"), "title", rs.getString("title"),
                        "kind", rs.getString("kind"), "memberCount", rs.getInt("memberCount"))) : Optional.empty(), conversationId);
    }

    public List<UUID> findMemberIds(UUID conversationId) {
        return jdbc.query("SELECT user_id FROM conversation_members WHERE conversation_id=?",
                (rs, rowNum) -> UUID.fromString(rs.getString("user_id")), conversationId.toString());
    }
}
