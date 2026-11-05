package com.modernstock.publicsite;

import java.time.Duration;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.common.FileStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Product photos and logos (FileStorage). Public, so an <img> can show them; the names are random. */
@Tag(name = "Public")
@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileStorage files;

    @Operation(summary = "A stored picture", description = "Cached for a year: a new picture gets a new name.")
    @GetMapping("/public/files/{folder}/{name:.+}")
    public ResponseEntity<Resource> file(@PathVariable String folder, @PathVariable String name) {
        String stored = folder + "/" + name;
        Resource file = FileStorage.isPrivate(folder) ? null : files.load(stored).orElse(null);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(FileStorage.contentType(stored)))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(file);
    }
}
