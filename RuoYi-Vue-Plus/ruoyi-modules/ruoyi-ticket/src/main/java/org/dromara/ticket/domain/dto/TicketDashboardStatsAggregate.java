package org.dromara.ticket.domain.dto;

import lombok.Data;

@Data
public class TicketDashboardStatsAggregate {

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
}
