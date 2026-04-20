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
@TableName("ticket_managed_account")
public class TicketManagedAccount extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "account_id")
    private Long accountId;

    private Long platformId;
    private Long phoneId;
    private String email;
    private String accountInfo;
    private String reqData;
    private String accountStatus;
    private String loginStatus;
    private Date lastLoginTime;
    private String lastError;
    private String latestVerifyCode;
    private String latestActivationUrl;
    private String latestMailSubject;
    private Date latestMailReceivedAt;
    private String latestMailMessageId;

    @TableLogic
    private Long delFlag;
}
