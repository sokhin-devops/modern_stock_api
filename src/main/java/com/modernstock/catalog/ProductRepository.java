package com.modernstock.catalog;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

import jakarta.persistence.LockModeType;

public interface ProductRepository extends TenantRepository<Product> {

    @Query("""
            select p from Product p
            where (:active is null or p.active = :active)
              and (:categoryId is null or p.categoryId = :categoryId)
              and (lower(p.nameEn) like :q or lower(coalesce(p.nameKm, '')) like :q
                   or lower(p.sku) like :q or coalesce(p.barcode, '') like :q)
            """)
    Page<Product> search(String q, Long categoryId, Boolean active, Pageable pageable);

    Optional<Product> findByBarcode(String barcode);

    @Query("select p from Product p join p.units u where u.barcode = :barcode")
    Optional<Product> findByUnitBarcode(String barcode);

    boolean existsBySkuIgnoreCase(String sku);

    Optional<Product> findBySkuIgnoreCase(String sku);

    /** Serializes average-cost updates of one product. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> lockById(Long id);

    @Override
    default String entityName() {
        return "Product";
    }
}
