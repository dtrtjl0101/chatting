package chatting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class FileStorageServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void storesPngWithGeneratedKeyAndOriginalName() throws Exception {
        byte[] pngHeader = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        var upload = new MockMultipartFile("file", "profile.png", "image/png", pngHeader);
        var storage = new FileStorageService(tempDirectory.toString());

        var stored = storage.storeAvatar(upload);

        assertTrue(stored.key().matches("[0-9a-fA-F-]{36}\\.png"));
        assertEquals("profile.png", stored.originalName());
        assertTrue(Files.isRegularFile(tempDirectory.resolve(stored.key())));
    }

    @Test
    void rejectsImageWhenFileSignatureDoesNotMatchMimeType() {
        var upload = new MockMultipartFile("file", "fake.png", "image/png", "not a png".getBytes());
        var storage = new FileStorageService(tempDirectory.toString());

        assertThrows(ResponseStatusException.class, () -> storage.storeAvatar(upload));
    }

    @Test
    void rejectsPathTraversalAsStoredKey() {
        var storage = new FileStorageService(tempDirectory.toString());

        assertThrows(ResponseStatusException.class, () -> storage.load("../secret.png"));
    }
}
