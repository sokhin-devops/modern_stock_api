package com.modernstock.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BusinessContext;
import com.modernstock.business.SettingsService;
import com.modernstock.catalog.Product;
import com.modernstock.catalog.ProductRepository;
import com.modernstock.common.ApiException;
import com.modernstock.common.CurrentUser;
import com.modernstock.common.Dates;
import com.modernstock.notification.TelegramAlerts;

import lombok.RequiredArgsConstructor;

/**
 * The only class that changes stock (PLAIN.md 8, key design rules). Every change writes a ledger row in
 * stock_movements and updates stock_levels and stock_batches in the caller's transaction.
 * Callers lock several products in product ID order, so two cashiers never deadlock.
 */
@Service
@RequiredArgsConstructor
public class StockService {

    /** The document that caused a movement, for example ("SALES_RECEIPT", 42). */
    public record Ref(String type, Long id) {
    }

    /** Stock taken from one batch: batch null only when negative stock is allowed and no batch was left. */
    public record Pick(Long batchId, String batchNo, LocalDate expiryDate, BigDecimal qty, BigDecimal unitCost) {
    }

    private static final int COST_SCALE = 4;

    private final StockLevelRepository levels;
    private final StockBatchRepository batches;
    private final StockMovementRepository movements;
    private final StockTransferRepository transfers;
    private final ProductRepository products;
    private final SettingsService settings;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    /**
     * Stock in. Purchases and opening stock update the weighted average cost; transfers, returns and
     * "found" adjustments come in at the cost they carry and leave the average alone.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockBatch receive(Product product, Long warehouseId, BigDecimal qty, BigDecimal unitCost, String batchNo,
                              LocalDate expiryDate, StockMovement.Type type, Ref ref) {
        requirePositive(qty);
        if (type == StockMovement.Type.GOODS_RECEIPT || type == StockMovement.Type.OPENING) {
            updateAverageCost(product, qty, unitCost);
        }
        StockLevel level = lockLevel(product.getId(), warehouseId);

        StockBatch batch = batchNo != null || expiryDate != null
                ? batches.findFirstSame(product.getId(), warehouseId, batchNo, expiryDate).orElse(null)
                : null;
        if (batch == null) {
            batch = new StockBatch();
            batch.setProductId(product.getId());
            batch.setWarehouseId(warehouseId);
            batch.setBatchNo(batchNo);
            batch.setExpiryDate(expiryDate);
            batch.setUnitCost(unitCost);
        } else {
            BigDecimal total = batch.getQtyOnHand().add(qty);
            if (total.signum() > 0) {
                batch.setUnitCost(batch.getQtyOnHand().max(BigDecimal.ZERO).multiply(batch.getUnitCost())
                        .add(qty.multiply(unitCost)).divide(total, COST_SCALE, RoundingMode.HALF_UP));
            }
        }
        batch.setQtyOnHand(batch.getQtyOnHand().add(qty));
        batches.save(batch);

        level.setQtyOnHand(level.getQtyOnHand().add(qty));
        write(product.getId(), warehouseId, batch.getId(), type, qty, unitCost, level.getQtyOnHand(), ref);
        return batch;
    }

    /** Stock out by FEFO. Expired batches are skipped unless allowExpired (adjustments, purchase returns). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Pick> issue(Product product, Long warehouseId, BigDecimal qty, StockMovement.Type type, Ref ref,
                            boolean allowExpired) {
        requirePositive(qty);
        StockLevel level = lockLevel(product.getId(), warehouseId);
        boolean allowNegative = settings.get().allowNegativeStock();
        if (level.getQtyOnHand().compareTo(qty) < 0 && !allowNegative) {
            throw insufficient(product, level.getQtyOnHand());
        }
        boolean hadStock = level.getQtyOnHand().signum() > 0;
        BigDecimal cost = product.getAvgCost();
        LocalDate today = Dates.today();
        BigDecimal remaining = qty;
        List<Pick> picks = new ArrayList<>();
        for (StockBatch b : batches.findForPick(product.getId(), warehouseId)) {
            if (remaining.signum() == 0) {
                break;
            }
            if (!allowExpired && b.getExpiryDate() != null && b.getExpiryDate().isBefore(today)) {
                continue;
            }
            BigDecimal take = remaining.min(b.getQtyOnHand());
            b.setQtyOnHand(b.getQtyOnHand().subtract(take));
            level.setQtyOnHand(level.getQtyOnHand().subtract(take));
            remaining = remaining.subtract(take);
            write(product.getId(), warehouseId, b.getId(), type, take.negate(), cost, level.getQtyOnHand(), ref);
            picks.add(new Pick(b.getId(), b.getBatchNo(), b.getExpiryDate(), take, cost));
        }
        if (remaining.signum() > 0) {
            if (!allowNegative) {
                // only expired batches are left: they can't be sold (PLAIN.md 11.5)
                throw insufficient(product, qty.subtract(remaining));
            }
            level.setQtyOnHand(level.getQtyOnHand().subtract(remaining));
            write(product.getId(), warehouseId, null, type, remaining.negate(), cost, level.getQtyOnHand(), ref);
            picks.add(new Pick(null, null, null, remaining, cost));
        }
        // Selling the last units is worth an immediate Telegram alert (FR-5.5)
        if (hadStock && level.getQtyOnHand().signum() <= 0
                && (type == StockMovement.Type.SALES_RECEIPT || type == StockMovement.Type.DELIVERY)) {
            events.publishEvent(new TelegramAlerts.StockOut(BusinessContext.businessId(), product.getId(), warehouseId));
        }
        return picks;
    }

    /** Stock out from one chosen batch, for example expired goods sent back to a central store. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Pick issueFromBatch(Product product, Long batchId, BigDecimal qty, StockMovement.Type type, Ref ref) {
        requirePositive(qty);
        StockBatch b = batches.get(batchId);
        if (!b.getProductId().equals(product.getId())) {
            throw ApiException.notFound("Batch");
        }
        StockLevel level = lockLevel(product.getId(), b.getWarehouseId());
        if (b.getQtyOnHand().compareTo(qty) < 0) {
            throw insufficient(product, b.getQtyOnHand());
        }
        b.setQtyOnHand(b.getQtyOnHand().subtract(qty));
        level.setQtyOnHand(level.getQtyOnHand().subtract(qty));
        write(product.getId(), b.getWarehouseId(), b.getId(), type, qty.negate(), product.getAvgCost(),
                level.getQtyOnHand(), ref);
        return new Pick(b.getId(), b.getBatchNo(), b.getExpiryDate(), qty, product.getAvgCost());
    }

    /**
     * Stock back out of the batch a document brought it in with (a purchase return from its goods receipt); what that
     * batch no longer holds comes from the other batches, earliest expiry first. Expired goods may go.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Pick> issueReceived(Product product, Ref received, Long warehouseId, String batchNo, LocalDate expiryDate,
                                    BigDecimal qty, StockMovement.Type type, Ref ref) {
        requirePositive(qty);
        StockBatch own = movements.findByReferenceTypeAndReferenceIdOrderByIdAsc(received.type(), received.id()).stream()
                .filter(m -> m.getProductId().equals(product.getId()) && m.getBatchId() != null)
                .map(m -> batches.get(m.getBatchId()))
                .filter(b -> b.getWarehouseId().equals(warehouseId) && Objects.equals(b.getBatchNo(), batchNo)
                        && Objects.equals(b.getExpiryDate(), expiryDate))
                .findFirst().orElse(null);
        List<Pick> picks = new ArrayList<>();
        BigDecimal remaining = qty;
        if (own != null && own.getQtyOnHand().signum() > 0) {
            BigDecimal take = own.getQtyOnHand().min(remaining);
            picks.add(issueFromBatch(product, own.getId(), take, type, ref));
            remaining = remaining.subtract(take);
        }
        if (remaining.signum() > 0) {
            picks.addAll(issue(product, warehouseId, remaining, type, ref, true));
        }
        return picks;
    }

    /**
     * Before voiding a document that brought stock in: each batch it filled must still hold what it added (it may have
     * been sold or moved since). Refused with VOID_STOCK_GONE, naming the product, otherwise.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireStillThere(Ref ref) {
        java.util.Map<Long, BigDecimal> added = new java.util.LinkedHashMap<>();
        java.util.Map<Long, Long> productOf = new java.util.HashMap<>();
        for (StockMovement m : movements.findByReferenceTypeAndReferenceIdOrderByIdAsc(ref.type(), ref.id())) {
            if (m.getBatchId() != null) {
                added.merge(m.getBatchId(), m.getQty(), BigDecimal::add);
                productOf.put(m.getBatchId(), m.getProductId());
            }
        }
        for (java.util.Map.Entry<Long, BigDecimal> e : added.entrySet()) {
            if (e.getValue().signum() <= 0) {
                continue;
            }
            StockBatch b = batches.get(e.getKey());
            if (b.getQtyOnHand().compareTo(e.getValue()) < 0) {
                throw ApiException.rule("VOID_STOCK_GONE", products.get(productOf.get(e.getKey())).getNameEn(),
                        b.getQtyOnHand().stripTrailingZeros().toPlainString(), e.getValue().stripTrailingZeros().toPlainString());
            }
        }
    }

    /**
     * Voids a document: writes the opposite of each of its movements, back into the same batches.
     * The document's own status check makes sure this runs once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverse(Ref ref) {
        List<StockMovement> original = new ArrayList<>(
                movements.findByReferenceTypeAndReferenceIdOrderByIdAsc(ref.type(), ref.id()));
        original.sort(Comparator.comparing(StockMovement::getProductId).thenComparing(StockMovement::getId));
        boolean allowNegative = settings.get().allowNegativeStock();
        for (StockMovement m : original) {
            BigDecimal back = m.getQty().negate();
            StockLevel level = lockLevel(m.getProductId(), m.getWarehouseId());
            if (back.signum() < 0 && level.getQtyOnHand().add(back).signum() < 0 && !allowNegative) {
                throw insufficient(products.get(m.getProductId()), level.getQtyOnHand());
            }
            if (m.getBatchId() != null) {
                StockBatch b = batches.get(m.getBatchId());
                b.setQtyOnHand(b.getQtyOnHand().add(back));
            }
            level.setQtyOnHand(level.getQtyOnHand().add(back));
            write(m.getProductId(), m.getWarehouseId(), m.getBatchId(), m.getType(), back, m.getUnitCost(),
                    level.getQtyOnHand(), ref);
        }
    }

    /** new_avg = (old_qty × old_avg + qty × cost) / (old_qty + qty), with stock in transit counted (PLAIN.md 11.3). */
    private void updateAverageCost(Product product, BigDecimal qty, BigDecimal unitCost) {
        Product locked = products.lockById(product.getId()).orElseThrow();
        BigDecimal oldQty = levels.totalQty(locked.getId()).add(transfers.inTransitQty(locked.getId()));
        if (oldQty.signum() <= 0) {
            locked.setAvgCost(unitCost.setScale(COST_SCALE, RoundingMode.HALF_UP));
        } else {
            locked.setAvgCost(oldQty.multiply(locked.getAvgCost()).add(qty.multiply(unitCost))
                    .divide(oldQty.add(qty), COST_SCALE, RoundingMode.HALF_UP));
        }
    }

