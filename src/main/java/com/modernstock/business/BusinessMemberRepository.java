package com.modernstock.business;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessMemberRepository extends JpaRepository<BusinessMember, Long> {

    List<BusinessMember> findByUserIdAndStatus(Long userId, BusinessMember.Status status);

    List<BusinessMember> findByBusinessIdAndStatusNotOrderByJoinedAt(Long businessId, BusinessMember.Status status);

    Optional<BusinessMember> findByBusinessIdAndUserId(Long businessId, Long userId);

    Optional<BusinessMember> findByIdAndBusinessId(Long id, Long businessId);

    long countByBusinessIdAndRoleIdAndStatusAndIdNot(Long businessId, Long roleId, BusinessMember.Status status, Long id);

    long countByBusinessIdAndStatus(Long businessId, BusinessMember.Status status);
}
