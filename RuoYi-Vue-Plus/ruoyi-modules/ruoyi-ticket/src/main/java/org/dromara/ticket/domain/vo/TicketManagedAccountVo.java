package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketManagedAccount;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketManagedAccount.class)
public class TicketManagedAccountVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long accountId;
    private String tenantId;
    private Long platformId;
    private String platformCode;
    private Long phoneId;
    private String email;
    private String accountInfo;
    private String reqData;
    private String loginReqData;
    private String accountStatus;
    private String loginStatus;
    private Date lastLoginTime;
    private String lastError;
    private String latestVerifyCode;
    private String latestActivationUrl;
    private String latestMailSubject;
    private Date latestMailReceivedAt;
    private String latestMailMessageId;
    private String platformName;
    private String phoneNumber;
    private Long mailboxId;
    private String mailboxBindingStatus;
    private Long mailboxBoundAccountId;
    private Boolean lotteryLinkOccupied;
    private Long lotteryLinkOccupiedTaskId;
    private String lotteryLinkOccupiedTaskName;
    private Date lotteryLinkOccupiedAt;
    private String lotteryLinkOccupiedStatus;
}
