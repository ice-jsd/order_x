package org.dromara.ticket.domain.vo;

import lombok.Data;

@Data
public class TicketExternalManualLoginCodeVo {

    private String requestId;
    private String status;
    private String verifyCode;
    private String message;
}
