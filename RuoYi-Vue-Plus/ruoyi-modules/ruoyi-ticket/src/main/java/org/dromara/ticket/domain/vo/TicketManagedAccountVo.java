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
    private String email;
    private String accountInfo;
    private String reqData;
    private String accountStatus;
    private String loginStatus;
    private Date lastLoginTime;
    private String lastError;
    private String platformName;
    private String phoneNumber;
}
