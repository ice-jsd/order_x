package org.dromara.ticket.adapter;

import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPhoneNumber;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTask;

import java.util.List;
import java.util.Map;

public interface TicketPlatformAdapter {

    String adapterType();

    List<TicketRegisterResult> batchRegister(TicketPlatformConfig platform, List<TicketPhoneNumber> phones);

    List<TicketLoginResult> batchLogin(TicketPlatformConfig platform, List<TicketManagedAccount> accounts);

    String handleCallback(TicketPlatformConfig platform, Map<String, Object> payload);

    String refreshSession(TicketPlatformConfig platform, TicketManagedAccount account);

    Map<String, Object> queryInventory(TicketPlatformConfig platform, TicketSaleTask saleTask);

    Map<String, Object> prepareOrder(TicketPlatformConfig platform, TicketSaleTask saleTask);

    TicketOrderResult submitOrder(TicketPlatformConfig platform, TicketSaleTask saleTask, TicketManagedAccount account);

    Map<String, Object> getOrderStatus(TicketPlatformConfig platform, String orderNo);

    String normalizeError(String rawError);
}
