package org.dromara.ticket.adapter;

import cn.hutool.json.JSONUtil;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTask;
import org.dromara.ticket.domain.vo.TicketPurchaseTemplateVo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TicketOrderFlowSupport {

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
            return "manual_pending";
        }
        String paymentMode = StringUtils.defaultIfBlank(readString(taskOptions, "paymentMode"), "pending_manual");
        return switch (paymentMode) {
            case "online" -> "pending_online";
            case "cod_store" -> "offline_pending";
            default -> "manual_pending";
        };
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
}
