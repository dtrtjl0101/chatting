package chatting.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import chatting.repository.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {
    private final UserRepository users;

    /** OAuth 사용자 정보를 조회·저장할 Repository를 주입받는다. */
    public UserService(UserRepository users) { this.users = users; }

    /** OAuth 제공자와 계정 식별자로 기존 앱 사용자를 찾거나 새 사용자를 만든다. */
    @Transactional
    public UUID getOrCreateOAuthUser(String provider, String subject, String displayName) {
        Optional<String> found = users.findIdByOAuth(provider, subject);
        if (found.isPresent()) {
            return UUID.fromString(found.get());
        }

        UUID newId = UUID.randomUUID();
        users.insertUser(newId, displayName, newId.toString().replace("-", ""));
        users.insertOAuthAccountIfAbsent(provider, subject, newId);
        UUID actualId = UUID.fromString(users.findIdByOAuth(provider, subject).orElseThrow());
        if (!actualId.equals(newId)) users.delete(newId);
        users.updateDisplayName(actualId, displayName);
        return actualId;
    }

    /** 사용자 ID로 닉네임, 사용자 코드, 프로필 사진 주소를 조회한다. */
    public Optional<Map<String, Object>> findProfile(UUID userId) { return users.findProfile(userId); }

    /** 저장 전 입력 중인 사용자 코드의 형식과 중복 여부를 확인한다. */
    public Map<String, Object> checkUserCode(UUID userId, String rawCode) {
        String code = rawCode == null ? "" : rawCode.strip().toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("[A-Z0-9_-]{3,32}")) {
            return Map.of("available", false, "message", "영문, 숫자, _, - 조합으로 3~32자를 입력해 주세요.");
        }
        boolean available = !users.userCodeTaken(userId, code);
        return Map.of("available", available, "message", available ? "사용할 수 있는 사용자 코드입니다." : "이미 사용 중인 사용자 코드입니다. 다른 코드를 입력해 주세요.");
    }

    /** 프로필 입력을 검증하고 사용자 코드의 고유성을 보장한 뒤 DB에 저장한다. */
    @Transactional
    public Map<String, Object> updateProfile(UUID userId, String rawName, String rawCode) {
        String name = rawName == null ? "" : rawName.strip();
        String code = rawCode == null ? "" : rawCode.strip().toUpperCase(java.util.Locale.ROOT);//대문자로사용
        if (name.isBlank() || name.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "닉네임은 1~80자로 입력해 주세요.");
        if (!code.matches("[A-Z0-9_-]{3,32}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사용자 코드는 영문, 숫자, _, - 조합으로 3~32자 입력해 주세요.");
        if (users.userCodeTaken(userId, code)) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 사용자 코드입니다. 다른 코드를 입력해 주세요.");
        try {
            users.updateProfile(userId, name, code);//동시에 저장할려는 상황을 방지
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 사용자 코드입니다. 다른 코드를 입력해 주세요.");
        }
        return users.findProfile(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** 사용자의 프로필 사진 파일 키를 DB에 기록한다. */
    public void updateAvatar(UUID userId, String path) { users.updateAvatarPath(userId, path); }

    /** 사용자 ID에 연결된 프로필 사진 경로를 조회하고 없으면 404 오류를 낸다. */
    public String findAvatarPath(UUID userId) {
        return users.findAvatarPath(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "프로필 사진이 없습니다."));
    }
}
