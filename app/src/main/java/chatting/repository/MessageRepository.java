package chatting.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {
    private static final String MESSAGE_FIELDS = """
            SELECT m.id, m.sender_id AS senderId, u.display_name AS senderName,
                   CASE WHEN u.profile_image_path IS NULL THEN '' ELSE CONCAT('/api/users/', u.id, '/avatar') END AS senderAvatarUrl,
                   CASE WHEN m.message_type='IMAGE' THEN '사진' WHEN m.message_type='AUDIO' THEN '음성 메시지' ELSE m.body END AS text, m.message_type AS messageType,
                   CASE WHEN m.attachment_path IS NULL THEN '' ELSE CONCAT('/api/conversations/', m.conversation_id, '/attachments/', m.id) END AS attachmentUrl,
                   COALESCE(m.attachment_name, '') AS attachmentName,
                   COALESCE(m.attachment_content_type, '') AS attachmentContentType,
                   COALESCE(m.attachment_size, 0) AS attachmentSize, m.created_at AS createdAt
            FROM messages m JOIN app_users u ON u.id=m.sender_id
            """;

    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> findLatest(UUID conversationId) {
        return jdbc.queryForList("""
            SELECT m.id, m.sender_id AS senderId, u.display_name AS senderName,
                   CASE WHEN u.profile_image_path IS NULL THEN '' ELSE CONCAT('/api/users/', u.id, '/avatar') END AS senderAvatarUrl,
                   CASE WHEN m.message_type='IMAGE' THEN '사진' WHEN m.message_type='AUDIO' THEN '음성 메시지' ELSE m.body END AS text, m.message_type AS messageType,
                   CASE WHEN m.attachment_path IS NULL THEN '' ELSE CONCAT('/api/conversations/', m.conversation_id, '/attachments/', m.id) END AS attachmentUrl,
                   COALESCE(m.attachment_name, '') AS attachmentName,
                   COALESCE(m.attachment_content_type, '') AS attachmentContentType,
                   COALESCE(m.attachment_size, 0) AS attachmentSize, m.created_at AS createdAt
            FROM (SELECT * FROM messages WHERE conversation_id=? ORDER BY created_at DESC, id DESC LIMIT 100) m
            JOIN app_users u ON u.id=m.sender_id ORDER BY m.created_at ASC, m.id ASC
            """, conversationId.toString());
    }

    public List<Map<String, Object>> findSince(UUID conversationId, Instant since) {
        return jdbc.queryForList(MESSAGE_FIELDS + " WHERE m.conversation_id=? AND m.created_at>=? ORDER BY m.created_at ASC, m.id ASC LIMIT 500",
                conversationId.toString(), Timestamp.from(since));
    }

    public Map<String, Object> insertAndFind(UUID messageId, UUID conversationId, UUID senderId, String body) {
        jdbc.update("INSERT INTO messages(id,conversation_id,sender_id,body) VALUES (?,?,?,?)",
                messageId.toString(), conversationId.toString(), senderId.toString(), body);
        return jdbc.queryForMap(MESSAGE_FIELDS + " WHERE m.id=?", messageId.toString());
    }

    public Map<String, Object> insertAttachmentAndFind(UUID messageId, UUID conversationId, UUID senderId,
                                                        String messageType, String body, String path,
                                                        String name, String contentType, long size) {
        jdbc.update("""
                INSERT INTO messages(id,conversation_id,sender_id,body,message_type,attachment_path,attachment_name,attachment_content_type,attachment_size)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, messageId.toString(), conversationId.toString(), senderId.toString(), body,
                messageType, path, name, contentType, size);
        return jdbc.queryForMap(MESSAGE_FIELDS + " WHERE m.id=?", messageId.toString());
    }

    public Optional<Attachment> findAttachment(UUID conversationId, UUID messageId) {
        return jdbc.query("SELECT attachment_path, attachment_name, attachment_content_type FROM messages WHERE conversation_id=? AND id=? AND attachment_path IS NOT NULL",
                rs -> rs.next() ? Optional.of(new Attachment(rs.getString("attachment_path"), rs.getString("attachment_name"), rs.getString("attachment_content_type"))) : Optional.empty(),
                conversationId.toString(), messageId.toString());
    }

    public record Attachment(String path, String name, String contentType) {}
}
