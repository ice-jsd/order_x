package org.dromara.ticket.adapter;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPhoneNumber;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTask;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
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
            result.setAccountNo(platform.getPlatformCode() + "-" + phone.getPhoneNumber());
            result.setDisplayName(phone.getSupplier() + "-" + RandomUtil.randomStringUpper(4));
            result.setMessage("mock register ok");
            return result;
        }).toList();
    }

    @Override
    public List<TicketLoginResult> batchLogin(TicketPlatformConfig platform, List<TicketManagedAccount> accounts) {
        Date expireTime = new Date(System.currentTimeMillis() + 30L * 60L * 1000L);
        return accounts.stream().map(account -> {
            TicketLoginResult result = new TicketLoginResult();
            result.setAccountId(account.getAccountId());
            result.setSuccess(true);
            result.setSessionToken(platform.getPlatformCode() + "-session-" + RandomUtil.randomString(12));
            result.setSessionExpireTime(expireTime);
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
    public Map<String, Object> prepareOrder(TicketPlatformConfig platform, TicketSaleTask saleTask) {
        Map<String, Object> prepared = new HashMap<>();
        prepared.put("platformCode", platform.getPlatformCode());
        prepared.put("taskId", saleTask.getTaskId());
        prepared.put("prepared", true);
        return prepared;
    }

    @Override
    public TicketOrderResult submitOrder(TicketPlatformConfig platform, TicketSaleTask saleTask, TicketManagedAccount account) {
        TicketOrderResult result = new TicketOrderResult();
        result.setSuccess(true);
        result.setOrderNo(platform.getPlatformCode() + "-order-" + RandomUtil.randomNumbers(8));
        result.setMessage("mock order executed");
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
