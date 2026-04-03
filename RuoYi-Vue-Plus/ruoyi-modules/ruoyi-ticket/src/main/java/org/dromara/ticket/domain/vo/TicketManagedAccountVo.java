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
    private Long phoneId;
    private String accountNo;
    private String displayName;
    private String accountStatus;
    private String loginStatus;
    private Date sessionExpireTime;
    private Date lastLoginTime;
    private String lastError;
    private String platformName;
    private String phoneNumber;
}
