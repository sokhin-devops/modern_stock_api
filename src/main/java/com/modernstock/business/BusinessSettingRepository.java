package com.modernstock.business;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessSettingRepository extends JpaRepository<BusinessSetting, Long> {

    Optional<BusinessSetting> findByKey(String key);
}
