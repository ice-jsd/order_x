package org.dromara.ticket.adapter;

import cn.hutool.json.JSONUtil;
import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTask;
import org.dromara.ticket.domain.vo.TicketPurchaseTemplateVo;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class TicketOrderFlowSupport {

    private static final List<String> LIVEPOCKET_LOTTERY_TICKETS_URL_KEYS = List.of("ticketEntryUrl", "ticketsUrl", "lotteryTicketsUrl", "lotteryEntryUrl");
    private static final Pattern LIVEPOCKET_EVENT_PATH_PATTERN = Pattern.compile("/e/([^/?#]+)");
    private static final Pattern HANDS_SEGMENT_PATH_PATTERN = Pattern.compile("(.*/segment/[^/?#]+)");

    private TicketOrderFlowSupport() {
    }

    public static TicketOrderFlowStep step(String stepType, String currentStep, String label, Map<String, Object> options) {
        TicketOrderFlowStep step = new TicketOrderFlowStep();
        step.setStepType(stepType);
        step.setStepCode(stepType.toLowerCase());
        step.setCurrentStep(currentStep);
        step.setLabel(label);
        if (options != null) {
            step.setOptions(new LinkedHashMap<>(options));
        }
        return step;
    }

    public static Map<String, Object> parseTaskOptions(String taskOptions) {
        if (StringUtils.isBlank(taskOptions) || !JSONUtil.isTypeJSON(taskOptions)) {
            return new LinkedHashMap<>();
        }
        Object parsed = JSONUtil.parse(taskOptions);
        if (parsed instanceof cn.hutool.json.JSONObject object) {
            return new LinkedHashMap<>(object);
        }
        return new LinkedHashMap<>();
    }

    public static String defaultPurchaseType(String value) {
        return StringUtils.defaultIfBlank(value, "flash_sale");
    }

    public static boolean isFlashSale(String purchaseType) {
        return "flash_sale".equals(defaultPurchaseType(purchaseType));
    }

    public static boolean isLottery(String purchaseType) {
        return "lottery".equals(defaultPurchaseType(purchaseType));
    }

    public static String resolveConfigSchemaKey(TicketPlatformConfig platform, String purchaseType) {
        String platformCode = platform == null ? "platform" : StringUtils.defaultIfBlank(platform.getPlatformCode(), "platform");
        return platformCode + ":" + defaultPurchaseType(purchaseType);
    }

    public static Map<String, Object> baseOptions(TicketPlatformConfig platform, TicketSaleTask saleTask, TicketManagedAccount account) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("platformCode", platform.getPlatformCode());
        options.put("purchaseType", defaultPurchaseType(saleTask.getPurchaseType()));
        options.put("purchaseQuantity", saleTask.getPurchaseQuantity());
        options.put("configSchemaKey", saleTask.getConfigSchemaKey());
        if (account != null) {
            options.put("email", account.getEmail());
        }
        return options;
    }

    public static String initialPaymentStatus(String purchaseType, Map<String, Object> taskOptions) {
        if (isLottery(purchaseType)) {
            return "not_required";
        }
        String paymentMode = StringUtils.defaultIfBlank(readString(taskOptions, "paymentMode"), "pending_manual");
        return switch (paymentMode) {
            case "online", "credit_card" -> "pending_online";
            case "cod_store" -> "offline_pending";
            default -> "manual_pending";
        };
    }

    public static String queuedPaymentStatus(String purchaseType) {
        // Payment status is only meaningful after a successful submit/result callback.
        return "not_required";
    }

    public static TicketPurchaseTemplateVo buildTemplate(TicketPlatformConfig platform, String purchaseType, Map<String, Object> template, List<String> editableFields) {
        TicketPurchaseTemplateVo vo = new TicketPurchaseTemplateVo();
        vo.setPurchaseType(defaultPurchaseType(purchaseType));
        vo.setConfigSchemaKey(resolveConfigSchemaKey(platform, purchaseType));
        vo.setConfigTemplate(new LinkedHashMap<>(template));
        vo.setEditableFields(new ArrayList<>(editableFields));
        return vo;
    }

    public static String readString(Map<String, Object> options, String key) {
        if (options == null || !options.containsKey(key)) {
            return null;
        }
        Object value = options.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static String resolveLotteryEventUrlFromTaskOptions(String taskOptionsText) {
        Map<String, Object> taskOptions = parseTaskOptions(taskOptionsText);
        String eventUrl = readString(taskOptions, "eventUrl");
        if (StringUtils.isBlank(eventUrl)) {
            eventUrl = readString(taskOptions, "lotteryEntryUrl");
        }
        if (StringUtils.isBlank(eventUrl)) {
            eventUrl = readString(taskOptions, "lotteryEventUrl");
        }
        if (StringUtils.isBlank(eventUrl)) {
            eventUrl = firstLivePocketLotteryTicketsUrl(taskOptions);
        }
        return normalizeLotteryEventUrl(eventUrl);
    }

    public static String normalizeLotteryEventUrl(String rawUrl) {
        String url = StringUtils.trim(rawUrl);
        if (StringUtils.isBlank(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String host = StringUtils.defaultString(uri.getHost());
            String scheme = StringUtils.defaultIfBlank(uri.getScheme(), "https");
            java.util.regex.Matcher matcher = LIVEPOCKET_EVENT_PATH_PATTERN.matcher(StringUtils.defaultString(uri.getPath()));
            if (matcher.find()) {
                String normalizedHost = StringUtils.isNotBlank(host) ? host.toLowerCase(Locale.ROOT) : "livepocket.jp";
                return scheme.toLowerCase(Locale.ROOT) + "://" + normalizedHost + "/e/" + matcher.group(1);
            }
            java.util.regex.Matcher handsMatcher = HANDS_SEGMENT_PATH_PATTERN.matcher(StringUtils.defaultString(uri.getPath()));
            if (handsMatcher.find() && StringUtils.isNotBlank(host)) {
                return new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    null,
                    host.toLowerCase(Locale.ROOT),
                    -1,
                    StrUtil.removeSuffix(handsMatcher.group(1), "/"),
                    null,
                    null
                ).toString();
            }
            String normalizedPath = StrUtil.removeSuffix(StringUtils.defaultIfBlank(uri.getPath(), "/"), "/");
            if (StringUtils.isBlank(host)) {
                return StrUtil.removeSuffix(url.split("[?#]", 2)[0], "/");
            }
            return new URI(
                scheme.toLowerCase(Locale.ROOT),
                null,
                host.toLowerCase(Locale.ROOT),
                -1,
                StringUtils.defaultIfBlank(normalizedPath, "/"),
                null,
                null
            ).toString();
        } catch (Exception ex) {
            String sanitized = url.split("[?#]", 2)[0].trim();
            java.util.regex.Matcher matcher = LIVEPOCKET_EVENT_PATH_PATTERN.matcher(sanitized);
            if (matcher.find()) {
                return "https://livepocket.jp/e/" + matcher.group(1);
            }
            java.util.regex.Matcher handsMatcher = HANDS_SEGMENT_PATH_PATTERN.matcher(sanitized);
            if (handsMatcher.find()) {
                return StrUtil.removeSuffix(handsMatcher.group(1), "/");
            }
            return StrUtil.removeSuffix(sanitized, "/");
        }
    }

    public static String firstLivePocketLotteryTicketsUrl(Map<String, Object> options, String... extraUrls) {
        if (extraUrls != null) {
            for (String url : extraUrls) {
                String normalizedUrl = StringUtils.trim(url);
                if (isLivePocketLotteryTicketsUrl(normalizedUrl)) {
                    return normalizedUrl;
                }
            }
        }
        if (options != null && !options.isEmpty()) {
            for (String key : LIVEPOCKET_LOTTERY_TICKETS_URL_KEYS) {
                String url = StringUtils.trim(readString(options, key));
                if (isLivePocketLotteryTicketsUrl(url)) {
                    return url;
                }
            }
        }
        return "";
    }

    public static boolean isLivePocketLotteryTicketsUrl(String url) {
        return StringUtils.isNotBlank(url)
            && url.contains("livepocket.jp/e/")
            && url.contains("/receptions/")
            && url.contains("/tickets");
    }
}
