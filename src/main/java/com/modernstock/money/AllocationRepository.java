package com.modernstock.money;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AllocationRepository extends JpaRepository<Allocation, Long> {

    List<Allocation> findByToTypeAndToId(Allocation.ToType toType, Long toId);

    List<Allocation> findByFromTypeAndFromId(Allocation.FromType fromType, Long fromId);

    @Query("select coalesce(sum(a.amount), 0) from Allocation a where a.fromType = :fromType and a.fromId = :fromId")
    BigDecimal allocated(Allocation.FromType fromType, Long fromId);
}
