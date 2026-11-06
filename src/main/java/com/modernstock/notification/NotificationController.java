package com.modernstock.notification;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.notification.NotificationService.Notifications;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** The member's in-app notifications in the open business (PLAIN.md 10.2, 11.10). */
@Tag(name = "Notifications")
@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;

    @Operation(summary = "My notifications", description = "Newest first, with how many are not read yet.")
    @GetMapping("/notifications")
    @PreAuthorize("isAuthenticated()")
    public Notifications mine(@RequestParam(defaultValue = "20") int size) {
        return notifications.mine(size);
    }

    @Operation(summary = "Mark one notification read")
    @PostMapping("/notifications/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public Notifications read(@PathVariable Long id) {
        return notifications.markRead(id);
    }

    @Operation(summary = "Mark all my notifications read")
    @PostMapping("/notifications/read")
    @PreAuthorize("isAuthenticated()")
    public Notifications readAll() {
        return notifications.markRead(null);
    }
}
