package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
public class TicketExternalVerifyCodeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String verifyCode;
    private String activationUrl;
    private String subject;
    private Date receivedAt;
}
