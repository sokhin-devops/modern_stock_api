package com.modernstock.billing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByBusinessId(Long businessId);

    List<Subscription> findByBusinessIdIn(Collection<Long> businessIds);
}
