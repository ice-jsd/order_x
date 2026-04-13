package org.dromara.ticket.adapter;

import lombok.Data;

@Data
public class TicketLoginResult {

    private Long accountId;
    private boolean success;
    private String accountInfo;
    private String reqData;
    private String message;
}
