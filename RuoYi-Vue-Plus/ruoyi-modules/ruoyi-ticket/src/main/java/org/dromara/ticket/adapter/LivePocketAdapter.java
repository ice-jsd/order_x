package org.dromara.ticket.adapter;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPhoneNumber;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTask;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LivePocketAdapter implements TicketPlatformAdapter {

    @Override
    public String adapterType() {
        return "live_pocket";
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
            accountInfo.put("source", "live-pocket-register");
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
            accountInfo.put("source", "live-pocket-login");
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
    public TicketOrderFlowDefinition buildOrderFlow(TicketPlatformConfig platform, TicketSaleTask saleTask, TicketManagedAccount account) {
        TicketOrderFlowDefinition definition = TicketOrderFlowSupport.buildDefaultFlow(platform, saleTask, account);
        definition.getSteps().forEach(step -> step.getOptions().put("adapter", adapterType()));
        return definition;
    }

    @Override
    public TicketOrderResult executeStep(TicketPlatformConfig platform, TicketOrderFlowContext context, TicketOrderFlowStep step) {
        TicketOrderResult result = new TicketOrderResult();
        result.setSuccess(true);
        result.setCurrentStep(step.getCurrentStep());
        if ("SUBMIT_ORDER".equals(step.getStepType())) {
            result.setOrderNo(platform.getPlatformCode() + "-order-" + RandomUtil.randomNumbers(8));
            result.setExecutionStatus("submitted");
            result.setPaymentStatus(TicketOrderFlowSupport.initialPaymentStatus(context.getSaleTask().getPaymentMode()));
            result.setMessage("live pocket mock submit ok");
        } else if ("CREATE_ONLINE_PAYMENT".equals(step.getStepType())) {
            result.setExecutionStatus("pending_payment");
            result.setPaymentStatus("pending_online");
            result.setMessage("live pocket mock payment created");
        } else if ("CONFIRM_PENDING_PAYMENT".equals(step.getStepType())) {
            result.setExecutionStatus("pending_payment");
            result.setPaymentStatus("manual_pending");
            result.setMessage("live pocket pending payment");
        } else {
            result.setExecutionStatus("running");
            result.setPaymentStatus(context.getPaymentStatus());
            result.setMessage("live pocket mock step ok: " + step.getStepType());
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
        result.setExecutionStatus("online".equals(context.getSaleTask().getPaymentMode()) ? "pending_payment" : "submitted");
        result.setPaymentStatus(context.getPaymentStatus());
        result.setMessage("live pocket order finalized");
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
}
