package com.modernstock.member;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    Optional<Invitation> findByIdAndBusinessId(Long id, Long businessId);

    List<Invitation> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    @Query("""
            select count(i) from Invitation i where i.businessId = :businessId
            and i.status = com.modernstock.member.Invitation.Status.PENDING and i.expiresAt > CURRENT_TIMESTAMP""")
    long countOpen(Long businessId);

    long countByBusinessIdAndRoleIdAndStatus(Long businessId, Long roleId, Invitation.Status status);

    @Query("""
            select i from Invitation i where lower(i.email) = lower(:email)
            and i.status = com.modernstock.member.Invitation.Status.PENDING and i.expiresAt > CURRENT_TIMESTAMP""")
    List<Invitation> findOpenForEmail(String email);
}
