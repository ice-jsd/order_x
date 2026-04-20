package org.dromara.ticket.service;

import cn.hutool.core.convert.Convert;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.ticket.adapter.TicketOrderFlowSupport;
import org.dromara.ticket.domain.TicketMockPlatformOrder;
import org.dromara.ticket.mapper.TicketMockPlatformOrderMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TicketMockPlatformService {

    private static final String DEFAULT_TENANT_ID = "000000";
    private static final long TIMEOUT_DELAY_MS = TimeUnit.SECONDS.toMillis(35);

    private final TicketMockPlatformOrderMapper ticketMockPlatformOrderMapper;
    private final ObjectMapper objectMapper;

    public Map<String, Object> acceptStep(String platformCode, Map<String, Object> payload) {
        String tenantId = resolveTenantId(payload);
        return TenantHelper.dynamic(tenantId, () -> acceptStepInternal(platformCode, payload, tenantId));
    }

    private Map<String, Object> acceptStepInternal(String platformCode, Map<String, Object> payload, String tenantId) {
        Map<String, Object> step = getMap(payload.get("step"));
        Map<String, Object> stepOptions = getMap(step.get("options"));
        Map<String, Object> taskOptions = getMap(payload.get("taskOptions"));
        String stepType = getString(step.get("stepType"), "SUBMIT_ORDER");
        String purchaseType = getString(payload.get("purchaseType"), TicketOrderFlowSupport.defaultPurchaseType(null));
        String paymentMode = getString(taskOptions.get("paymentMode"), "pending_manual");
        String mockBehavior = getString(taskOptions.get("mockBehavior"), "");

        Map<String, Object> response = buildResponse(platformCode, payload, stepType, stepOptions, taskOptions, purchaseType, paymentMode, mockBehavior);

        TicketMockPlatformOrder record = new TicketMockPlatformOrder();
        record.setMockOrderId(IdGeneratorUtil.nextLongId());
        record.setTenantId(tenantId);
        record.setPlatformCode(platformCode);
        record.setExecutionId(Convert.toLong(payload.get("executionId"), null));
        record.setTaskId(Convert.toLong(payload.get("taskId"), null));
        record.setAccountId(Convert.toLong(payload.get("accountId"), null));
        record.setPurchaseType(purchaseType);
        record.setStepType(stepType);
        record.setPurchaseQuantity(Convert.toInt(payload.get("purchaseQuantity"), null));
        record.setPickupStoreCode(getString(taskOptions.get("pickupStoreCode"), getString(stepOptions.get("pickupStoreCode"), null)));
        record.setDeliveryOption(getString(taskOptions.get("deliveryOption"), getString(stepOptions.get("deliveryOption"), null)));
        record.setRequestPayload(writeJson(payload));
        record.setResponsePayload(writeJson(response));
        record.setMockStatus(getString(response.get("status"), "submitted"));
        record.setMockOrderNo(getString(response.get("orderNo"), null));
        record.setPaymentStatus(getString(response.get("paymentStatus"), null));
        record.setCreatedAt(new Date());
        record.setDelFlag(0L);
        ticketMockPlatformOrderMapper.insert(record);

        if ("timeout".equalsIgnoreCase(mockBehavior)) {
            sleepForTimeout();
        }
        return response;
    }

    private Map<String, Object> buildResponse(
        String platformCode,
        Map<String, Object> payload,
        String stepType,
        Map<String, Object> stepOptions,
        Map<String, Object> taskOptions,
        String purchaseType,
        String paymentMode,
        String mockBehavior
    ) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("status", "running");
        response.put("message", "mock step accepted");

        Map<String, Object> mockData = new LinkedHashMap<>();
        mockData.put("platformCode", platformCode);
        mockData.put("stepType", stepType);
        mockData.put("purchaseType", purchaseType);
        mockData.put("paymentMode", paymentMode);
        if (taskOptions.containsKey("pickupStoreCode")) {
            mockData.put("pickupStoreCode", taskOptions.get("pickupStoreCode"));
        }
        if (taskOptions.containsKey("deliveryOption")) {
            mockData.put("deliveryOption", taskOptions.get("deliveryOption"));
        }
        if (!stepOptions.isEmpty()) {
            mockData.put("stepOptions", stepOptions);
        }

        if ("timeout".equalsIgnoreCase(mockBehavior)) {
            response.put("success", false);
            response.put("status", "timeout");
            response.put("message", "mock timeout triggered");
            response.put("paymentStatus", TicketOrderFlowSupport.initialPaymentStatus(purchaseType, taskOptions));
            response.put("mockData", mockData);
            return response;
        }

        switch (stepType) {
            case "ADD_TO_CART" -> {
                response.put("status", "carted");
                response.put("message", "已加入购物车");
            }
            case "DIRECT_BUY" -> {
                response.put("status", "checking_out");
                response.put("message", "已准备直接下单");
            }
            case "SELECT_PICKUP_STORE" -> {
                response.put("status", "fulfillment_selected");
                response.put("message", "已选择自提门店");
            }
            case "SELECT_DELIVERY" -> {
                response.put("status", "fulfillment_selected");
                response.put("message", "已选择配送方式");
            }
            case "SELECT_PAYMENT_MODE" -> {
                response.put("status", "payment_mode_selected");
                response.put("message", "已选择支付方式");
                response.put("paymentStatus", TicketOrderFlowSupport.initialPaymentStatus(purchaseType, taskOptions));
            }
            case "SUBMIT_ORDER" -> {
                if ("fail_submit".equalsIgnoreCase(mockBehavior)) {
                    response.put("success", false);
                    response.put("status", "failed");
                    response.put("message", "mock submit failed");
                } else {
                    response.put("status", "submitted");
                    response.put("message", "mock order submitted");
                    response.put("orderNo", buildMockOrderNo(platformCode));
                    response.put("paymentStatus", TicketOrderFlowSupport.initialPaymentStatus(purchaseType, taskOptions));
                }
            }
            case "CREATE_ONLINE_PAYMENT" -> {
                if ("fail_payment".equalsIgnoreCase(mockBehavior)) {
                    response.put("success", false);
                    response.put("status", "failed");
                    response.put("message", "mock payment creation failed");
                } else {
                    response.put("status", "pending_payment");
                    response.put("message", "mock online payment created");
                    response.put("orderNo", getString(payload.get("orderNo"), null));
                    response.put("paymentStatus", "pending_online");
                }
            }
            case "CONFIRM_PENDING_PAYMENT" -> {
                response.put("status", "pending_payment");
                response.put("message", "订单已提交，等待后续支付");
                response.put("orderNo", getString(payload.get("orderNo"), null));
                response.put("paymentStatus", TicketOrderFlowSupport.initialPaymentStatus(purchaseType, taskOptions));
            }
            default -> {
                response.put("status", "running");
                response.put("message", "mock step processed: " + stepType);
            }
        }

        response.put("mockData", mockData);
        return response;
    }

    private String buildMockOrderNo(String platformCode) {
        return platformCode + "-mock-" + IdGeneratorUtil.nextLongId();
    }

    private void sleepForTimeout() {
        try {
            Thread.sleep(TIMEOUT_DELAY_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String resolveTenantId(Map<String, Object> payload) {
        String tenantId = getString(payload.get("tenantId"), null);
        if (StringUtils.isBlank(tenantId)) {
            tenantId = getString(payload.get("tenant_id"), null);
        }
        return StringUtils.defaultIfBlank(tenantId, DEFAULT_TENANT_ID);
    }

    private Map<String, Object> getMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, entryValue) -> result.put(String.valueOf(key), entryValue));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private String getString(Object value, String defaultValue) {
        String text = Convert.toStr(value, defaultValue);
        return StringUtils.defaultIfBlank(text, defaultValue);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }
}
