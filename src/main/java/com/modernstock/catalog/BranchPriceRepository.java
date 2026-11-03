package com.modernstock.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchPriceRepository extends JpaRepository<BranchPrice, Long> {

    Optional<BranchPrice> findByBranchIdAndProductIdAndUnitId(Long branchId, Long productId, Long unitId);

    List<BranchPrice> findByProductId(Long productId);
}
