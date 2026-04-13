package org.dromara.ticket.domain.dto;

import lombok.Data;
import org.dromara.ticket.adapter.TicketOrderFlowStep;

import java.util.Date;
import java.util.List;

@Data
public class TicketOrderDispatchRequest {

    private Long executionId;
    private Long taskId;
    private Long platformId;
    private String platformCode;
    private String platformName;
    private String adapterType;
    private String orderSubmitUrl;
    private Long accountId;
    private String email;
    private String accountInfo;
    private String reqData;
    private String productId;
    private Integer purchaseQuantity;
    private Long scheduleVersion;
    private String orderFlowType;
    private String fulfillmentType;
    private String paymentMode;
    private String taskOptions;
    private List<TicketOrderFlowStep> flowSteps;
    private Date scheduledTime;
    private Date warmupTime;
}
