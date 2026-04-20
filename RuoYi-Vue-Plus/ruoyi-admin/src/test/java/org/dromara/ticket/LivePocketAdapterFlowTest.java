package org.dromara.ticket;

import org.dromara.ticket.adapter.LivePocketAdapter;
import org.dromara.ticket.adapter.TicketOrderFlowDefinition;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LivePocketAdapterFlowTest {

    private final LivePocketAdapter adapter = new LivePocketAdapter();

    @Test
    void buildOrderFlow_shouldReturnLivePocketFourSteps() {
        TicketPlatformConfig platform = new TicketPlatformConfig();
        platform.setPlatformCode("livepocket");

        TicketManagedAccount account = new TicketManagedAccount();
        account.setEmail("tester@example.com");

        TicketSaleTask task = new TicketSaleTask();
        task.setPurchaseType("flash_sale");
        task.setConfigSchemaKey("livepocket:flash_sale");
        task.setPurchaseQuantity(2);
        task.setTaskOptions("{\"ticketsPageUrl\":\"https://livepocket.jp/e/demo/receptions/1/tickets\"}");

        TicketOrderFlowDefinition definition = adapter.buildOrderFlow(platform, task, account);

        assertEquals("flash_sale", definition.getPurchaseType());
        assertEquals(4, definition.getSteps().size());
        assertEquals("LP_FETCH_TICKETS", definition.getSteps().get(0).getStepType());
        assertEquals("LP_SELECT_SEAT", definition.getSteps().get(1).getStepType());
        assertEquals("LP_CONFIRM_PURCHASE", definition.getSteps().get(2).getStepType());
        assertEquals("LP_SUBMIT_PURCHASE", definition.getSteps().get(3).getStepType());
        assertEquals("cvs", definition.getSteps().get(3).getOptions().get("paymentMethod"));
        assertEquals("016", definition.getSteps().get(3).getOptions().get("sbpsWebCvsType"));
    }
}
