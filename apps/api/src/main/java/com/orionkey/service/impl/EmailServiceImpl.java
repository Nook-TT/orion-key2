package com.orionkey.service.impl;

import com.orionkey.entity.CardKey;
import com.orionkey.entity.Order;
import com.orionkey.entity.OrderItem;
import com.orionkey.entity.Product;
import com.orionkey.repository.CardKeyRepository;
import com.orionkey.repository.OrderItemRepository;
import com.orionkey.repository.OrderRepository;
import com.orionkey.repository.ProductRepository;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final ProductRepository productRepository;
    private final SiteConfigRepository siteConfigRepository;

    @Value("${mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${mail.site-url:https://orionkey.shop}")
    private String siteUrl;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    @Value("${spring.mail.host:mail.example.com}")
    private String defaultMailHost;

    @Value("${spring.mail.port:465}")
    private int defaultMailPort;

    @Value("${spring.mail.password:your_password}")
    private String defaultMailPassword;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Async
    @Override
    public void sendDeliveryEmail(UUID orderId) {
        MailSettings mailSettings = loadMailSettings();
        if (!mailSettings.enabled()) {
            return;
        }

        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null || order.getEmail() == null || order.getEmail().isBlank()) {
                log.warn("Cannot send delivery email: order {} not found or no email", orderId);
                return;
            }

            List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
            List<CardKey> keys = cardKeyRepository.findByOrderId(orderId);
            Map<UUID, List<CardKey>> grouped = keys.stream()
                    .filter(k -> k.getOrderItemId() != null)
                    .collect(Collectors.groupingBy(CardKey::getOrderItemId));
            Map<UUID, String> deliveryNoteMap = buildDeliveryNoteMap(items);

            String siteName = siteConfigRepository.findByConfigKey("site_name")
                    .map(c -> c.getConfigValue())
                    .orElse("Orion Key");

            if (!mailSettings.isComplete()) {
                log.warn("Mail delivery skipped: SMTP config incomplete for order {}", orderId);
                return;
            }

            String html = buildHtml(order, items, grouped, deliveryNoteMap, siteName, mailSettings.siteUrl());

            String displayName = siteName == null || siteName.isBlank() ? "订单通知" : siteName;

            JavaMailSender sender = mailSettings.useCustomTransport() ? buildCustomMailSender(mailSettings) : mailSender;
            String resolvedFromAddress = mailSettings.username();
            if (resolvedFromAddress == null || resolvedFromAddress.isBlank()) {
                log.warn("Mail delivery skipped: sender address missing for order {}", orderId);
                return;
            }

            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(resolvedFromAddress, displayName);
            helper.setTo(order.getEmail());
            helper.setSubject("【" + siteName + "】订单发货通知 - " + orderId.toString().substring(0, 8));
            helper.setText(html, true);

            sender.send(message);
            log.info("Delivery email sent for order {} to {}", orderId, order.getEmail());
        } catch (MessagingException e) {
            log.error("Failed to send delivery email for order {}: {}", orderId, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error sending delivery email for order {}: {}", orderId, e.getMessage(), e);
        }
    }

    private String buildHtml(Order order, List<OrderItem> items,
                             Map<UUID, List<CardKey>> grouped, Map<UUID, String> deliveryNoteMap,
                             String siteName, String resolvedSiteUrl) {
        UUID orderId = order.getId();
        BigDecimal amount = order.getActualAmount() != null ? order.getActualAmount() : order.getTotalAmount();
        LocalDateTime createdAt = order.getCreatedAt();
        String orderUrl = buildOrderUrl(resolvedSiteUrl, orderId);

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>");
        sb.append("<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">");
        sb.append("</head><body style=\"margin:0;padding:0;background-color:#eef2f6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;color:#1f2937;\">");

        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#eef2f6;padding:32px 12px;\">");
        sb.append("<tr><td align=\"center\">");
        sb.append("<table role=\"presentation\" width=\"640\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:640px;width:100%;background-color:#ffffff;border:1px solid #d7dee7;border-radius:14px;overflow:hidden;\">");

        sb.append("<tr><td style=\"padding:28px 36px 24px;border-bottom:1px solid #e5e7eb;background-color:#ffffff;\">");
        sb.append("<p style=\"margin:0 0 10px;color:#64748b;font-size:12px;letter-spacing:0.12em;text-transform:uppercase;\">自动发货通知</p>");
        sb.append("<h1 style=\"margin:0;color:#111827;font-size:28px;font-weight:700;line-height:1.3;\">")
          .append(escapeHtml(siteName)).append("</h1>");
        sb.append("</td></tr>");

        sb.append("<tr><td style=\"padding:32px 36px 12px;\">");
        sb.append("<h2 style=\"margin:0 0 10px;color:#111827;font-size:22px;font-weight:700;\">订单已发货</h2>");
        sb.append("<p style=\"margin:0;color:#475569;font-size:14px;line-height:1.8;\">您购买的卡密已经整理完成，以下是本次订单的发货内容。建议尽快保存，并避免将卡密内容转发给他人。</p>");
        sb.append("</td></tr>");

        sb.append("<tr><td style=\"padding:12px 36px 28px;\">");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f8fafc;border:1px solid #e2e8f0;border-radius:12px;\">");
        sb.append("<tr><td style=\"padding:16px 18px;border-bottom:1px solid #e2e8f0;color:#64748b;font-size:12px;letter-spacing:0.08em;text-transform:uppercase;\">订单信息</td></tr>");
        sb.append("<tr><td style=\"padding:6px 18px 0;\">");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">");
        sb.append("<tr><td style=\"padding:8px 0;color:#64748b;font-size:13px;\">订单编号</td>");
        sb.append("<td style=\"padding:8px 0;text-align:right;color:#111827;font-size:13px;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,'Liberation Mono','Courier New',monospace;word-break:break-all;\">")
          .append(orderId).append("</td></tr>");
        sb.append("<tr><td style=\"padding:8px 0;color:#64748b;font-size:13px;\">支付金额</td>");
        sb.append("<td style=\"padding:8px 0;text-align:right;color:#111827;font-size:14px;font-weight:700;\">¥")
          .append(amount).append("</td></tr>");
        if (createdAt != null) {
            sb.append("<tr><td style=\"padding:8px 0 16px;color:#64748b;font-size:13px;\">下单时间</td>");
            sb.append("<td style=\"padding:8px 0 16px;text-align:right;color:#111827;font-size:13px;\">")
              .append(createdAt.format(DATE_FMT)).append("</td></tr>");
        } else {
            sb.append("<tr><td style=\"padding:0 0 16px;\"></td><td style=\"padding:0 0 16px;\"></td></tr>");
        }
        sb.append("</table>");
        sb.append("</td></tr>");
        sb.append("</table>");
        sb.append("</td></tr>");

        sb.append("<tr><td style=\"padding:0 36px 8px;\">");
        sb.append("<h3 style=\"margin:0;color:#111827;font-size:16px;font-weight:700;\">发货内容</h3>");
        sb.append("</td></tr>");
        sb.append("<tr><td style=\"padding:0 36px 12px;\">");
        for (OrderItem item : items) {
            List<CardKey> itemKeys = grouped.get(item.getId());
            String deliveryNote = normalizeDeliveryNote(deliveryNoteMap.get(item.getProductId()));
            if ((itemKeys == null || itemKeys.isEmpty()) && deliveryNote == null) {
                continue;
            }

            String title = escapeHtml(item.getProductTitle());
            if (item.getSpecName() != null && !item.getSpecName().isBlank()) {
                title += " <span style=\"color:#64748b;font-size:12px;font-weight:500;\">[" + escapeHtml(item.getSpecName()) + "]</span>";
            }

            sb.append("<div style=\"margin-bottom:14px;border:1px solid #e5e7eb;border-radius:12px;overflow:hidden;background-color:#ffffff;\">");
            sb.append("<div style=\"padding:12px 16px;background-color:#f8fafc;border-bottom:1px solid #e5e7eb;font-size:14px;font-weight:700;color:#111827;line-height:1.5;\">")
              .append(title).append("</div>");
            sb.append("<div style=\"padding:14px 16px;\">");
            if (itemKeys != null) {
                for (CardKey key : itemKeys) {
                    sb.append("<div style=\"margin-bottom:8px;padding:10px 12px;background-color:#f8fafc;border:1px solid #e2e8f0;border-radius:10px;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,'Liberation Mono','Courier New',monospace;font-size:13px;line-height:1.6;color:#0f172a;word-break:break-all;\">")
                      .append(escapeHtml(key.getContent())).append("</div>");
                }
            }
            if (deliveryNote != null) {
                sb.append("<div style=\"margin-top:")
                  .append(itemKeys == null || itemKeys.isEmpty() ? "0" : "10")
                  .append("px;padding:12px 14px;background-color:#fff7ed;border:1px solid #fed7aa;border-radius:10px;\">");
                sb.append("<div style=\"margin:0 0 6px;color:#9a3412;font-size:12px;font-weight:700;letter-spacing:0.04em;text-transform:uppercase;\">发货附言</div>");
                sb.append("<div style=\"color:#9a3412;font-size:13px;line-height:1.8;white-space:pre-line;\">")
                  .append(escapeHtmlWithLineBreaks(deliveryNote))
                  .append("</div>");
                sb.append("</div>");
            }
            sb.append("</div></div>");
        }
        sb.append("</td></tr>");

        sb.append("<tr><td style=\"padding:8px 36px 28px;\">");
        sb.append("<div style=\"padding:14px 16px;background-color:#fff7ed;border:1px solid #fed7aa;border-radius:12px;color:#9a3412;font-size:13px;line-height:1.8;\">");
        sb.append("请妥善保管卡密信息。若需再次查看订单，可使用下方按钮返回订单页。");
        sb.append("</div>");
        sb.append("</td></tr>");

        sb.append("<tr><td style=\"padding:0 36px 32px;\">");
        sb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr><td>");
        sb.append("<a href=\"").append(escapeHtml(orderUrl)).append("\" style=\"display:inline-block;background-color:#111827;color:#ffffff;text-decoration:none;padding:12px 20px;border-radius:10px;font-size:14px;font-weight:600;\">查看订单详情</a>");
        sb.append("</td></tr></table>");
        sb.append("</td></tr>");

        sb.append("<tr><td style=\"padding:20px 36px;background-color:#f8fafc;border-top:1px solid #e5e7eb;\">");
        sb.append("<p style=\"margin:0 0 6px;color:#475569;font-size:12px;line-height:1.7;\">此邮件由系统自动发送，请勿直接回复。</p>");
        sb.append("<p style=\"margin:0;color:#94a3b8;font-size:12px;line-height:1.7;\">&copy; ")
          .append(java.time.Year.now().getValue()).append(" ")
          .append(escapeHtml(siteName)).append("</p>");
        sb.append("</td></tr>");

        sb.append("</table></td></tr></table>");
        sb.append("</body></html>");

        return sb.toString();
    }

    private MailSettings loadMailSettings() {
        Optional<String> hostOverride = findConfigValue("mail_host");
        Optional<String> portOverride = findConfigValue("mail_port");
        Optional<String> usernameOverride = findConfigValue("mail_username");
        Optional<String> passwordOverride = findConfigValue("mail_password");

        boolean useCustomTransport = hostOverride.isPresent()
                || portOverride.isPresent()
                || usernameOverride.isPresent()
                || passwordOverride.isPresent();

        boolean enabled = siteConfigRepository.findByConfigKey("mail_enabled")
                .map(config -> Boolean.parseBoolean(trimToEmpty(config.getConfigValue())))
                .orElse(mailEnabled);

        String resolvedSiteUrl = findConfigValue("mail_site_url")
                .filter(value -> !value.isBlank())
                .orElse(trimToEmpty(siteUrl));

        String resolvedHost = hostOverride
                .map(this::normalizeMailHost)
                .orElseGet(() -> normalizeMailHost(defaultMailHost));
        int resolvedPort = portOverride
                .map(this::parsePort)
                .orElse(defaultMailPort);
        String resolvedUsername = usernameOverride
                .map(this::normalizeMailUsername)
                .orElseGet(() -> normalizeMailUsername(fromAddress));
        String resolvedPassword = passwordOverride
                .map(this::normalizeMailPassword)
                .orElseGet(() -> normalizeMailPassword(defaultMailPassword));

        return new MailSettings(
                enabled,
                resolvedHost,
                resolvedPort,
                resolvedUsername,
                resolvedPassword,
                resolvedSiteUrl,
                useCustomTransport
        );
    }

    private JavaMailSender buildCustomMailSender(MailSettings mailSettings) {
        JavaMailSenderImpl customSender = new JavaMailSenderImpl();
        customSender.setHost(mailSettings.host());
        customSender.setPort(mailSettings.port());
        customSender.setUsername(mailSettings.username());
        customSender.setPassword(mailSettings.password());

        Properties props = customSender.getJavaMailProperties();
        boolean sslMode = mailSettings.port() == 465;
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", String.valueOf(sslMode));
        props.put("mail.smtp.starttls.enable", String.valueOf(!sslMode));
        props.put("mail.smtp.starttls.required", String.valueOf(!sslMode));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return customSender;
    }

    private Optional<String> findConfigValue(String key) {
        return siteConfigRepository.findByConfigKey(key)
                .map(config -> trimToEmpty(config.getConfigValue()));
    }

    private int parsePort(String value) {
        try {
            return Integer.parseInt(trimToEmpty(value));
        } catch (NumberFormatException e) {
            return defaultMailPort;
        }
    }

    private String buildOrderUrl(String resolvedSiteUrl, UUID orderId) {
        String baseUrl = trimToEmpty(resolvedSiteUrl);
        if (baseUrl.isBlank()) {
            return "/order/query?orderId=" + orderId;
        }
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalizedBaseUrl + "/order/query?orderId=" + orderId;
    }

    private String normalizeMailHost(String value) {
        String normalized = trimToEmpty(value);
        return "mail.example.com".equalsIgnoreCase(normalized) ? "" : normalized;
    }

    private String normalizeMailUsername(String value) {
        String normalized = trimToEmpty(value);
        return "noreply@example.com".equalsIgnoreCase(normalized) ? "" : normalized;
    }

    private String normalizeMailPassword(String value) {
        String normalized = trimToEmpty(value);
        return "your_password".equals(normalized) ? "" : normalized;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
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
        String normalized = trimToEmpty(deliveryNote);
        return normalized.isBlank() ? null : normalized;
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }

    private static String escapeHtmlWithLineBreaks(String input) {
        return escapeHtml(input).replace("\r\n", "\n").replace("\n", "<br>");
    }

    private record MailSettings(
            boolean enabled,
            String host,
            int port,
            String username,
            String password,
            String siteUrl,
            boolean useCustomTransport
    ) {
        private boolean isComplete() {
            return !host.isBlank() && !username.isBlank() && !password.isBlank();
        }
    }
}
