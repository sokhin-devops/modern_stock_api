package com.modernstock.billing;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AddonRepository extends JpaRepository<Addon, Long> {

    List<Addon> findAllByOrderBySortOrder();

    Optional<Addon> findByCode(String code);
}
