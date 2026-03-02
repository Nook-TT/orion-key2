package com.orionkey.service;

import java.util.Map;
import java.util.UUID;

public interface OrderService {

    Map<String, Object> createDirectOrder(Map<String, Object> request, UUID userId, String clientIp, String sessionToken);

    Map<String, Object> createCartOrder(Map<String, Object> request, UUID userId, String clientIp, String sessionToken);

    Map<String, Object> getOrderStatus(UUID orderId, UUID userId, String sessionToken);

    Map<String, Object> refreshOrderStatus(UUID orderId, UUID userId, String sessionToken);

    void expireOrders();
}
