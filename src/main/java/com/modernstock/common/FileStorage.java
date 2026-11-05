package com.modernstock.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Pictures people add (product photos, the business logo), kept on disk under app.files.dir with a random name, and
 * served at /public/files/{folder}/{name}. Only JPEG, PNG and WebP up to 2 MB; the type is checked from the file's
 * first bytes, not its name. Names are random, so a file can only be opened by someone who was given its link.
 */
@Service
public class FileStorage {

    public static final long MAX_BYTES = 2L * 1024 * 1024;
    /** Bank transfer screenshots (FR-10.14): never served at /public/files, only to the Owner and the super admin. */
    public static final String PRIVATE_TRANSFERS = "transfers";
    private static final Map<String, String> TYPES = Map.of("jpg", "image/jpeg", "png", "image/png", "webp", "image/webp");

    private final Path root;

    public FileStorage(@Value("${app.files.dir:.data/files}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /** Saves an image in a folder ("products", "logos"); returns "folder/name.ext" for the database. */
    public String saveImage(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("FILE_TOO_BIG", 2);
        }
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(12);
            String ext = imageType(head);
            if (ext == null) {
                throw ApiException.badRequest("FILE_NOT_IMAGE");
            }
            String name = folder + "/" + UUID.randomUUID() + "." + ext;
            Path target = resolve(name).orElseThrow();
            Files.createDirectories(target.getParent());
            try (InputStream all = file.getInputStream()) {
                Files.copy(all, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return name;
        } catch (IOException e) {
            throw new IllegalStateException("Could not save the file", e);
        }
    }

    public void delete(String name) {
        if (name == null) {
            return;
        }
        resolve(name).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // an orphan file does no harm
            }
        });
    }

    /** The file for a stored name, if it exists inside the files folder. */
    public Optional<Path> resolve(String name) {
        Path path = root.resolve(name).normalize();
        return path.startsWith(root) ? Optional.of(path) : Optional.empty();
    }

    public static String contentType(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1);
        return TYPES.getOrDefault(ext, "application/octet-stream");
    }

    /** "/public/files/..." for a stored name (the app puts the API address in front), or null. */
    public static String url(String name) {
        return name == null ? null : "/public/files/" + name;
    }

    private static String imageType(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return "png";
        }
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
