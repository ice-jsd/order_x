package org.dromara.ticket.adapter;

import cn.hutool.json.JSONUtil;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTask;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TicketOrderFlowSupport {

    private TicketOrderFlowSupport() {
    }

    public static TicketOrderFlowDefinition buildDefaultFlow(TicketPlatformConfig platform, TicketSaleTask saleTask, TicketManagedAccount account) {
        TicketOrderFlowDefinition definition = new TicketOrderFlowDefinition();
        definition.setFlowType(defaultFlowType(saleTask.getOrderFlowType()));
        definition.setFulfillmentType(defaultFulfillmentType(saleTask.getFulfillmentType()));
        definition.setPaymentMode(defaultPaymentMode(saleTask.getPaymentMode()));

        if ("cart_checkout".equals(definition.getFlowType())) {
            definition.getSteps().add(step("ADD_TO_CART", "carting", "加入购物车", baseOptions(platform, saleTask, account)));
        }

        if ("pickup_store".equals(definition.getFulfillmentType())) {
            definition.getSteps().add(step("SELECT_PICKUP_STORE", "selecting_fulfillment", "选择自提门店", parseTaskOptions(saleTask.getTaskOptions())));
        } else {
            definition.getSteps().add(step("SELECT_DELIVERY", "selecting_fulfillment", "选择配送方式", parseTaskOptions(saleTask.getTaskOptions())));
        }

        definition.getSteps().add(step("SELECT_PAYMENT_MODE", "selecting_payment", "选择支付方式", Map.of(
            "paymentMode", definition.getPaymentMode()
        )));
        definition.getSteps().add(step("SUBMIT_ORDER", "creating_order", "提交订单", baseOptions(platform, saleTask, account)));

        if ("online".equals(definition.getPaymentMode())) {
            definition.getSteps().add(step("CREATE_ONLINE_PAYMENT", "awaiting_payment", "创建线上支付", parseTaskOptions(saleTask.getTaskOptions())));
        } else if ("pending_manual".equals(definition.getPaymentMode())) {
            definition.getSteps().add(step("CONFIRM_PENDING_PAYMENT", "awaiting_payment", "等待人工支付", parseTaskOptions(saleTask.getTaskOptions())));
        }

        return definition;
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

    public static String defaultFlowType(String value) {
        return StringUtils.defaultIfBlank(value, "direct_order");
    }

    public static String defaultFulfillmentType(String value) {
        return StringUtils.defaultIfBlank(value, "shipping");
    }

    public static String defaultPaymentMode(String value) {
        return StringUtils.defaultIfBlank(value, "pending_manual");
    }

    public static String initialPaymentStatus(String paymentMode) {
        return switch (defaultPaymentMode(paymentMode)) {
            case "online" -> "pending_online";
            case "cod_store" -> "offline_pending";
            case "pending_manual" -> "manual_pending";
            default -> "unknown";
        };
    }

    public static Map<String, Object> baseOptions(TicketPlatformConfig platform, TicketSaleTask saleTask, TicketManagedAccount account) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("platformCode", platform.getPlatformCode());
        options.put("productId", saleTask.getProductId());
        options.put("purchaseQuantity", saleTask.getPurchaseQuantity());
        if (account != null) {
            options.put("email", account.getEmail());
        }
        return options;
    }
}
