package com.modernstock.member;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, Long> {

    @Query("select r from Role r where r.businessId is null or r.businessId = :businessId order by r.builtIn desc, r.id")
    List<Role> findVisibleTo(Long businessId);

    @Query("select r from Role r where r.businessId is null and r.code = :code")
    Optional<Role> findBuiltIn(String code);

    /** Custom roles of a business. */
    long countByBusinessId(Long businessId);
}
