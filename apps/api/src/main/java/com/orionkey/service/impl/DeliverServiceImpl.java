package com.orionkey.service.impl;

import com.orionkey.constant.CardKeyStatus;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.OrderStatus;
import com.orionkey.context.RequestContext;
import com.orionkey.entity.*;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.*;
import com.orionkey.service.DeliverService;
import com.orionkey.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliverServiceImpl implements DeliverService {

    private record Allocation(OrderItem item, List<CardKey> keys) {}

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final PointsLogRepository pointsLogRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final EmailService emailService;

    @Override
    @SuppressWarnings("unchecked")
    public List<?> queryOrders(Map<String, Object> request, UUID userId, String sessionToken) {
        Set<UUID> orderIds = new LinkedHashSet<>();

        List<String> orderIdStrs = (List<String>) request.get("order_ids");
        if (orderIdStrs != null) {
            orderIdStrs.forEach(id -> orderIds.add(UUID.fromString(id)));
        }

        List<String> emails = (List<String>) request.get("emails");
        if (emails != null && !emails.isEmpty()) {
            List<Order> emailOrders = orderRepository.findByEmailInOrderByCreatedAtDesc(emails);
            emailOrders.forEach(o -> orderIds.add(o.getId()));
        }

        if (orderIds.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在");
        }

        List<Order> orders = orderRepository.findByIdIn(new ArrayList<>(orderIds));
        orders = orders.stream()
                .filter(o -> canAccessOrder(o, userId, sessionToken))
                .toList();
        if (orders.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在");
        }
        // Sort by createdAt desc
        orders = orders.stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .toList();

        return orders.stream().map(o -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", o.getId());
            map.put("total_amount", o.getTotalAmount());
            map.put("actual_amount", o.getActualAmount());
            map.put("status", o.getStatus().name());
            map.put("order_type", o.getOrderType().name());
            map.put("payment_method", o.getPaymentMethod());
            map.put("created_at", o.getCreatedAt());
            return map;
        }).toList();
    }

    @Override
    public Map<String, Object> getDeliveryResult(UUID orderId, UUID userId, String sessionToken) {
        Order order = getAccessibleOrderOrThrow(orderId, userId, sessionToken);
        return buildReadOnlyDeliveryResult(order);
    }

    @Override
    @SuppressWarnings("unchecked")
    @Transactional
    public List<?> deliverOrders(Map<String, Object> request, UUID userId, String sessionToken) {
        List<String> orderIdStrs = (List<String>) request.get("order_ids");
        if (orderIdStrs == null || orderIdStrs.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (String idStr : orderIdStrs) {
            UUID orderId = UUID.fromString(idStr);
            results.add(deliverSingleOrder(orderId, userId, sessionToken));
        }
        return results;
    }

    @Override
    @Transactional
    public Map<String, Object> deliverOrderSystem(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        return deliverOrder(order);
    }

    private Map<String, Object> deliverSingleOrder(UUID orderId, UUID userId, String sessionToken) {
        Order order = getAccessibleOrderOrThrow(orderId, userId, sessionToken);
        return deliverOrder(order);
    }

    private Map<String, Object> deliverOrder(Order order) {
        switch (order.getStatus()) {
            case PAID -> {
                return deliverPaidOrder(order);
            }
            case DELIVERED, PENDING, EXPIRED -> {
                return buildReadOnlyDeliveryResult(order);
            }
        }
        return buildReadOnlyDeliveryResult(order);
    }

    private Map<String, Object> deliverPaidOrder(Order order) {
        UUID orderId = order.getId();
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        try {
            List<Allocation> allocations = new ArrayList<>();
            for (OrderItem item : items) {
                List<CardKey> keys = cardKeyRepository.findAndLockAvailable(
                        item.getProductId(), item.getSpecId(), item.getQuantity());
                if (keys.size() < item.getQuantity()) {
                    throw new BusinessException(ErrorCode.ORDER_OUT_OF_STOCK, "缺货补货中，请联系客服");
                }
                allocations.add(new Allocation(item, keys));
            }

            for (Allocation allocation : allocations) {
                for (CardKey key : allocation.keys()) {
                    key.setStatus(CardKeyStatus.SOLD);
                    key.setOrderId(orderId);
                    key.setOrderItemId(allocation.item().getId());
                    key.setSoldAt(LocalDateTime.now());
                    cardKeyRepository.save(key);
                }
            }

            order.setStatus(OrderStatus.DELIVERED);
            order.setDeliveredAt(LocalDateTime.now());
            orderRepository.save(order);

            // Award points
            awardPoints(order);

            // Send delivery email after transaction commits (async, non-blocking)
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emailService.sendDeliveryEmail(orderId);
                }
            });

            return buildReadOnlyDeliveryResult(order);
        } catch (BusinessException e) {
            log.warn("Deliver failed for order {}: {}", orderId, e.getMessage());
            return buildReadOnlyDeliveryResult(order);
        }
    }

    private Map<String, Object> buildReadOnlyDeliveryResult(Order order) {
        List<Map<String, Object>> groups = order.getStatus() == OrderStatus.DELIVERED
                ? buildCardKeyGroups(order.getId())
                : List.of();
        return buildDeliveryResult(order, groups);
    }

    private Map<String, Object> buildDeliveryResult(Order order, List<Map<String, Object>> groups) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        result.put("status", order.getStatus().name());
        result.put("groups", groups);
        return result;
    }

    @Override
    public String exportCardKeys(UUID orderId, UUID userId, String sessionToken) {
        Order order = getAccessibleOrderOrThrow(orderId, userId, sessionToken);
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAID, "订单未发货");
        }

        List<CardKey> keys = cardKeyRepository.findByOrderId(orderId);
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, String> deliveryNoteMap = buildDeliveryNoteMap(items);

        StringBuilder sb = new StringBuilder();
        sb.append("订单号: ").append(orderId).append("\n");
        sb.append("=".repeat(40)).append("\n\n");

        Map<UUID, List<CardKey>> grouped = keys.stream()
                .filter(k -> k.getOrderItemId() != null)
                .collect(Collectors.groupingBy(CardKey::getOrderItemId));

        for (OrderItem item : items) {
            List<CardKey> itemKeys = grouped.get(item.getId());
            String deliveryNote = normalizeDeliveryNote(deliveryNoteMap.get(item.getProductId()));
            if ((itemKeys == null || itemKeys.isEmpty()) && deliveryNote == null) {
                continue;
            }
            sb.append("商品: ").append(item.getProductTitle());
            if (item.getSpecName() != null) sb.append(" [").append(item.getSpecName()).append("]");
            sb.append("\n");
            if (deliveryNote != null) {
                sb.append("发货附言:\n").append(deliveryNote).append("\n");
            }
            if (itemKeys != null) {
                for (CardKey key : itemKeys) {
                    sb.append(key.getContent()).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private Order getAccessibleOrderOrThrow(UUID orderId, UUID userId, String sessionToken) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        if (!canAccessOrder(order, userId, sessionToken)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在");
        }
        return order;
    }

    private List<Map<String, Object>> buildCardKeyGroups(UUID orderId) {
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, String> deliveryNoteMap = buildDeliveryNoteMap(items);
        List<CardKey> keys = cardKeyRepository.findByOrderId(orderId);

        Map<UUID, List<CardKey>> grouped = keys.stream()
                .filter(k -> k.getOrderItemId() != null)
                .collect(Collectors.groupingBy(CardKey::getOrderItemId));

        List<Map<String, Object>> groups = new ArrayList<>();
        for (OrderItem item : items) {
            List<CardKey> itemKeys = grouped.get(item.getId());
            List<String> cardKeys = itemKeys == null ? List.of() : itemKeys.stream().map(CardKey::getContent).toList();
            String deliveryNote = normalizeDeliveryNote(deliveryNoteMap.get(item.getProductId()));
            if (cardKeys.isEmpty() && deliveryNote == null) {
                continue;
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("product_title", item.getProductTitle());
            g.put("spec_name", item.getSpecName());
            g.put("card_keys", cardKeys);
            g.put("delivery_note", deliveryNote);
            groups.add(g);
        }
        return groups;
    }

    private Map<UUID, String> buildDeliveryNoteMap(List<OrderItem> items) {
        List<UUID> productIds = items.stream()
                .map(OrderItem::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> noteMap = new HashMap<>();
        for (Product product : productRepository.findAllById(productIds)) {
            String deliveryNote = normalizeDeliveryNote(product.getDeliveryNote());
            if (deliveryNote != null) {
                noteMap.put(product.getId(), deliveryNote);
            }
        }
        return noteMap;
    }

    private String normalizeDeliveryNote(String deliveryNote) {
        if (!StringUtils.hasText(deliveryNote)) {
            return null;
        }
        return deliveryNote.trim();
    }

    private void awardPoints(Order order) {
        if (order.getUserId() == null) return;
        boolean pointsEnabled = siteConfigRepository.findByConfigKey("points_enabled")
                .map(c -> "true".equalsIgnoreCase(c.getConfigValue()))
                .orElse(false);
        if (!pointsEnabled) return;

        int pointsRate = siteConfigRepository.findByConfigKey("points_rate")
                .map(c -> { try { return Integer.parseInt(c.getConfigValue()); } catch (Exception e) { return 0; } })
                .orElse(0);
        if (pointsRate <= 0) return;

        int pointsEarned = order.getActualAmount().multiply(java.math.BigDecimal.valueOf(pointsRate))
                .setScale(0, java.math.RoundingMode.FLOOR).intValue();
        if (pointsEarned <= 0) return;

        User user = userRepository.findById(order.getUserId()).orElse(null);
        if (user == null) return;

        user.setPoints(user.getPoints() + pointsEarned);
        userRepository.save(user);

        PointsLog log = new PointsLog();
        log.setUserId(user.getId());
        log.setChangeAmount(pointsEarned);
        log.setBalanceAfter(user.getPoints());
        log.setReason("购物奖励积分");
        log.setOrderId(order.getId());
        pointsLogRepository.save(log);
    }

    private boolean canAccessOrder(Order order, UUID userId, String sessionToken) {
        if ("ADMIN".equals(RequestContext.getRole())) {
            return true;
        }
        if (userId != null && userId.equals(order.getUserId())) {
            return true;
        }
        return StringUtils.hasText(sessionToken) && sessionToken.equals(order.getSessionToken());
    }
}
