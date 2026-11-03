package com.modernstock.sale;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CashMovementRepository extends JpaRepository<CashMovement, Long> {

    List<CashMovement> findByCashShiftIdOrderById(Long cashShiftId);
}
