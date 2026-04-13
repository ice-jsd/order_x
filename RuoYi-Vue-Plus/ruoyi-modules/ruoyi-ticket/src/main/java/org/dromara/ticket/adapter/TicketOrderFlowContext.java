package org.dromara.ticket.adapter;

import lombok.Data;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTask;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class TicketOrderFlowContext implements Serializable {

    private TicketPlatformConfig platform;
    private TicketSaleTask saleTask;
    private TicketManagedAccount account;
    private Map<String, Object> taskOptions = new LinkedHashMap<>();
    private Map<String, Object> runtimeState = new LinkedHashMap<>();
    private List<Map<String, Object>> stepTrace = new ArrayList<>();
    private String currentStep;
    private String executionStatus;
    private String paymentStatus;
    private String orderNo;
    private String message;
}
