package com.modernstock.business;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BusinessRepository extends JpaRepository<Business, Long> {

    @Query("select count(b) from Business b where b.createdBy = :userId and b.status <> com.modernstock.business.Business.Status.ARCHIVED")
    long countCreatedBy(Long userId);
}
