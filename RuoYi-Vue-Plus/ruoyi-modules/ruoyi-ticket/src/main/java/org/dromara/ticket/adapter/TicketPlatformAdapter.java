package org.dromara.ticket.adapter;

import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPhoneNumber;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTask;
import org.dromara.ticket.domain.vo.TicketPurchaseTemplateVo;

import java.util.List;
import java.util.Map;

public interface TicketPlatformAdapter {

    String adapterType();

    List<TicketRegisterResult> batchRegister(TicketPlatformConfig platform, List<TicketPhoneNumber> phones);

    List<TicketLoginResult> batchLogin(TicketPlatformConfig platform, List<TicketManagedAccount> accounts);

    String handleCallback(TicketPlatformConfig platform, Map<String, Object> payload);

    String refreshSession(TicketPlatformConfig platform, TicketManagedAccount account);

    Map<String, Object> queryInventory(TicketPlatformConfig platform, TicketSaleTask saleTask);

    TicketPurchaseTemplateVo getPurchaseTemplate(TicketPlatformConfig platform, String purchaseType);

    TicketOrderFlowDefinition buildOrderFlow(TicketPlatformConfig platform, TicketSaleTask saleTask, TicketManagedAccount account);

    TicketOrderResult executeStep(TicketPlatformConfig platform, TicketOrderFlowContext context, TicketOrderFlowStep step);

    TicketOrderResult finalizeOrder(TicketPlatformConfig platform, TicketOrderFlowContext context);

    Map<String, Object> getOrderStatus(TicketPlatformConfig platform, String orderNo);

    String normalizeError(String rawError);
}
