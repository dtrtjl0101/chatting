package chatting.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FileStorageService {
    private static final long AVATAR_LIMIT = 5L * 1024 * 1024;
    private static final long ATTACHMENT_LIMIT = 20L * 1024 * 1024;
    private final Path root;

    /** 설정된 업로드 폴더를 절대 경로로 정규화해 저장 위치를 준비한다. */
    public FileStorageService(@Value("${app.upload-dir:uploads}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    /** 이미지 형식과 5MB 제한을 적용해 프로필 사진을 저장한다. */
    public StoredFile storeAvatar(MultipartFile file) {
        return store(file, true);
    }

    /** 이미지 또는 음성 형식과 20MB 제한을 적용해 채팅 첨부 파일을 저장한다. */
    public StoredFile storeAttachment(MultipartFile file) {
        return store(file, false);
    }

    /** 저장 키의 형식과 경계 경로를 확인한 뒤 파일 Resource를 반환한다. */
    public Resource load(String key) {
        if (key == null || !key.matches("[0-9a-fA-F-]{36}\\.(jpg|png|gif|mp3|wav|ogg|webm|m4a)")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            Path path = root.resolve(key).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            return new UrlResource(path.toUri());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /** 업로드 크기·MIME 형식·파일 시그니처를 검사하고 무작위 이름으로 실제 파일을 저장한다. */
    private StoredFile store(MultipartFile file, boolean avatar) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "파일을 선택해 주세요.");
        long limit = avatar ? AVATAR_LIMIT : ATTACHMENT_LIMIT;
        if (file.getSize() > limit) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, avatar ? "프로필 사진은 5MB 이하로 올려주세요." : "첨부 파일은 20MB 이하로 올려주세요.");
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String extension = extensionFor(contentType, avatar);
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(16);
            if (!matchesSignature(contentType, header)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 파일 형식이거나 파일 내용이 올바르지 않습니다.");
            Files.createDirectories(root);
            String key = UUID.randomUUID() + "." + extension;
            Path target = root.resolve(key).normalize();
            if (!target.startsWith(root)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            try (InputStream content = file.getInputStream()) {
                Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String original = file.getOriginalFilename() == null ? "file." + extension : file.getOriginalFilename().replace('\\', '/');
            String filename = original.substring(original.lastIndexOf('/') + 1);
            if (filename.length() > 255) filename = filename.substring(filename.length() - 255);
            return new StoredFile(key, filename, contentType, file.getSize());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "파일을 저장하지 못했습니다.");
        }
    }

    /** 허용된 MIME 형식에 맞는 파일 확장자를 고르고 금지 형식은 오류로 거부한다. */
    private String extensionFor(String contentType, boolean avatar) {
        return switch (contentType) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "audio/mpeg", "audio/mp3" -> avatar ? unsupported() : "mp3";
            case "audio/wav", "audio/x-wav", "audio/wave" -> avatar ? unsupported() : "wav";
            case "audio/ogg", "application/ogg" -> avatar ? unsupported() : "ogg";
            case "audio/webm" -> avatar ? unsupported() : "webm";
            case "audio/mp4", "audio/x-m4a" -> avatar ? unsupported() : "m4a";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, avatar ? "JPG, PNG, GIF 이미지 파일만 올릴 수 있습니다." : "JPG, PNG, GIF 이미지 또는 MP3, WAV, OGG, WebM, M4A 음성 파일만 올릴 수 있습니다.");
        };
    }

    /** 프로필 사진 자리에 음성 형식이 입력됐을 때 명확한 400 오류를 발생시킨다. */
    private String unsupported() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로필 사진은 이미지 파일만 올릴 수 있습니다."); }

    /** 확장자나 브라우저 MIME 선언만 믿지 않고 파일 시작 바이트가 실제 형식과 맞는지 확인한다. */
    private boolean matchesSignature(String type, byte[] b) {
        return switch (type) {
            case "image/jpeg", "image/jpg" -> b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
            case "image/png" -> b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "image/gif" -> b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
            case "audio/mpeg", "audio/mp3" -> b.length >= 3 && ((b[0] == 'I' && b[1] == 'D' && b[2] == '3') || ((b[0] & 0xff) == 0xff && (b[1] & 0xe0) == 0xe0));
            case "audio/wav", "audio/x-wav", "audio/wave" -> b.length >= 12 && ascii(b, 0, "RIFF") && ascii(b, 8, "WAVE");
            case "audio/ogg", "application/ogg" -> b.length >= 4 && ascii(b, 0, "OggS");
            case "audio/webm" -> b.length >= 4 && (b[0] & 0xff) == 0x1a && (b[1] & 0xff) == 0x45 && (b[2] & 0xff) == 0xdf && (b[3] & 0xff) == 0xa3;
            case "audio/mp4", "audio/x-m4a" -> b.length >= 8 && ascii(b, 4, "ftyp");
            default -> false;
        };
    }

    /** 파일 바이트의 지정 위치가 주어진 ASCII 표식과 같은지 비교한다. */
    private boolean ascii(byte[] bytes, int offset, String value) {
        if (bytes.length < offset + value.length()) return false;
        for (int i = 0; i < value.length(); i++) if (bytes[offset + i] != value.charAt(i)) return false;
        return true;
    }

    /** 저장된 파일의 내부 키, 원래 이름, MIME 형식, 크기를 서비스 계층에 전달한다. */
    public record StoredFile(String key, String originalName, String contentType, long size) {}
}
