package com.modernstock.common;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BusinessContext;

import lombok.RequiredArgsConstructor;

/**
 * Document numbers PREFIX-YYYYMM-0001, counted per business and type (PLAIN.md 11.1).
 * The upsert locks the sequence row until the document's transaction ends, so two cashiers never get the same number.
 */
@Service
@RequiredArgsConstructor
public class DocumentNumberService {

    public enum DocType {
        PO, GRN, BILL, PAY, PRT, SC, EXP, ADJ, TRF, CNT, QO, SO, DO, INV, TAX, DN, RCT, RCV, SR, CN
    }

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(DocType type) {
        Long businessId = BusinessContext.require().businessId();
        String period = YearMonth.now(Dates.PHNOM_PENH).format(PERIOD);
        Integer number = jdbc.queryForObject("""
                INSERT INTO document_sequences (business_id, doc_type, period, last_number) VALUES (?, ?, ?, 1)
                ON CONFLICT (business_id, doc_type, period)
                DO UPDATE SET last_number = document_sequences.last_number + 1
                RETURNING last_number
                """, Integer.class, businessId, type.name(), period);
        return "%s-%s-%04d".formatted(type.name(), period, number);
    }
}
