package com.modernstock.money;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AllocationRepository extends JpaRepository<Allocation, Long> {

    List<Allocation> findByToTypeAndToId(Allocation.ToType toType, Long toId);

    List<Allocation> findByFromTypeAndFromId(Allocation.FromType fromType, Long fromId);
}
