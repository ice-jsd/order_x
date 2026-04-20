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
public class LivePocketAdapter implements TicketPlatformAdapter {

    @Override
    public String adapterType() {
        return "livepocket";
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
    public TicketPurchaseTemplateVo getPurchaseTemplate(TicketPlatformConfig platform, String purchaseType) {
        if (TicketOrderFlowSupport.isLottery(purchaseType)) {
            Map<String, Object> template = new LinkedHashMap<>();
            template.put("lotteryEntryUrl", "");
            template.put("entryQuantity", 1);
            template.put("notificationPreference", "email");
            template.put("notes", "抽票链路首版仅支持配置建模，不执行真实请求");
            return TicketOrderFlowSupport.buildTemplate(
                platform,
                purchaseType,
                template,
                List.of("lotteryEntryUrl", "entryQuantity", "notificationPreference", "notes")
            );
        }

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("ticketsPageUrl", "");
        template.put("ticketQuantity", 1);
        template.put("paymentMethod", "cvs");
        template.put("sbpsWebCvsType", "016");
        template.put("followNotification", 1);
        template.put("purchaseAgreementContent", 1);
        return TicketOrderFlowSupport.buildTemplate(
            platform,
            purchaseType,
            template,
            List.of(
                "ticketsPageUrl",
                "ticketQuantity",
                "paymentMethod",
                "sbpsWebCvsType",
                "followNotification",
                "purchaseAgreementContent"
            )
        );
    }

    @Override
    public TicketOrderFlowDefinition buildOrderFlow(TicketPlatformConfig platform, TicketSaleTask saleTask, TicketManagedAccount account) {
        TicketOrderFlowDefinition definition = new TicketOrderFlowDefinition();
        definition.setPurchaseType(TicketOrderFlowSupport.defaultPurchaseType(saleTask.getPurchaseType()));
        definition.setConfigSchemaKey(saleTask.getConfigSchemaKey());

        Map<String, Object> baseOptions = TicketOrderFlowSupport.baseOptions(platform, saleTask, account);
        Map<String, Object> taskOptions = TicketOrderFlowSupport.parseTaskOptions(saleTask.getTaskOptions());

        Map<String, Object> fetchOptions = new LinkedHashMap<>(baseOptions);
        fetchOptions.putAll(taskOptions);
        fetchOptions.put("adapter", adapterType());
        definition.getSteps().add(TicketOrderFlowSupport.step("LP_FETCH_TICKETS", "checking_out", "获取票务页面", fetchOptions));

        Map<String, Object> selectSeatOptions = new LinkedHashMap<>(taskOptions);
        selectSeatOptions.put("ticketQuantity", taskOptions.getOrDefault("ticketQuantity", saleTask.getPurchaseQuantity()));
        selectSeatOptions.put("adapter", adapterType());
        definition.getSteps().add(TicketOrderFlowSupport.step("LP_SELECT_SEAT", "checking_out", "选择票种并创建预留", selectSeatOptions));

        Map<String, Object> confirmOptions = new LinkedHashMap<>(taskOptions);
        confirmOptions.put("adapter", adapterType());
        definition.getSteps().add(TicketOrderFlowSupport.step("LP_CONFIRM_PURCHASE", "checking_out", "确认购买信息", confirmOptions));

        Map<String, Object> submitOptions = new LinkedHashMap<>(taskOptions);
        submitOptions.put("paymentMethod", taskOptions.getOrDefault("paymentMethod", "cvs"));
        submitOptions.put("sbpsWebCvsType", taskOptions.getOrDefault("sbpsWebCvsType", "016"));
        submitOptions.put("followNotification", taskOptions.getOrDefault("followNotification", 1));
        submitOptions.put("purchaseAgreementContent", taskOptions.getOrDefault("purchaseAgreementContent", 1));
        submitOptions.put("adapter", adapterType());
        definition.getSteps().add(TicketOrderFlowSupport.step("LP_SUBMIT_PURCHASE", "creating_order", "提交 LivePocket 订单", submitOptions));
        return definition;
    }

    @Override
    public TicketOrderResult executeStep(TicketPlatformConfig platform, TicketOrderFlowContext context, TicketOrderFlowStep step) {
        TicketOrderResult result = new TicketOrderResult();
        result.setSuccess(true);
        result.setCurrentStep(step.getCurrentStep());
        if ("LP_SUBMIT_PURCHASE".equals(step.getStepType()) || "SUBMIT_ORDER".equals(step.getStepType())) {
            result.setOrderNo(platform.getPlatformCode() + "-order-" + RandomUtil.randomNumbers(8));
            result.setExecutionStatus("pending_payment");
            result.setPaymentStatus(TicketOrderFlowSupport.initialPaymentStatus(context.getSaleTask().getPurchaseType(), context.getTaskOptions()));
            result.setMessage("live pocket compatibility submit ok");
        } else {
            result.setExecutionStatus("running");
            result.setPaymentStatus(context.getPaymentStatus());
            result.setMessage("live pocket compatibility step ok: " + step.getStepType());
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
        result.setExecutionStatus("pending_payment");
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
