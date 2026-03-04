package com.orionkey.service;

import com.orionkey.common.PageResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AdminCardKeyService {

    List<?> getStockSummary(UUID productId, UUID specId);

    Map<String, Object> importCardKeys(Map<String, Object> request, UUID importedBy);

    PageResult<?> getImportBatches(UUID productId, int page, int pageSize);

    void lockCardKey(UUID id, String note);

    void unlockCardKey(UUID id);

    int batchLockCardKeys(UUID productId, UUID specId, String note);

    int batchUnlockCardKeys(UUID productId, UUID specId);

    int lockSelectedCardKeys(List<UUID> ids, String note);

    int unlockSelectedCardKeys(List<UUID> ids);

    int deleteSelectedCardKeys(List<UUID> ids);

    void invalidateCardKey(UUID id);

    int batchInvalidateCardKeys(UUID productId, UUID specId);

    List<?> getCardKeysByOrder(UUID orderId);

    PageResult<?> listCardKeys(UUID productId, UUID specId, int page, int pageSize);
}
