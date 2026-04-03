package org.dromara.ticket.adapter;

import lombok.Data;

import java.util.Date;

@Data
public class TicketLoginResult {

    private Long accountId;
    private boolean success;
    private String sessionToken;
    private Date sessionExpireTime;
    private String message;
}
