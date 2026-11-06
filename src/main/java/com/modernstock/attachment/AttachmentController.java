package com.modernstock.attachment;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.modernstock.attachment.AttachmentService.AttachmentInfo;
import com.modernstock.common.FileStorage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The photo or scan kept with a document (PLAIN.md 11.1 rule 4). Bills and expenses keep theirs at /bills/{id}/photo
 * and /expenses/{id}/photo. Seeing it needs what opening the document needs; changing it, what making one needs.
 */
@Tag(name = "Attachments")
@RestController
@RequiredArgsConstructor
public class AttachmentController {

    private static final String TYPE = "The document's path: purchase-orders, goods-receipts, purchase-returns, supplier-credits, "
            + "supplier-payments, quotations, sales-orders, sales-receipts, invoices, deliveries, payments-received, sales-returns, "
            + "credit-notes, adjustments, transfers or stock-counts";

    private final AttachmentService attachments;
    private final FileStorage files;

    @Operation(summary = "Whether a document has a photo")
    @GetMapping("/attachments/{type}/{id}")
    @PreAuthorize("isAuthenticated()")
    public AttachmentInfo attachment(@Parameter(description = TYPE) @PathVariable String type, @PathVariable Long id) {
        return attachments.info(type, id);
    }

    @Operation(summary = "A document's photo", description = "Private, not cached; 404 without one.")
    @GetMapping("/attachments/{type}/{id}/photo")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> photo(@Parameter(description = TYPE) @PathVariable String type, @PathVariable Long id) {
        return files.privately(attachments.photo(type, id));
    }

    @Operation(summary = "Attach a photo to a document", description = "JPEG, PNG or WebP up to 2 MB; replaces the one before.")
    @PostMapping(value = "/attachments/{type}/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public AttachmentInfo attach(@Parameter(description = TYPE) @PathVariable String type, @PathVariable Long id,
                                 @RequestParam("file") MultipartFile file) {
        return attachments.set(type, id, file);
    }

    @Operation(summary = "Remove a document's photo")
    @DeleteMapping("/attachments/{type}/{id}/photo")
    @PreAuthorize("isAuthenticated()")
    public AttachmentInfo remove(@Parameter(description = TYPE) @PathVariable String type, @PathVariable Long id) {
        return attachments.set(type, id, null);
    }
}
