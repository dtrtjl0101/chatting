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

    /** OAuth 제공자와 제공자 사용자 ID로 기존 앱 사용자 ID를 찾는다. */
    public Optional<String> findIdByOAuth(String provider, String subject) {
        return jdbc.query("SELECT user_id FROM oauth_accounts WHERE provider=? AND provider_subject=?",
                rs -> rs.next() ? Optional.of(rs.getString("user_id")) : Optional.empty(), provider, subject);
    }

    /** 사용자 ID로 프로필과 화면에서 쓸 프로필 사진 URL을 조회한다. */
    public Optional<Map<String, Object>> findProfile(UUID id) {
        return jdbc.query("""
                SELECT id, display_name AS displayName, user_code AS userCode,
                       CASE WHEN profile_image_path IS NULL THEN '' ELSE CONCAT('/api/users/', id, '/avatar') END AS avatarUrl
                FROM app_users WHERE id=?
                """, rs -> rs.next() ? Optional.of(Map.of(
                        "id", rs.getString("id"),
                        "displayName", rs.getString("displayName"),
                        "userCode", rs.getString("userCode"),
                        "avatarUrl", rs.getString("avatarUrl"))) : Optional.empty(), id.toString());
    }

    /** 앱 사용자 테이블에 해당 ID가 존재하는지 확인한다. */
    public boolean exists(UUID id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM app_users WHERE id=?", Integer.class, id.toString());
        return count != null && count > 0;
    }

    /** 전체 코드가 일치하면 그 사용자를 우선 반환하고, 없으면 코드 앞부분과 닉네임으로 후보를 검색한다. */
    public List<Map<String, Object>> searchByNameOrExactCode(UUID requester, String query) {
        return jdbc.queryForList("""
                SELECT id, display_name AS displayName, user_code AS userCode,
                       CASE WHEN profile_image_path IS NULL THEN '' ELSE CONCAT('/api/users/', id, '/avatar') END AS avatarUrl
                FROM app_users
                WHERE id<>? AND (
                    user_code=? OR (
                        NOT EXISTS (
                            SELECT 1 FROM app_users exact_code
                            WHERE exact_code.id<>? AND exact_code.user_code=?
                        ) AND (user_code LIKE CONCAT(?, '%') OR display_name LIKE ?)
                    )
                )
                ORDER BY CASE WHEN user_code=? THEN 0 WHEN user_code LIKE CONCAT(?, '%') THEN 1 ELSE 2 END,
                         display_name LIMIT 30
                """, requester.toString(), query, requester.toString(), query, query, "%" + query + "%", query, query);
    }

    /** OAuth 최초 로그인 사용자의 기본 프로필 행을 생성한다. */
    public void insertUser(UUID id, String displayName, String userCode) {
        jdbc.update("INSERT INTO app_users(id, display_name, user_code) VALUES (?, ?, ?)", id.toString(), displayName, userCode);
    }

    /** OAuth 계정과 앱 사용자 연결을 추가하며 이미 연결됐으면 중복 삽입을 막는다. */
    public void insertOAuthAccountIfAbsent(String provider, String subject, UUID userId) {
        jdbc.update("INSERT IGNORE INTO oauth_accounts(provider, provider_subject, user_id) VALUES (?, ?, ?)", provider, subject, userId.toString());
    }

    /** 사용자 닉네임만 수정한다. */
    public void updateDisplayName(UUID id, String displayName) {
        jdbc.update("UPDATE app_users SET display_name=? WHERE id=?", displayName, id.toString());
    }

    /** 본인을 제외한 다른 사용자가 같은 사용자 코드를 쓰는지 확인한다. */
    public boolean userCodeTaken(UUID id, String userCode) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM app_users WHERE user_code=? AND id<>?", Integer.class, userCode, id.toString());
        return count != null && count > 0;
    }

    /** 사용자 닉네임과 사용자 코드를 함께 수정한다. */
    public void updateProfile(UUID id, String displayName, String userCode) {
        jdbc.update("UPDATE app_users SET display_name=?, user_code=? WHERE id=?", displayName, userCode, id.toString());
    }

    /** 업로드된 프로필 사진의 저장 경로를 사용자 정보에 기록한다. */
    public void updateAvatarPath(UUID id, String path) {
        jdbc.update("UPDATE app_users SET profile_image_path=? WHERE id=?", path, id.toString());
    }

    /** 사용자 프로필 사진 저장 경로를 조회한다. */
    public Optional<String> findAvatarPath(UUID id) {
        return jdbc.query("SELECT profile_image_path FROM app_users WHERE id=?",
                rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty(), id.toString());
    }

    /** 앱 사용자 행을 삭제한다. 연결된 OAuth 계정 등은 DB 외래 키 정책을 따른다. */
    public void delete(UUID id) { jdbc.update("DELETE FROM app_users WHERE id=?", id.toString()); }
}
