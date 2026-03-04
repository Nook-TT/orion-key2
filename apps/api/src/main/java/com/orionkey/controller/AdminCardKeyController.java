package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.constant.ErrorCode;
import com.orionkey.context.RequestContext;
import com.orionkey.exception.BusinessException;
import com.orionkey.service.AdminCardKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/card-keys")
@RequiredArgsConstructor
public class AdminCardKeyController {

    private final AdminCardKeyService adminCardKeyService;

    @GetMapping("/list")
    public ApiResponse<?> listCardKeys(
            @RequestParam("product_id") UUID productId,
            @RequestParam(value = "spec_id", required = false) UUID specId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(adminCardKeyService.listCardKeys(productId, specId, page, pageSize));
    }

    @GetMapping("/stock")
    public ApiResponse<?> getStockSummary(
            @RequestParam(value = "product_id", required = false) UUID productId,
            @RequestParam(value = "spec_id", required = false) UUID specId) {
        return ApiResponse.success(adminCardKeyService.getStockSummary(productId, specId));
    }

    @PostMapping("/import")
    public ApiResponse<?> importCardKeys(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(adminCardKeyService.importCardKeys(request, RequestContext.getUserId()));
    }

    @GetMapping("/import-batches")
    public ApiResponse<?> getImportBatches(
            @RequestParam(value = "product_id", required = false) UUID productId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(adminCardKeyService.getImportBatches(productId, page, pageSize));
    }

    @PostMapping("/{id}/lock")
    public ApiResponse<Void> lockCardKey(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> request) {
        String note = request != null ? (String) request.get("note") : null;
        adminCardKeyService.lockCardKey(id, note);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/unlock")
    public ApiResponse<Void> unlockCardKey(@PathVariable UUID id) {
        adminCardKeyService.unlockCardKey(id);
        return ApiResponse.success();
    }

    @PostMapping("/batch-lock")
    public ApiResponse<?> batchLockCardKeys(@RequestBody Map<String, Object> request) {
        UUID productId = UUID.fromString((String) request.get("product_id"));
        UUID specId = request.get("spec_id") != null ? UUID.fromString((String) request.get("spec_id")) : null;
        String note = request.get("note") != null ? (String) request.get("note") : null;
        int count = adminCardKeyService.batchLockCardKeys(productId, specId, note);
        return ApiResponse.success(Map.of("locked_count", count));
    }

    @PostMapping("/batch-unlock")
    public ApiResponse<?> batchUnlockCardKeys(@RequestBody Map<String, Object> request) {
        UUID productId = UUID.fromString((String) request.get("product_id"));
        UUID specId = request.get("spec_id") != null ? UUID.fromString((String) request.get("spec_id")) : null;
        int count = adminCardKeyService.batchUnlockCardKeys(productId, specId);
        return ApiResponse.success(Map.of("unlocked_count", count));
    }

    @PostMapping("/lock-selected")
    public ApiResponse<?> lockSelectedCardKeys(@RequestBody Map<String, Object> request) {
        String note = request.get("note") != null ? (String) request.get("note") : null;
        int count = adminCardKeyService.lockSelectedCardKeys(parseCardKeyIds(request), note);
        return ApiResponse.success(Map.of("locked_count", count));
    }

    @PostMapping("/unlock-selected")
    public ApiResponse<?> unlockSelectedCardKeys(@RequestBody Map<String, Object> request) {
        int count = adminCardKeyService.unlockSelectedCardKeys(parseCardKeyIds(request));
        return ApiResponse.success(Map.of("unlocked_count", count));
    }

    @PostMapping("/delete-selected")
    public ApiResponse<?> deleteSelectedCardKeys(@RequestBody Map<String, Object> request) {
        int count = adminCardKeyService.deleteSelectedCardKeys(parseCardKeyIds(request));
        return ApiResponse.success(Map.of("deleted_count", count));
    }

    @PostMapping("/{id}/invalidate")
    public ApiResponse<Void> invalidateCardKey(@PathVariable UUID id) {
        adminCardKeyService.invalidateCardKey(id);
        return ApiResponse.success();
    }

    @PostMapping("/batch-invalidate")
    public ApiResponse<?> batchInvalidateCardKeys(
            @RequestParam("product_id") UUID productId,
            @RequestParam(value = "spec_id", required = false) UUID specId) {
        int count = adminCardKeyService.batchInvalidateCardKeys(productId, specId);
        return ApiResponse.success(Map.of("invalidated_count", count));
    }

    @GetMapping("/by-order/{orderId}")
    public ApiResponse<?> getCardKeysByOrder(@PathVariable UUID orderId) {
        return ApiResponse.success(adminCardKeyService.getCardKeysByOrder(orderId));
    }

    private List<UUID> parseCardKeyIds(Map<String, Object> request) {
        Object rawIds = request.get("card_key_ids");
        if (!(rawIds instanceof List<?> rawList) || rawList.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请至少选择一条卡密");
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (Object raw : rawList) {
            ids.add(UUID.fromString(String.valueOf(raw)));
        }
        return new ArrayList<>(ids);
    }
}
