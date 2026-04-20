package org.dromara.ticket.adapter;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPhoneNumber;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTask;
import org.dromara.ticket.domain.vo.TicketPurchaseTemplateVo;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MockTicketPlatformAdapter implements TicketPlatformAdapter {

    @Override
    public String adapterType() {
        return "mock";
    }

    @Override
    public List<TicketRegisterResult> batchRegister(TicketPlatformConfig platform, List<TicketPhoneNumber> phones) {
        return phones.stream().map(phone -> {
            TicketRegisterResult result = new TicketRegisterResult();
            result.setPhoneId(phone.getPhoneId());
            result.setSuccess(true);
            String email = phone.getPhoneNumber() + "@" + platform.getPlatformCode() + ".test";
            Map<String, Object> accountInfo = new LinkedHashMap<>();
            accountInfo.put("nickname", phone.getSupplier() + "-" + RandomUtil.randomStringUpper(4));
            accountInfo.put("source", "mock-register");
            Map<String, Object> reqData = new LinkedHashMap<>();
            reqData.put("platformCode", platform.getPlatformCode());
            reqData.put("phoneNumber", phone.getPhoneNumber());
            reqData.put("channel", "email");
            result.setEmail(email);
            result.setAccountInfo(JSONUtil.toJsonStr(accountInfo));
            result.setReqData(JSONUtil.toJsonStr(reqData));
            result.setMessage("mock register ok");
            return result;
        }).toList();
    }

    @Override
    public List<TicketLoginResult> batchLogin(TicketPlatformConfig platform, List<TicketManagedAccount> accounts) {
        return accounts.stream().map(account -> {
            TicketLoginResult result = new TicketLoginResult();
            result.setAccountId(account.getAccountId());
            result.setSuccess(true);
            Map<String, Object> accountInfo = new LinkedHashMap<>();
            accountInfo.put("email", account.getEmail());
            accountInfo.put("profileStatus", "active");
            accountInfo.put("source", "mock-login");
            Map<String, Object> reqData = new LinkedHashMap<>();
            reqData.put("sessionId", platform.getPlatformCode() + "-session-" + RandomUtil.randomString(12));
            reqData.put("loginAt", System.currentTimeMillis());
            reqData.put("ip", "127.0.0.1");
            result.setAccountInfo(JSONUtil.toJsonStr(accountInfo));
            result.setReqData(JSONUtil.toJsonStr(reqData));
            result.setMessage("mock login ok");
            return result;
        }).toList();
    }

    @Override
    public String handleCallback(TicketPlatformConfig platform, Map<String, Object> payload) {
        return "mock callback accepted: " + JSONUtil.toJsonStr(payload);
    }

    @Override
    public String refreshSession(TicketPlatformConfig platform, TicketManagedAccount account) {
        return platform.getPlatformCode() + "-refresh-" + account.getAccountId();
    }

    @Override
    public Map<String, Object> queryInventory(TicketPlatformConfig platform, TicketSaleTask saleTask) {
        Map<String, Object> inventory = new HashMap<>();
        inventory.put("platformCode", platform.getPlatformCode());
        inventory.put("taskId", saleTask.getTaskId());
        inventory.put("inventory", 12);
        return inventory;
    }

    @Override
    public TicketPurchaseTemplateVo getPurchaseTemplate(TicketPlatformConfig platform, String purchaseType) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("purchaseMode", TicketOrderFlowSupport.isLottery(purchaseType) ? "lottery-entry" : "mock-order");
        template.put("paymentMode", "pending_manual");
        template.put("deliveryOption", "standard");
        template.put("pickupStoreCode", "store-shibuya");
        template.put("mockBehavior", "");
        return TicketOrderFlowSupport.buildTemplate(
            platform,
            purchaseType,
            template,
            List.of("purchaseMode", "paymentMode", "deliveryOption", "pickupStoreCode", "mockBehavior")
        );
    }

    @Override
    public TicketOrderFlowDefinition buildOrderFlow(TicketPlatformConfig platform, TicketSaleTask saleTask, TicketManagedAccount account) {
        TicketOrderFlowDefinition definition = new TicketOrderFlowDefinition();
        definition.setPurchaseType(TicketOrderFlowSupport.defaultPurchaseType(saleTask.getPurchaseType()));
        definition.setConfigSchemaKey(saleTask.getConfigSchemaKey());

        Map<String, Object> taskOptions = TicketOrderFlowSupport.parseTaskOptions(saleTask.getTaskOptions());
        Map<String, Object> baseOptions = TicketOrderFlowSupport.baseOptions(platform, saleTask, account);
        String paymentMode = TicketOrderFlowSupport.readString(taskOptions, "paymentMode");
        String purchaseMode = TicketOrderFlowSupport.readString(taskOptions, "purchaseMode");

        if ("lottery-entry".equals(purchaseMode)) {
            definition.getSteps().add(TicketOrderFlowSupport.step("LOTTERY_REGISTER", "checking_out", "登记抽选", merge(baseOptions, taskOptions)));
            return definition;
        }

        if ("cart_checkout".equals(purchaseMode)) {
            definition.getSteps().add(TicketOrderFlowSupport.step("ADD_TO_CART", "carting", "加入购物车", merge(baseOptions, taskOptions)));
        } else {
            definition.getSteps().add(TicketOrderFlowSupport.step("DIRECT_BUY", "checking_out", "直接下单", merge(baseOptions, taskOptions)));
        }

        if ("pickup_store".equals(TicketOrderFlowSupport.readString(taskOptions, "fulfillmentMode"))) {
            definition.getSteps().add(TicketOrderFlowSupport.step("SELECT_PICKUP_STORE", "selecting_fulfillment", "选择自提门店", taskOptions));
        } else {
            definition.getSteps().add(TicketOrderFlowSupport.step("SELECT_DELIVERY", "selecting_fulfillment", "选择配送方式", taskOptions));
        }

        definition.getSteps().add(TicketOrderFlowSupport.step("SELECT_PAYMENT_MODE", "selecting_payment", "选择支付方式", taskOptions));
        definition.getSteps().add(TicketOrderFlowSupport.step("SUBMIT_ORDER", "creating_order", "提交订单", merge(baseOptions, taskOptions)));
        if ("online".equals(paymentMode)) {
            definition.getSteps().add(TicketOrderFlowSupport.step("CREATE_ONLINE_PAYMENT", "awaiting_payment", "创建线上支付", taskOptions));
        } else if (!"cod_store".equals(paymentMode)) {
            definition.getSteps().add(TicketOrderFlowSupport.step("CONFIRM_PENDING_PAYMENT", "awaiting_payment", "等待人工支付", taskOptions));
        }
        return definition;
    }

    @Override
    public TicketOrderResult executeStep(TicketPlatformConfig platform, TicketOrderFlowContext context, TicketOrderFlowStep step) {
        TicketOrderResult result = new TicketOrderResult();
        result.setSuccess(true);
        result.setCurrentStep(step.getCurrentStep());
        String paymentMode = TicketOrderFlowSupport.readString(context.getTaskOptions(), "paymentMode");
        if ("SUBMIT_ORDER".equals(step.getStepType())) {
            result.setOrderNo(platform.getPlatformCode() + "-order-" + RandomUtil.randomNumbers(8));
            result.setExecutionStatus("submitted");
            result.setPaymentStatus(TicketOrderFlowSupport.initialPaymentStatus(context.getSaleTask().getPurchaseType(), context.getTaskOptions()));
            result.setMessage("mock submit order ok");
        } else if ("CREATE_ONLINE_PAYMENT".equals(step.getStepType())) {
            result.setExecutionStatus("pending_payment");
            result.setPaymentStatus("pending_online");
            result.setMessage("mock create online payment ok");
        } else if ("CONFIRM_PENDING_PAYMENT".equals(step.getStepType()) || "LOTTERY_REGISTER".equals(step.getStepType())) {
            result.setExecutionStatus("pending_payment");
            result.setPaymentStatus(TicketOrderFlowSupport.initialPaymentStatus(context.getSaleTask().getPurchaseType(), context.getTaskOptions()));
            result.setMessage("mock pending payment");
        } else {
            result.setExecutionStatus("running");
            result.setPaymentStatus("online".equals(paymentMode) ? "pending_online" : context.getPaymentStatus());
            result.setMessage("mock step ok: " + step.getStepType());
        }
        result.setStepTrace(JSONUtil.toJsonStr(step.getOptions()));
        return result;
    }

    @Override
    public TicketOrderResult finalizeOrder(TicketPlatformConfig platform, TicketOrderFlowContext context) {
        TicketOrderResult result = new TicketOrderResult();
        result.setSuccess(true);
        result.setOrderNo(context.getOrderNo());
        result.setCurrentStep("completed");
        String paymentMode = TicketOrderFlowSupport.readString(context.getTaskOptions(), "paymentMode");
        result.setPaymentStatus(TicketOrderFlowSupport.initialPaymentStatus(context.getSaleTask().getPurchaseType(), context.getTaskOptions()));
        result.setExecutionStatus("online".equals(paymentMode) ? "pending_payment" : "submitted");
        result.setMessage("mock order finalized");
        return result;
    }

    @Override
    public Map<String, Object> getOrderStatus(TicketPlatformConfig platform, String orderNo) {
        Map<String, Object> status = new HashMap<>();
        status.put("platformCode", platform.getPlatformCode());
        status.put("orderNo", orderNo);
        status.put("status", "submitted");
        return status;
    }

    @Override
    public String normalizeError(String rawError) {
        return rawError == null ? "UNKNOWN" : rawError.toUpperCase();
    }

    private Map<String, Object> merge(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> merged = new LinkedHashMap<>(left);
        merged.putAll(right);
        return merged;
    }
}
