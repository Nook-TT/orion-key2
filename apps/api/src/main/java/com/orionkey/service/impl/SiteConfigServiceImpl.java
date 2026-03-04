package com.orionkey.service.impl;

import com.orionkey.entity.SiteConfig;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.service.SiteConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SiteConfigServiceImpl implements SiteConfigService {

    private final SiteConfigRepository siteConfigRepository;

    @Value("${mail.enabled:true}")
    private boolean defaultMailEnabled;

    @Value("${mail.site-url:https://example.com}")
    private String defaultMailSiteUrl;

    @Value("${spring.mail.host:mail.example.com}")
    private String defaultMailHost;

    @Value("${spring.mail.port:465}")
    private int defaultMailPort;

    @Value("${spring.mail.username:noreply@example.com}")
    private String defaultMailUsername;

    @Value("${spring.mail.password:your_password}")
    private String defaultMailPassword;

    private static final List<String> PUBLIC_KEYS = List.of(
            "site_name", "site_slogan", "site_description", "logo_url", "favicon_url",
            "announcement_enabled", "announcement", "popup_enabled", "popup_content",
            "contact_email", "contact_telegram", "points_enabled", "points_rate",
            "maintenance_enabled", "maintenance_message", "footer_text", "github_url", "custom_css"
    );

    private static final List<String> MAIL_KEYS = List.of(
            "mail_enabled", "mail_site_url", "mail_host", "mail_port", "mail_username", "mail_password"
    );

    @Override
    public Map<String, Object> getPublicConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : PUBLIC_KEYS) {
            siteConfigRepository.findByConfigKey(key).ifPresent(c -> {
                String val = c.getConfigValue();
                if ("true".equalsIgnoreCase(val) || "false".equalsIgnoreCase(val)) {
                    result.put(key, Boolean.parseBoolean(val));
                } else {
                    try {
                        result.put(key, Integer.parseInt(val));
                    } catch (NumberFormatException e) {
                        result.put(key, val);
                    }
                }
            });
        }
        return result;
    }

    @Override
    public List<?> getAllConfigs() {
        LinkedHashMap<String, SiteConfig> configMap = new LinkedHashMap<>();
        for (SiteConfig config : siteConfigRepository.findAll()) {
            configMap.put(config.getConfigKey(), config);
        }

        appendDefaultMailConfig(configMap, "mail_enabled", String.valueOf(defaultMailEnabled));
        appendDefaultMailConfig(configMap, "mail_site_url", normalizeSiteUrl(defaultMailSiteUrl));
        appendDefaultMailConfig(configMap, "mail_host", normalizeMailHost(defaultMailHost));
        appendDefaultMailConfig(configMap, "mail_port", String.valueOf(defaultMailPort));
        appendDefaultMailConfig(configMap, "mail_username", normalizeMailUsername(defaultMailUsername));
        appendDefaultMailConfig(configMap, "mail_password", normalizeMailPassword(defaultMailPassword));

        return configMap.values().stream()
                .map(c -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("config_key", c.getConfigKey());
                    map.put("config_value", c.getConfigValue());
                    map.put("config_group", c.getConfigGroup());
                    return map;
                }).toList();
    }

    @Override
    @Transactional
    public void updateConfigs(List<Map<String, String>> configs) {
        for (Map<String, String> item : configs) {
            String key = item.get("config_key");
            String value = item.get("config_value");
            SiteConfig config = siteConfigRepository.findByConfigKey(key)
                    .orElseGet(() -> {
                        SiteConfig c = new SiteConfig();
                        c.setConfigKey(key);
                        return c;
                    });
            config.setConfigValue(value);
            String configGroup = resolveConfigGroup(key);
            if (configGroup != null) {
                config.setConfigGroup(configGroup);
            }
            siteConfigRepository.save(config);
        }
    }

    @Override
    @Transactional
    public void toggleMaintenance(boolean enabled) {
        SiteConfig config = siteConfigRepository.findByConfigKey("maintenance_enabled")
                .orElseGet(() -> {
                    SiteConfig c = new SiteConfig();
                    c.setConfigKey("maintenance_enabled");
                    c.setConfigGroup("site");
                    return c;
                });
        config.setConfigValue(String.valueOf(enabled));
        siteConfigRepository.save(config);
    }

    private void appendDefaultMailConfig(Map<String, SiteConfig> configMap, String key, String value) {
        if (configMap.containsKey(key)) {
            return;
        }

        SiteConfig config = new SiteConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        config.setConfigGroup("mail");
        configMap.put(key, config);
    }

    private String resolveConfigGroup(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (MAIL_KEYS.contains(key)) {
            return "mail";
        }
        if (PUBLIC_KEYS.contains(key)) {
            return "site";
        }
        if (key.startsWith("risk_")) {
            return "risk";
        }
        return null;
    }

    private String normalizeMailHost(String value) {
        return "mail.example.com".equalsIgnoreCase(trimToEmpty(value)) ? "" : trimToEmpty(value);
    }

    private String normalizeMailUsername(String value) {
        return "noreply@example.com".equalsIgnoreCase(trimToEmpty(value)) ? "" : trimToEmpty(value);
    }

    private String normalizeMailPassword(String value) {
        return "your_password".equals(trimToEmpty(value)) ? "" : trimToEmpty(value);
    }

    private String normalizeSiteUrl(String value) {
        return "https://example.com".equalsIgnoreCase(trimToEmpty(value)) ? "" : trimToEmpty(value);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
