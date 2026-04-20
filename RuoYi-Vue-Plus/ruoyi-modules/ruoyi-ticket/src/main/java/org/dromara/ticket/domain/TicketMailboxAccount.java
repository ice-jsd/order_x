package org.dromara.ticket.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_mailbox_account")
public class TicketMailboxAccount extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "mailbox_id")
    private Long mailboxId;

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

    @TableLogic
    private Long delFlag;
}
