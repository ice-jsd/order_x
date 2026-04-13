package org.dromara.ticket.adapter;

import lombok.Data;

@Data
public class TicketRegisterResult {

    private Long phoneId;
    private boolean success;
    private String email;
    private String accountInfo;
    private String reqData;
    private String message;
}
