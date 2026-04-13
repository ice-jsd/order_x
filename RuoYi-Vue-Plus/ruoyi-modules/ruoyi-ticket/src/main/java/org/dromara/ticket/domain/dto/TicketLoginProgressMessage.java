package org.dromara.ticket.domain.dto;

import lombok.Data;

import java.util.Date;

@Data
public class TicketLoginProgressMessage {

    private String module = "ticket_login";
    private Long batchId;
    private Long platformId;
    private String platformName;
    private Long accountId;
    private Long phoneId;
    private String email;
    private String accountInfo;
    private String reqData;
    private String phoneNumber;
    private String stepStatus;
    private String loginStatus;
    private String lastError;
    private Date lastLoginTime;
    private String message;
    private Integer successCount;
    private Integer failedCount;
    private Integer processedCount;
    private Integer totalCount;
}
