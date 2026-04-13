package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketOrderExecution;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketOrderExecution.class)
public class TicketOrderExecutionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long executionId;
    private String tenantId;
    private Long taskId;
    private Long platformId;
    private Long accountId;
    private String productId;
    private Integer purchaseQuantity;
    private String flowType;
    private String fulfillmentType;
    private String paymentMode;
    private String currentStep;
    private String stepStatus;
    private String stepTrace;
    private String paymentStatus;
    private String orderNo;
    private String executionStatus;
    private String resultMessage;
    private String rawResult;
    private String workerId;
    private Integer attemptCount;
    private Date heartbeatAt;
    private Date startedAt;
    private Date executedAt;
    private String platformName;
    private String email;
    private String accountInfo;
    private String reqData;
    private String taskName;
}