    /** Creates the stock row if it is the first time this product is in this warehouse, then locks it. */
    private StockLevel lockLevel(Long productId, Long warehouseId) {
        jdbc.update("""
                INSERT INTO stock_levels (business_id, warehouse_id, product_id, qty_on_hand) VALUES (?, ?, ?, 0)
                ON CONFLICT (warehouse_id, product_id) DO NOTHING
                """, BusinessContext.require().businessId(), warehouseId, productId);
        return levels.lockByProductAndWarehouse(productId, warehouseId).orElseThrow();
    }

    private void write(Long productId, Long warehouseId, Long batchId, StockMovement.Type type, BigDecimal qty,
                       BigDecimal unitCost, BigDecimal balanceAfter, Ref ref) {
        StockMovement m = new StockMovement();
        m.setProductId(productId);
        m.setWarehouseId(warehouseId);
        m.setBatchId(batchId);
        m.setType(type);
        m.setQty(qty);
        m.setUnitCost(Objects.requireNonNullElse(unitCost, BigDecimal.ZERO));
        m.setBalanceAfter(balanceAfter);
        m.setReferenceType(ref.type());
        m.setReferenceId(ref.id());
        m.setUserId(CurrentUser.idOrNull());
        movements.save(m);
    }

    private static void requirePositive(BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw ApiException.badRequest("QTY_MUST_BE_POSITIVE");
        }
    }

    private static ApiException insufficient(Product product, BigDecimal available) {
        return ApiException.rule("INSUFFICIENT_STOCK", product.getNameEn(), available.stripTrailingZeros().toPlainString());
    }
}
