package org.dromara.ticket.adapter;

import lombok.Data;

@Data
public class TicketOrderResult {

    private boolean success;
    private String orderNo;
    private String message;
}
