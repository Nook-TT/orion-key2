package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.OrderStatus;
import com.orionkey.exception.BusinessException;
import com.orionkey.entity.Order;
import com.orionkey.entity.PaymentChannel;
import com.orionkey.entity.WebhookEvent;
import com.orionkey.repository.OrderRepository;
import com.orionkey.repository.PaymentChannelRepository;
import com.orionkey.repository.WebhookEventRepository;
import com.orionkey.service.DeliverService;
import com.orionkey.service.EpayService;
import com.orionkey.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookServiceImpl implements WebhookService {

    private final WebhookEventRepository webhookEventRepository;
    private final OrderRepository orderRepository;
    private final PaymentChannelRepository paymentChannelRepository;
    private final EpayService epayService;
    private final DeliverService deliverService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public String processWebhook(String channelCode, Map<String, Object> payload) {
        log.info("Webhook received: channel={}, payload={}", channelCode, payload);

        String eventId = (String) payload.getOrDefault("event_id", UUID.randomUUID().toString());
        String orderIdStr = (String) payload.get("order_id");

        // Check idempotency
        Optional<WebhookEvent> existingEvent = webhookEventRepository.findByEventId(eventId);
        if (existingEvent.isPresent()) {
            log.info("Webhook event already processed: {}", eventId);
            return "OK";
        }

        // Save webhook event
        WebhookEvent event = new WebhookEvent();
        event.setEventId(eventId);
        event.setChannelCode(channelCode);
        event.setOrderId(orderIdStr != null ? UUID.fromString(orderIdStr) : null);
        event.setPayload(payload.toString());
        event.setProcessResult("PROCESSING");

        if (orderIdStr != null) {
            UUID orderId = UUID.fromString(orderIdStr);
            event.setOrderId(orderId);

            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                if (order.getStatus() == OrderStatus.PENDING) {
                    order.setStatus(OrderStatus.PAID);
                    order.setPaidAt(LocalDateTime.now());
                    orderRepository.save(order);
                    Map<String, Object> deliverResult = deliverService.deliverOrderSystem(orderId);
                    event.setProcessResult("SUCCESS_" + deliverResult.get("status"));
                    log.info("Order marked as {} via webhook: {}", deliverResult.get("status"), orderId);
                } else {
                    event.setProcessResult("SKIPPED_" + order.getStatus().name());
                    log.info("Order already {}: {}", order.getStatus(), orderId);
                }
            } else {
                event.setProcessResult("ORDER_NOT_FOUND");
                log.warn("Webhook order not found: {}", orderId);
            }
        }

        webhookEventRepository.save(event);
        return "OK";
    }

    @Override
    @Transactional
    public String processEpayCallback(Map<String, String> params) {
        String tradeNo = params.get("trade_no");
        String outTradeNo = params.get("out_trade_no");
        String tradeStatus = params.get("trade_status");
        String money = params.get("money");
        String sign = params.get("sign");

        log.info("Epay callback: out_trade_no={}, trade_status={}, money={}", outTradeNo, tradeStatus, money);

        // Use trade_no as event ID for idempotency
        String eventId = "epay_" + (tradeNo != null ? tradeNo : UUID.randomUUID().toString());
        Optional<WebhookEvent> existingEvent = webhookEventRepository.findByEventId(eventId);
        if (existingEvent.isPresent()) {
            log.info("Epay callback already processed: {}", eventId);
            return "SUCCESS";
        }

        // Step 1: Parse order ID
        UUID orderId;
        try {
            orderId = UUID.fromString(outTradeNo);
        } catch (IllegalArgumentException e) {
            log.error("Epay callback invalid out_trade_no: {}", outTradeNo);
            return "FAIL";
        }

        // Step 2: Resolve merchant key from order's channel config
        String merchantKey = resolveMerchantKey(orderId);

        // Step 3: Verify signature
        if (!epayService.verifySign(merchantKey, params, sign)) {
            log.error("Epay callback signature verification failed: out_trade_no={}", outTradeNo);
            WebhookEvent event = new WebhookEvent();
            event.setEventId(eventId);
            event.setChannelCode("epay");
            event.setPayload(params.toString());
            event.setProcessResult("SIGN_VERIFY_FAIL");
            webhookEventRepository.save(event);
            return "FAIL";
        }

        // Step 4: Check trade status
        if (!"TRADE_SUCCESS".equals(tradeStatus)) {
            log.info("Epay callback non-success status: {}", tradeStatus);
            WebhookEvent event = new WebhookEvent();
            event.setEventId(eventId);
            event.setChannelCode("epay");
            event.setPayload(params.toString());
            event.setProcessResult("SKIPPED_" + tradeStatus);
            webhookEventRepository.save(event);
            return "SUCCESS";
        }

        // Step 5: Process payment
        WebhookEvent event = new WebhookEvent();
        event.setEventId(eventId);
        event.setChannelCode("epay");
        event.setOrderId(orderId);
        event.setPayload(params.toString());

        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            event.setProcessResult("ORDER_NOT_FOUND");
            log.warn("Epay callback order not found: {}", orderId);
            webhookEventRepository.save(event);
            return "SUCCESS";
        }

        // Step 6: Verify amount matches
        if (money != null) {
            BigDecimal callbackAmount = new BigDecimal(money);
            if (order.getActualAmount().compareTo(callbackAmount) != 0) {
                log.error("Epay callback amount mismatch: order={}, callback={}", order.getActualAmount(), callbackAmount);
                event.setProcessResult("AMOUNT_MISMATCH");
                webhookEventRepository.save(event);
                return "SUCCESS";
            }
        }

        // Step 7: Idempotent update order status
        if (order.getStatus() == OrderStatus.PENDING) {
            order.setStatus(OrderStatus.PAID);
            order.setPaidAt(LocalDateTime.now());
            orderRepository.save(order);
            Map<String, Object> deliverResult = deliverService.deliverOrderSystem(orderId);
            event.setProcessResult("SUCCESS_" + deliverResult.get("status"));
            log.info("Epay callback: order {} marked as {}", orderId, deliverResult.get("status"));
        } else {
            event.setProcessResult("SKIPPED_" + order.getStatus().name());
            log.info("Epay callback: order {} already {}", orderId, order.getStatus());
        }

        webhookEventRepository.save(event);
        return "SUCCESS";
    }

    /**
     * 根据订单的 paymentMethod 查找渠道 config_data 中的 merchant key。
     * 所有配置均从数据库读取，缺失则抛出异常。
     */
    private String resolveMerchantKey(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order != null && order.getPaymentMethod() != null) {
            PaymentChannel channel = paymentChannelRepository
                    .findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0)
                    .orElse(null);
            if (channel != null && channel.getConfigData() != null) {
                try {
                    Map<String, Object> cfg = objectMapper.readValue(
                            channel.getConfigData(), new TypeReference<>() {});
                    Object key = cfg.get("key");
                    if (key != null && !key.toString().isBlank()) {
                        return key.toString();
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse channel config for key resolution: {}", e.getMessage());
                }
            }
        }
        log.error("Cannot resolve merchant key for order {}: channel config missing 'key' field", orderId);
        throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                "支付渠道配置缺少 key，请在后台「支付渠道管理」中完善配置");
    }
}
