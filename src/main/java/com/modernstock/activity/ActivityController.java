package com.modernstock.activity;

import java.time.LocalDate;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.activity.ActivityService.ActivityRow;
import com.modernstock.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Activity log")
@RestController
@RequestMapping("/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activity;

    @Operation(summary = "Activity log", description = "Who did what and when, newest first (FR-6.22). from/to are dates in Phnom Penh "
            + "time; q matches what an entry is about (a product name, a document number).")
    @GetMapping
    @PreAuthorize("hasAuthority('activity:view')")
    public PageResponse<ActivityRow> listActivity(@RequestParam(required = false) Long userId,
                                                  @RequestParam(required = false) String entityType,
                                                  @RequestParam(required = false) LocalDate from,
                                                  @RequestParam(required = false) LocalDate to,
                                                  @RequestParam(required = false) String q,
                                                  @ParameterObject @PageableDefault(size = 30) Pageable pageable) {
        return activity.list(userId, entityType, from, to, q, pageable);
    }
}
