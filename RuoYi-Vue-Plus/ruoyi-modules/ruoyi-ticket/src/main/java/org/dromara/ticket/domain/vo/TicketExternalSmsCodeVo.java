package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
public class TicketExternalSmsCodeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String verifyCode;
    private String smsText;
    private String receivedAt;
    private String phoneNumber;
    private Date phoneLeaseExpireTime;
    private Integer phoneCountdownSeconds;
    private Boolean phoneRenewed;
}
