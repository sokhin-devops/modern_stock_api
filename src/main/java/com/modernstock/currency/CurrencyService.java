package com.modernstock.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.SettingsService;
import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;

import lombok.RequiredArgsConstructor;

/** Prices are in USD; riel is calculated with the day's rate and rounded to the business setting (PLAIN.md 11.6). */
@Service
@RequiredArgsConstructor
public class CurrencyService {

    /** stale = today's rate is missing, so the last one is used and the Owner and Manager get a warning. */
    public record Rate(BigDecimal usdToKhr, LocalDate rateDate, boolean stale) {
    }

    private final ExchangeRateRepository repository;
    private final SettingsService settings;

    @Transactional(readOnly = true)
    public Rate current() {
        LocalDate today = Dates.today();
        ExchangeRate rate = repository.findFirstByRateDateLessThanEqualOrderByRateDateDesc(today)
                .orElseThrow(() -> ApiException.rule("EXCHANGE_RATE_MISSING"));
        return new Rate(rate.getUsdToKhr(), rate.getRateDate(), !rate.getRateDate().equals(today));
    }

    @Transactional
    public ExchangeRate set(LocalDate date, BigDecimal usdToKhr) {
        ExchangeRate rate = repository.findByRateDate(date).orElseGet(ExchangeRate::new);
        rate.setRateDate(date);
        rate.setUsdToKhr(usdToKhr);
        return repository.save(rate);
    }

    /** $12.35 at 4,100 = 50,635 → ៛50,600 with 100-riel rounding. */
    public BigDecimal toKhr(BigDecimal usd, BigDecimal rate) {
        return roundKhr(usd.multiply(rate));
    }

    public BigDecimal roundKhr(BigDecimal khr) {
        BigDecimal step = BigDecimal.valueOf(settings.get().rielRounding());
        return khr.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
    }

    /** Change is never more than what is owed back, so it rounds down to the riel step. */
    public BigDecimal roundKhrDown(BigDecimal khr) {
        BigDecimal step = BigDecimal.valueOf(settings.get().rielRounding());
        return khr.divide(step, 0, RoundingMode.DOWN).multiply(step);
    }

    public static BigDecimal toUsd(BigDecimal khr, BigDecimal rate) {
        return khr.divide(rate, 2, RoundingMode.HALF_UP);
    }
}
