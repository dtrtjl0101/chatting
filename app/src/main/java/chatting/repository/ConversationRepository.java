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

    /** 사용자가 참여한 방 목록과 화면 표시용 제목·인원·마지막 메시지를 조회한다. */
    public List<Map<String, Object>> findAllForUser(UUID userId) {
        String user = userId.toString();
        return jdbc.queryForList("""
            SELECT c.id, c.kind, CASE WHEN c.closed_at IS NULL THEN FALSE ELSE TRUE END AS closed,
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

    /** 같은 참여자 조합의 1:1 방이 이미 있는지 확인한다. */
    public boolean existsByDirectKey(String directKey) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM conversations WHERE direct_key=?", Integer.class, directKey);
        return count != null && count > 0;
    }

    /** ID와 생성 정보를 가진 방을 추가하며, 고유 키가 이미 있으면 중복 삽입을 건너뛴다. */
    public void insertIfAbsent(UUID id, String title, String directKey, String kind, UUID creator) {
        jdbc.update("INSERT IGNORE INTO conversations(id,title,direct_key,kind,created_by) VALUES (?,?,?,?,?)",
                id.toString(), title, directKey, kind, creator.toString());
    }

    /** 1:1 참여자 조합 키에 해당하는 방 ID를 조회한다. */
    public String findIdByDirectKey(String directKey) {
        return jdbc.queryForObject("SELECT id FROM conversations WHERE direct_key=?", String.class, directKey);
    }

    /** 사용자를 방 참여자로 등록하며 이미 등록되어 있으면 중복 추가하지 않는다. */
    public void addMemberIfAbsent(String conversationId, UUID userId) {
        jdbc.update("INSERT IGNORE INTO conversation_members(conversation_id,user_id) VALUES (?,?)", conversationId, userId.toString());
    }

    /** 사용자가 해당 방의 참여자인지 확인한다. */
    public boolean isMember(UUID conversationId, UUID userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM conversation_members WHERE conversation_id=? AND user_id=?",
                Integer.class, conversationId.toString(), userId.toString());
        return count != null && count > 0;
    }

    /** 대화방 참여자 테이블에서 사용자를 제거하고 실제 삭제 행 수를 반환한다. */
    public int removeMember(UUID conversationId, UUID userId) {
        return jdbc.update("DELETE FROM conversation_members WHERE conversation_id=? AND user_id=?",
                conversationId.toString(), userId.toString());
    }

    /** 대화방 종류를 조회해 1:1 종료와 그룹 개별 나가기를 구분한다. */
    public String findKind(UUID conversationId) {
        return jdbc.queryForObject("SELECT kind FROM conversations WHERE id=?", String.class, conversationId.toString());
    }

    /** 방 행을 잠그고 종료 상태인지 확인해 메시지 저장과 나가기 작업이 경합하지 않게 한다. */
    public boolean isClosedForUpdate(UUID conversationId) {
        Boolean closed = jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM conversations WHERE id=? FOR UPDATE",
                Boolean.class, conversationId.toString());
        return Boolean.TRUE.equals(closed);
    }

    /** 1:1 대화방을 종료하고 기존 고유 대화 키를 해제해 나중에 새 대화를 만들 수 있게 한다. */
    public int closeDirectConversation(UUID conversationId) {
        return jdbc.update("UPDATE conversations SET closed_at=CURRENT_TIMESTAMP(6), direct_key=CONCAT('closed:', id) WHERE id=? AND closed_at IS NULL",
                conversationId.toString());
    }

    /** 사용자 ID로 닉네임을 조회해 기본 방 제목을 만들 때 사용한다. */
    public String findDisplayName(UUID userId) {
        return jdbc.queryForObject("SELECT display_name FROM app_users WHERE id=?", String.class, userId.toString());
    }

    /** 생성 직후 클라이언트에 돌려줄 방 요약 정보와 참여 인원을 조회한다. */
    public Optional<Map<String, Object>> findSummary(String conversationId) {
        return jdbc.query("""
                SELECT c.id, c.title, c.kind,
                       CASE WHEN c.closed_at IS NULL THEN FALSE ELSE TRUE END AS closed,
                       (SELECT COUNT(*) FROM conversation_members cm WHERE cm.conversation_id=c.id) AS memberCount
                FROM conversations c WHERE c.id=?
                """, rs -> rs.next() ? Optional.of(Map.of(
                        "id", rs.getString("id"), "title", rs.getString("title"),
                        "kind", rs.getString("kind"), "closed", rs.getBoolean("closed"),
                        "memberCount", rs.getInt("memberCount"))) : Optional.empty(), conversationId);
    }

    /** 방 종료 알림을 보낼 대상 등으로 사용할 참여자 ID 목록을 조회한다. */
    public List<UUID> findMemberIds(UUID conversationId) {
        return jdbc.query("SELECT user_id FROM conversation_members WHERE conversation_id=?",
                (rs, rowNum) -> UUID.fromString(rs.getString("user_id")), conversationId.toString());
    }

    /** 대화방 참여자의 ID와 프로필 표시 정보를 이름순으로 반환한다. */
    public List<Map<String, Object>> findMembers(UUID conversationId) {
        return jdbc.queryForList("""
                SELECT u.id, u.display_name AS displayName, u.user_code AS userCode,
                       CASE WHEN u.profile_image_path IS NULL THEN '' ELSE CONCAT('/api/users/', u.id, '/avatar') END AS avatarUrl
                FROM conversation_members cm JOIN app_users u ON u.id=cm.user_id
                WHERE cm.conversation_id=? ORDER BY u.display_name
                """, conversationId.toString());
    }
}
