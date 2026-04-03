package org.dromara.ticket.adapter;

import lombok.Data;

@Data
public class TicketRegisterResult {

    private Long phoneId;
    private boolean success;
    private String accountNo;
    private String displayName;
    private String message;
}
