package com.modernstock.common;

import java.time.LocalDate;
import java.time.ZoneId;

/** Times are stored in UTC and the business day is Phnom Penh time (UTC+7). */
public final class Dates {

    public static final ZoneId PHNOM_PENH = ZoneId.of("Asia/Phnom_Penh");

    private Dates() {
    }

    public static LocalDate today() {
        return LocalDate.now(PHNOM_PENH);
    }
}
