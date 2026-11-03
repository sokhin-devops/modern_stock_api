package com.modernstock.sale;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleItemRepository extends JpaRepository<SaleItem, Long> {

    List<SaleItem> findBySalesReceiptIdOrderById(Long salesReceiptId);

    List<SaleItem> findByDeliveryIdOrderById(Long deliveryId);
}
