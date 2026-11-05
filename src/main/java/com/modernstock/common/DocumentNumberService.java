package com.modernstock.common;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BusinessContext;

import lombok.RequiredArgsConstructor;

/**
 * Document numbers PREFIX-YYYYMM-0001 (PLAIN.md 11.1). The prefix is the type's code unless the Owner chose another
 * (FR-7.11, Settings › Tax and receipt). Numbers are counted per business, prefix and month, so a number is never
 * given twice, even after a prefix changes. The upsert locks the sequence row until the document's transaction ends,
 * so two cashiers never get the same number.
 */
@Service
@RequiredArgsConstructor
public class DocumentNumberService {

    public enum DocType {
        PO, GRN, BILL, PAY, PRT, SC, EXP, ADJ, TRF, CNT, QO, SO, DO, INV, TAX, DN, RCT, RCV, SR, CN
    }

    /** Settings key of a type's own prefix: number_prefix_INV. */
    public static final String PREFIX_KEY = "number_prefix_";
    /** 1 to 6 capital letters or digits, starting with a letter. */
    public static final Pattern PREFIX = Pattern.compile("[A-Z][A-Z0-9]{0,5}");

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(DocType type) {
        Long businessId = BusinessContext.require().businessId();
        String period = YearMonth.now(Dates.PHNOM_PENH).format(PERIOD);
        String prefix = prefix(businessId, type);
        // doc_type holds the prefix: the default prefixes are the type codes, so existing counters carry on
        Integer number = jdbc.queryForObject("""
                INSERT INTO document_sequences (business_id, doc_type, period, last_number) VALUES (?, ?, ?, 1)
                ON CONFLICT (business_id, doc_type, period)
                DO UPDATE SET last_number = document_sequences.last_number + 1
                RETURNING last_number
                """, Integer.class, businessId, prefix, period);
        return "%s-%s-%04d".formatted(prefix, period, number);
    }

    private String prefix(Long businessId, DocType type) {
        List<String> value = jdbc.queryForList("SELECT value FROM business_settings WHERE business_id = ? AND key = ?",
                String.class, businessId, PREFIX_KEY + type.name());
        return value.isEmpty() || !PREFIX.matcher(value.get(0)).matches() ? type.name() : value.get(0);
    }
}
