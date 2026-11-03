package com.modernstock.currency;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    Optional<ExchangeRate> findByRateDate(LocalDate rateDate);

    Optional<ExchangeRate> findFirstByRateDateLessThanEqualOrderByRateDateDesc(LocalDate date);

    Page<ExchangeRate> findAllByOrderByRateDateDesc(Pageable pageable);
}
