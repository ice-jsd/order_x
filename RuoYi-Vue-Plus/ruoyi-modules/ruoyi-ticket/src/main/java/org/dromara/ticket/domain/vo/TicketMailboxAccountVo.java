package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketMailboxAccount;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketMailboxAccount.class)
public class TicketMailboxAccountVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long mailboxId;
    private String tenantId;
    private String email;
    private String username;
    private String password;
    private String domain;
    private String provider;
    private String stalwartPrincipalId;
    private String status;
    private Long usedAccountId;
    private Date usedTime;
    private String lastError;
    private String latestMailSubject;
    private String latestMailFrom;
    private Date latestMailReceivedAt;
    private String latestMailMessageId;
    private String latestMailExcerpt;
    private String latestVerifyCode;
    private String latestActivationUrl;
    private Date lastMailSyncTime;
    private String lastMailSyncError;
    private Date createTime;
    private String usedAccountEmail;
}
