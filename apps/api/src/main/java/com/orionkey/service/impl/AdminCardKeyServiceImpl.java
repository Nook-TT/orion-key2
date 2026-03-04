package com.orionkey.service.impl;

import com.orionkey.common.PageResult;
import com.orionkey.constant.CardKeyStatus;
import com.orionkey.constant.ErrorCode;
import com.orionkey.entity.CardImportBatch;
import com.orionkey.entity.CardKey;
import com.orionkey.entity.OrderItem;
import com.orionkey.entity.Product;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.*;
import com.orionkey.service.AdminCardKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCardKeyServiceImpl implements AdminCardKeyService {

    private final CardKeyRepository cardKeyRepository;
    private final CardImportBatchRepository cardImportBatchRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final OrderItemRepository orderItemRepository;

    @Override
    public List<?> getStockSummary(UUID productId, UUID specId) {
        // Get all products or specific product
        List<Product> products;
        if (productId != null) {
            products = productRepository.findById(productId).map(List::of).orElse(List.of());
        } else {
            products = productRepository.findAll();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Product p : products) {
            if (p.getIsDeleted() != 0) continue;
            var specs = productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(p.getId(), 0);
            if (specs.isEmpty()) {
                result.add(buildStockEntry(p.getId(), p.getTitle(), null, null));
            } else {
                for (var spec : specs) {
                    if (specId != null && !spec.getId().equals(specId)) continue;
                    result.add(buildStockEntry(p.getId(), p.getTitle(), spec.getId(), spec.getName()));
                }
            }
        }
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> importCardKeys(Map<String, Object> req, UUID importedBy) {
        UUID productId = UUID.fromString((String) req.get("product_id"));
        UUID specId = req.get("spec_id") != null ? UUID.fromString((String) req.get("spec_id")) : null;
        String content = (String) req.get("content");
        String duplicateAction = normalizeDuplicateAction((String) req.get("duplicate_action"));

        productRepository.findById(productId)
                .filter(p -> p.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));

        List<String> normalizedLines = new ArrayList<>();
        LinkedHashSet<String> uniqueContents = new LinkedHashSet<>();
        Set<String> requestSeen = new HashSet<>();
        int total = 0;
        int requestDuplicateCount = 0;

        for (String line : content == null ? new String[0] : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            normalizedLines.add(trimmed);
            uniqueContents.add(trimmed);
            total++;
            if (!requestSeen.add(trimmed)) {
                requestDuplicateCount++;
            }
        }

        if (total == 0) {
            throw new BusinessException(ErrorCode.CARD_KEY_FORMAT_ERROR, "卡密导入格式错误");
        }

        Map<String, List<CardKey>> existingByContent = new LinkedHashMap<>();
        for (CardKey key : cardKeyRepository.findByProductIdAndContentIn(productId, new ArrayList<>(uniqueContents))) {
            existingByContent.computeIfAbsent(key.getContent(), ignored -> new ArrayList<>()).add(key);
        }

        List<Map<String, Object>> duplicateItems = buildDuplicateItems(existingByContent);
        if ("ask".equals(duplicateAction) && !duplicateItems.isEmpty()) {
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("requires_duplicate_action", true);
            preview.put("total_count", total);
            preview.put("success_count", 0);
            preview.put("fail_count", 0);
            preview.put("fail_detail", null);
            preview.put("duplicate_count", duplicateItems.size());
            preview.put("duplicate_items", duplicateItems);
            preview.put("input_duplicate_count", requestDuplicateCount);
            return preview;
        }

        int success = 0;
        int fail = 0;
        int overwriteCount = 0;
        int skippedDuplicateCount = 0;
        StringBuilder failDetail = new StringBuilder();
        List<CardKey> importedCardKeys = new ArrayList<>();
        Set<String> importedContents = new HashSet<>();

        for (String trimmed : normalizedLines) {
            if (!importedContents.add(trimmed)) {
                fail++;
                failDetail.append("本次输入重复: ").append(trimmed).append("\n");
                continue;
            }

            List<CardKey> duplicates = existingByContent.getOrDefault(trimmed, List.of());
            if (!duplicates.isEmpty()) {
                if ("skip".equals(duplicateAction)) {
                    fail++;
                    skippedDuplicateCount++;
                    failDetail.append("跳过重复: ").append(trimmed).append("\n");
                    continue;
                }

                if ("overwrite".equals(duplicateAction)) {
                    CardKey reusable = findReusableDuplicate(duplicates);
                    if (reusable == null) {
                        fail++;
                        skippedDuplicateCount++;
                        failDetail.append(buildOverwriteBlockedMessage(trimmed, duplicates)).append("\n");
                        continue;
                    }

                    reusable.setSpecId(specId);
                    reusable.setStatus(CardKeyStatus.AVAILABLE);
                    reusable.setLockNote(null);
                    reusable.setOrderId(null);
                    reusable.setOrderItemId(null);
                    reusable.setSoldAt(null);
                    cardKeyRepository.save(reusable);
                    importedCardKeys.add(reusable);
                    success++;
                    overwriteCount++;
                    continue;
                }
            }

            CardKey key = new CardKey();
            key.setProductId(productId);
            key.setSpecId(specId);
            key.setContent(trimmed);
            key.setStatus(CardKeyStatus.AVAILABLE);
            cardKeyRepository.save(key);
            importedCardKeys.add(key);
            success++;
        }

        CardImportBatch batch = new CardImportBatch();
        batch.setProductId(productId);
        batch.setSpecId(specId);
        batch.setImportedBy(importedBy);
        batch.setTotalCount(total);
        batch.setSuccessCount(success);
        batch.setFailCount(fail);
        batch.setFailDetail(fail > 0 ? failDetail.toString() : null);
        cardImportBatchRepository.save(batch);

        // Update import batch id on successfully imported card keys
        for (CardKey key : importedCardKeys) {
            key.setImportBatchId(batch.getId());
            cardKeyRepository.save(key);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", batch.getId());
        result.put("product_id", batch.getProductId());
        result.put("spec_id", batch.getSpecId());
        result.put("imported_by", batch.getImportedBy());
        result.put("total_count", batch.getTotalCount());
        result.put("success_count", batch.getSuccessCount());
        result.put("fail_count", batch.getFailCount());
        result.put("fail_detail", batch.getFailDetail());
        result.put("overwrite_count", overwriteCount);
        result.put("skipped_duplicate_count", skippedDuplicateCount);
        result.put("input_duplicate_count", requestDuplicateCount);
        result.put("created_at", batch.getCreatedAt());
        return result;
    }

    @Override
    public PageResult<?> getImportBatches(UUID productId, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        Page<CardImportBatch> batchPage;
        if (productId != null) {
            batchPage = cardImportBatchRepository.findByProductIdOrderByCreatedAtDesc(productId, pageable);
        } else {
            batchPage = cardImportBatchRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return PageResult.of(batchPage, batchPage.getContent());
    }

    @Override
    @Transactional
    public void lockCardKey(UUID id, String note) {
        CardKey key = cardKeyRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "卡密不存在"));
        if (key.getStatus() != CardKeyStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅可锁定可用状态的卡密");
        }
        key.setLockNote(normalizeLockNote(note));
        key.setStatus(CardKeyStatus.LOCKED);
        cardKeyRepository.save(key);
    }

    @Override
    @Transactional
    public void unlockCardKey(UUID id) {
        CardKey key = cardKeyRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "卡密不存在"));
        if (key.getStatus() != CardKeyStatus.LOCKED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅可恢复已锁定的卡密");
        }
        key.setLockNote(null);
        key.setStatus(CardKeyStatus.AVAILABLE);
        cardKeyRepository.save(key);
    }

    @Override
    @Transactional
    public int batchLockCardKeys(UUID productId, UUID specId, String note) {
        return cardKeyRepository.updateStatusAndLockNoteByProductIdAndSpecId(
                productId, specId, CardKeyStatus.AVAILABLE, CardKeyStatus.LOCKED, normalizeLockNote(note));
    }

    @Override
    @Transactional
    public int batchUnlockCardKeys(UUID productId, UUID specId) {
        return cardKeyRepository.updateStatusAndLockNoteByProductIdAndSpecId(
                productId, specId, CardKeyStatus.LOCKED, CardKeyStatus.AVAILABLE, null);
    }

    @Override
    @Transactional
    public int lockSelectedCardKeys(List<UUID> ids, String note) {
        List<CardKey> keys = requireExistingCardKeys(ids);
        for (CardKey key : keys) {
            if (key.getStatus() != CardKeyStatus.AVAILABLE) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "选中的卡密里存在不可锁定项");
            }
        }
        String normalizedNote = normalizeLockNote(note);
        for (CardKey key : keys) {
            key.setLockNote(normalizedNote);
            key.setStatus(CardKeyStatus.LOCKED);
        }
        cardKeyRepository.saveAll(keys);
        return keys.size();
    }

    @Override
    @Transactional
    public int unlockSelectedCardKeys(List<UUID> ids) {
        List<CardKey> keys = requireExistingCardKeys(ids);
        for (CardKey key : keys) {
            if (key.getStatus() != CardKeyStatus.LOCKED) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "选中的卡密里存在不可恢复项");
            }
        }
        for (CardKey key : keys) {
            key.setLockNote(null);
            key.setStatus(CardKeyStatus.AVAILABLE);
        }
        cardKeyRepository.saveAll(keys);
        return keys.size();
    }

    @Override
    @Transactional
    public int deleteSelectedCardKeys(List<UUID> ids) {
        List<CardKey> keys = requireExistingCardKeys(ids);
        for (CardKey key : keys) {
            if (key.getStatus() == CardKeyStatus.SOLD) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "已售卡密不可删除");
            }
        }
        cardKeyRepository.deleteAll(keys);
        return keys.size();
    }

    @Override
    @Transactional
    public void invalidateCardKey(UUID id) {
        CardKey key = cardKeyRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "卡密不存在"));
        if (key.getStatus() == CardKeyStatus.SOLD) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已售出的卡密不可作废");
        }
        key.setStatus(CardKeyStatus.INVALID);
        cardKeyRepository.save(key);
    }

    @Override
    @Transactional
    public int batchInvalidateCardKeys(UUID productId, UUID specId) {
        return cardKeyRepository.updateStatusByProductIdAndSpecId(
                productId, specId, CardKeyStatus.AVAILABLE, CardKeyStatus.INVALID);
    }

    @Override
    public List<?> getCardKeysByOrder(UUID orderId) {
        List<CardKey> keys = cardKeyRepository.findByOrderId(orderId);
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, OrderItem> itemMap = new HashMap<>();
        for (OrderItem item : items) {
            itemMap.put(item.getId(), item);
        }

        return keys.stream().map(k -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("card_key_id", k.getId());
            map.put("content", k.getContent());
            OrderItem item = k.getOrderItemId() != null ? itemMap.get(k.getOrderItemId()) : null;
            map.put("product_title", item != null ? item.getProductTitle() : null);
            map.put("spec_name", item != null ? item.getSpecName() : null);
            map.put("status", k.getStatus().name());
            return map;
        }).toList();
    }

    @Override
    public PageResult<?> listCardKeys(UUID productId, UUID specId, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        var keyPage = cardKeyRepository.findByProductIdAndOptionalSpecId(productId, specId, pageable);
        var list = keyPage.getContent().stream().map(k -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", k.getId());
            map.put("content", k.getContent());
            map.put("lock_note", k.getLockNote());
            map.put("status", k.getStatus().name());
            map.put("order_id", k.getOrderId());
            map.put("created_at", k.getCreatedAt());
            map.put("sold_at", k.getSoldAt());
            return map;
        }).toList();
        return PageResult.of(keyPage, list);
    }

    private Map<String, Object> buildStockEntry(UUID productId, String productTitle, UUID specId, String specName) {
        List<Object[]> counts = cardKeyRepository.countByProductIdAndSpecIdGroupByStatus(productId, specId);
        long total = 0, available = 0, sold = 0, locked = 0, invalid = 0;
        for (Object[] row : counts) {
            CardKeyStatus status = (CardKeyStatus) row[0];
            long cnt = (Long) row[1];
            total += cnt;
            switch (status) {
                case AVAILABLE -> available = cnt;
                case SOLD -> sold = cnt;
                case LOCKED -> locked = cnt;
                case INVALID -> invalid = cnt;
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("product_id", productId);
        map.put("product_title", productTitle);
        map.put("spec_id", specId);
        map.put("spec_name", specName);
        map.put("total", total);
        map.put("available", available);
        map.put("sold", sold);
        map.put("locked", locked);
        map.put("invalid", invalid);
        return map;
    }

    private String normalizeLockNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 200) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "锁定备注最多 200 个字符");
        }
        return trimmed;
    }

    private List<CardKey> requireExistingCardKeys(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请至少选择一条卡密");
        }
        List<CardKey> keys = cardKeyRepository.findAllById(ids);
        if (keys.size() != ids.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分卡密不存在或已被删除");
        }
        return keys;
    }

    private List<Map<String, Object>> buildDuplicateItems(Map<String, List<CardKey>> existingByContent) {
        List<Map<String, Object>> duplicateItems = new ArrayList<>();
        for (Map.Entry<String, List<CardKey>> entry : existingByContent.entrySet()) {
            List<CardKey> duplicates = entry.getValue();
            CardKey reusable = findReusableDuplicate(duplicates);
            CardKey previewKey = duplicates.get(0);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("content", entry.getKey());
            item.put("status", previewKey.getStatus().name());
            item.put("existing_count", duplicates.size());
            item.put("can_overwrite", reusable != null);
            if (reusable == null) {
                item.put("reason", overwriteBlockedReason(duplicates));
            }
            duplicateItems.add(item);
        }
        return duplicateItems;
    }

    private CardKey findReusableDuplicate(List<CardKey> duplicates) {
        if (duplicates == null || duplicates.isEmpty()) {
            return null;
        }
        if (duplicates.size() > 1) {
            return null;
        }
        CardKey candidate = duplicates.get(0);
        if (candidate.getStatus() == CardKeyStatus.SOLD) {
            return null;
        }
        return candidate;
    }

    private String buildOverwriteBlockedMessage(String content, List<CardKey> duplicates) {
        return overwriteBlockedReason(duplicates) + ": " + content;
    }

    private String overwriteBlockedReason(List<CardKey> duplicates) {
        if (duplicates == null || duplicates.isEmpty()) {
            return "无法自动覆盖";
        }
        if (duplicates.size() > 1) {
            return "重复记录超过 1 条，无法自动覆盖";
        }
        if (duplicates.get(0).getStatus() == CardKeyStatus.SOLD) {
            return "已售卡密不可覆盖";
        }
        return "无法自动覆盖";
    }

    private String normalizeDuplicateAction(String action) {
        if (action == null || action.isBlank()) {
            return "ask";
        }
        String normalized = action.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("ask", "skip", "overwrite").contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的重复处理方式");
        }
        return normalized;
    }
}
