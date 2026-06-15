package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class TicketDashboardOverviewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long platformTotal;
    private Long enabledPlatformCount;

    private Long taskTotal;
    private Long runningTaskCount;
    private Long abnormalTaskCount;

    private Long executionTotal;
    private Long runningExecutionCount;
    private Long successExecutionCount;
    private Long abnormalExecutionCount;

    private Long accountTotal;
    private Long loggedInAccountCount;
    private Long activatedAccountCount;
    private Long accountErrorCount;

    private Long mailboxTotal;
    private Long mailboxErrorCount;
    private Long unusedMailboxCount;

    private List<TicketSaleTaskVo> recentTasks;
    private List<TicketOrderExecutionVo> recentExecutions;
    private List<TicketRegistrationBatchVo> recentRegistrationBatches;
    private List<TicketLoginBatchVo> recentLoginBatches;
}
