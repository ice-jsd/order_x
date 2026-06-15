package org.dromara.ticket.adapter;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPhoneNumber;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTask;
import org.dromara.ticket.domain.vo.TicketPurchaseTemplateVo;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JumpShopOnlineAdapter implements TicketPlatformAdapter {

    public static final String ADAPTER_TYPE = "jump-shop-online";
    public static final String CONFIG_SCHEMA_KEY = "jump-shop-cart-checkout-v1";

    @Override
    public String adapterType() {
        return ADAPTER_TYPE;
    }

    @Override
    public List<TicketRegisterResult> batchRegister(TicketPlatformConfig platform, List<TicketPhoneNumber> phones) {
        throw new UnsupportedOperationException("Jump Shop batch register is handled by Python executor");
    }

    @Override
    public List<TicketLoginResult> batchLogin(TicketPlatformConfig platform, List<TicketManagedAccount> accounts) {
        throw new UnsupportedOperationException("Jump Shop batch login is handled by Python executor");
    }

    @Override
    public String handleCallback(TicketPlatformConfig platform, Map<String, Object> payload) {
        return "jump shop callback accepted: " + JSONUtil.toJsonStr(payload);
    }

    @Override
    public String refreshSession(TicketPlatformConfig platform, TicketManagedAccount account) {
        return platform.getPlatformCode() + "-refresh-" + account.getAccountId();
    }

    @Override
    public Map<String, Object> queryInventory(TicketPlatformConfig platform, TicketSaleTask saleTask) {
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("platformCode", platform.getPlatformCode());
        inventory.put("taskId", saleTask.getTaskId());
        inventory.put("status", "python-managed");
        return inventory;
    }

    @Override
    public TicketPurchaseTemplateVo getPurchaseTemplate(TicketPlatformConfig platform, String purchaseType) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("productUrl", "");
        template.put("variantId", null);
        template.put("productId", null);
        template.put("sectionId", "");
        template.put("productTitle", "");
        template.put("imageUrl", "");
        template.put("available", Boolean.TRUE);
        template.put("quantity", 10);
        template.put("profileId", null);
        template.put("purchaseMode", "cart_checkout");
        template.put("paymentMode", "credit_card");
        template.put("configSchemaKey", CONFIG_SCHEMA_KEY);
        template.put("notes", "Jump Shop 由 Python 浏览器执行加购、跳转 checkout 并填写信用卡信息，命中 3DS 直接判失败");
        TicketPurchaseTemplateVo vo = TicketOrderFlowSupport.buildTemplate(
            platform,
            purchaseType,
            template,
            List.of(
                "productUrl",
                "variantId",
                "productId",
                "sectionId",
                "quantity",
                "profileId",
                "purchaseMode",
                "paymentMode",
                "notes"
            )
        );
        vo.setConfigSchemaKey(CONFIG_SCHEMA_KEY);
        return vo;
    }

    @Override
    public TicketOrderFlowDefinition buildOrderFlow(TicketPlatformConfig platform, TicketSaleTask saleTask, TicketManagedAccount account) {
        TicketOrderFlowDefinition definition = new TicketOrderFlowDefinition();
        definition.setPurchaseType(TicketOrderFlowSupport.defaultPurchaseType(saleTask.getPurchaseType()));
        definition.setConfigSchemaKey(CONFIG_SCHEMA_KEY);

        Map<String, Object> taskOptions = TicketOrderFlowSupport.parseTaskOptions(saleTask.getTaskOptions());
        Map<String, Object> baseOptions = TicketOrderFlowSupport.baseOptions(platform, saleTask, account);
        Map<String, Object> merged = new LinkedHashMap<>(baseOptions);
        merged.putAll(taskOptions);

        definition.getSteps().add(TicketOrderFlowSupport.step("JS_ADD_TO_CART", "carting", "加入购物车", merged));
        definition.getSteps().add(TicketOrderFlowSupport.step("JS_OPEN_CHECKOUT", "checking_out", "进入 Shopify Checkout", merged));
        definition.getSteps().add(TicketOrderFlowSupport.step("JS_FILL_CONTACT", "checking_out", "填写联系人与地址", merged));
        definition.getSteps().add(TicketOrderFlowSupport.step("JS_FILL_CARD", "selecting_payment", "填写信用卡信息", merged));
        definition.getSteps().add(TicketOrderFlowSupport.step("JS_SUBMIT_ORDER", "creating_order", "提交 Jump Shop 订单", merged));
        return definition;
    }

    @Override
    public TicketOrderResult executeStep(TicketPlatformConfig platform, TicketOrderFlowContext context, TicketOrderFlowStep step) {
        TicketOrderResult result = new TicketOrderResult();
        result.setSuccess(true);
        result.setCurrentStep(step.getCurrentStep());
        if ("JS_SUBMIT_ORDER".equals(step.getStepType())) {
            result.setOrderNo(platform.getPlatformCode() + "-order-" + RandomUtil.randomNumbers(8));
            result.setExecutionStatus("paid");
            result.setPaymentStatus("paid");
            result.setMessage("jump shop compatibility submit ok");
        } else {
            result.setExecutionStatus("running");
            result.setPaymentStatus(context.getPaymentStatus());
            result.setMessage("jump shop compatibility step ok: " + step.getStepType());
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
        result.setExecutionStatus("paid");
        result.setPaymentStatus("paid");
        result.setMessage("jump shop order finalized");
        return result;
    }

    @Override
    public Map<String, Object> getOrderStatus(TicketPlatformConfig platform, String orderNo) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("platformCode", platform.getPlatformCode());
        status.put("orderNo", orderNo);
        status.put("status", "paid");
        return status;
    }

    @Override
    public String normalizeError(String rawError) {
        return rawError == null ? "UNKNOWN" : rawError.toUpperCase();
    }
}
