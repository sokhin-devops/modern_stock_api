package com.modernstock.currency;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.common.Dates;
import com.modernstock.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@Tag(name = "Exchange rates")
@RestController
@RequestMapping("/exchange-rates")
@RequiredArgsConstructor
public class ExchangeRateController {

    public record RateRequest(LocalDate rateDate,
                              @NotNull @DecimalMin("1000") @DecimalMax("10000") BigDecimal usdToKhr) {
    }

    public record RateResponse(Long id, LocalDate rateDate, BigDecimal usdToKhr) {
        static RateResponse from(ExchangeRate r) {
            return new RateResponse(r.getId(), r.getRateDate(), r.getUsdToKhr());
        }
    }

    private final CurrencyService currency;
    private final ExchangeRateRepository repository;

    @Operation(summary = "Rate used today", description = "If today's rate is missing, the last one is returned with stale = true.")
    @GetMapping("/current")
    public CurrencyService.Rate current() {
        return currency.current();
    }

    @Operation(summary = "Rate history, newest first")
    @GetMapping
    public PageResponse<RateResponse> list(@ParameterObject Pageable pageable) {
        return PageResponse.of(repository.findAllByOrderByRateDateDesc(pageable), RateResponse::from);
    }

    @Operation(summary = "Set the rate for a day", description = "rateDate defaults to today. Setting a day twice replaces it.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('exchange_rate:manage')")
    public RateResponse set(@Valid @RequestBody RateRequest req) {
        LocalDate date = req.rateDate() != null ? req.rateDate() : Dates.today();
        return RateResponse.from(currency.set(date, req.usdToKhr()));
    }
}
