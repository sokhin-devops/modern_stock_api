package com.modernstock.common;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Pictures people add (product photos, the business logo), kept with a random name and served at
 * /public/files/{folder}/{name}. Only JPEG, PNG and WebP up to 2 MB; the type is checked from the file's first bytes,
 * not its name. Names are random, so a file can only be opened by someone who was given its link.
 * <p>
 * They are kept on disk under app.files.dir (a PC, the tests), or in an S3 bucket when app.files.s3.bucket is set:
 * Supabase Storage in the cloud, where the server's own disk is wiped on every deploy (DevSecOps.md C4). The bucket
 * stays private and the API serves the files itself, so the rules below are the same either way.
 */
@Service
public class FileStorage {

    public static final long MAX_BYTES = 2L * 1024 * 1024;
    /** Bank transfer screenshots (FR-10.14): never served at /public/files, only to the Owner and the super admin. */
    public static final String PRIVATE_TRANSFERS = "transfers";
    /** Photos of expense receipts and supplier invoices: never public, served by the expense and bill endpoints. */
    public static final String PRIVATE_DOCUMENTS = "documents";
    private static final Map<String, String> TYPES = Map.of("jpg", "image/jpeg", "png", "image/png", "webp", "image/webp");
    /** The names saveImage gives: "folder/uuid.ext". Anything else is not ours (S3 keys have no folder to escape). */
    private static final Pattern STORED_NAME = Pattern.compile("[a-z]+/[0-9a-f-]{36}\\.(jpg|png|webp)");

    private final Path root;
    /** null: the files are on disk */
    private final S3Client s3;
    private final String bucket;

    public FileStorage(@Value("${app.files.dir:.data/files}") String dir,
                       @Value("${app.files.s3.bucket:}") String bucket,
                       @Value("${app.files.s3.endpoint:}") String endpoint,
                       @Value("${app.files.s3.region:ap-southeast-1}") String region,
                       @Value("${app.files.s3.access-key:}") String accessKey,
                       @Value("${app.files.s3.secret-key:}") String secretKey) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.bucket = bucket;
        this.s3 = bucket.isBlank() ? null : S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                // S3-compatible stores like Supabase don't all take the SDK's newer default checksums
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
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
            if (s3 != null) {
                s3.putObject(b -> b.bucket(bucket).key(name).contentType(contentType(name)), RequestBody.fromBytes(file.getBytes()));
                return name;
            }
            Path target = resolve(name).orElseThrow();
            Files.createDirectories(target.getParent());
            try (InputStream all = file.getInputStream()) {
                Files.copy(all, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return name;
        } catch (IOException | SdkException e) {
            throw new IllegalStateException("Could not save the file", e);
        }
    }

    public void delete(String name) {
        if (name == null) {
            return;
        }
        if (s3 != null) {
            try {
                if (STORED_NAME.matcher(name).matches()) {
                    s3.deleteObject(b -> b.bucket(bucket).key(name));
                }
            } catch (SdkException e) {
                // an orphan file does no harm
            }
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

    /** A stored file, or empty when the name is null, not one of ours, or the file is gone. */
    public Optional<Resource> load(String name) {
        if (name == null) {
            return Optional.empty();
        }
        if (s3 != null) {
            if (!STORED_NAME.matcher(name).matches()) {
                return Optional.empty();
            }
            try {
                return Optional.of(new ByteArrayResource(s3.getObjectAsBytes(b -> b.bucket(bucket).key(name)).asByteArray()));
            } catch (S3Exception e) {
                if (e.statusCode() == 404) {
                    return Optional.empty();
                }
                throw e;
            }
        }
        return resolve(name).filter(Files::isRegularFile).map(FileSystemResource::new);
    }

    /** The file for a stored name, if it is inside the files folder. */
    private Optional<Path> resolve(String name) {
        Path path = root.resolve(name).normalize();
        return path.startsWith(root) ? Optional.of(path) : Optional.empty();
    }

    /** Folders whose files are never served at /public/files. */
    public static boolean isPrivate(String folder) {
        return PRIVATE_TRANSFERS.equals(folder) || PRIVATE_DOCUMENTS.equals(folder);
    }

    /** A private picture for someone allowed to see it: not cached, 404 when it is gone. */
    public ResponseEntity<Resource> privately(String name) {
        Resource file = load(name).orElse(null);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType(name)))
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
                .body(file);
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
