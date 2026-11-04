package com.modernstock.business;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/** Tells BusinessContext.branchForWrite which branches are read-only (PlanLimits sets them when a plan ends). */
@Component
@RequiredArgsConstructor
class ReadOnlyBranches {

    private final JdbcTemplate jdbc;

    @PostConstruct
    void register() {
        BusinessContext.readOnlyCheck(id -> Boolean.TRUE.equals(
                jdbc.query("select read_only from branches where id = ?", rs -> rs.next() ? rs.getBoolean(1) : null, id)));
    }
}
