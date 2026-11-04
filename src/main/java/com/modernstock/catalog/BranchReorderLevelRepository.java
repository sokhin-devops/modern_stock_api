package com.modernstock.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchReorderLevelRepository extends JpaRepository<BranchReorderLevel, Long> {

    Optional<BranchReorderLevel> findByBranchIdAndProductId(Long branchId, Long productId);

    List<BranchReorderLevel> findByProductId(Long productId);

    List<BranchReorderLevel> findByProductIdInAndBranchIdIn(Collection<Long> productIds, Collection<Long> branchIds);
}
