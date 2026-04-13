package org.dromara.ticket.domain.dto;

import lombok.Data;

@Data
public class TicketRegisterProgressMessage {

    private String module = "ticket_register";
    private Long batchId;
    private Long platformId;
    private String platformName;
    private Long phoneId;
    private String phoneNumber;
    private String stepStatus;
    private String phoneStatus;
    private String note;
    private Long accountId;
    private String email;
    private String accountInfo;
    private String reqData;
    private String message;
    private Integer successCount;
    private Integer failedCount;
    private Integer skippedCount;
    private Integer processedCount;
    private Integer totalCount;
    private Integer registeredPlatformCount;
    private Integer loggedInPlatformCount;
}
