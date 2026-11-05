package com.modernstock.billing;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionAddonRepository extends JpaRepository<SubscriptionAddon, Long> {

    List<SubscriptionAddon> findByBusinessIdAndStatusInOrderById(Long businessId, Collection<SubscriptionAddon.Status> statuses);
}
