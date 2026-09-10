package org.dromara.ticket.service;

import lombok.RequiredArgsConstructor;
import org.dromara.ticket.domain.dto.TicketDashboardStatsAggregate;
import org.dromara.ticket.domain.vo.TicketDashboardOverviewVo;
import org.dromara.ticket.mapper.TicketMailboxAccountMapper;
import org.dromara.ticket.mapper.TicketManagedAccountMapper;
import org.dromara.ticket.mapper.TicketOrderExecutionMapper;
import org.dromara.ticket.mapper.TicketPlatformConfigMapper;
import org.dromara.ticket.mapper.TicketSaleTaskMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketDashboardOverviewCounterService {

    private final TicketPlatformConfigMapper platformMapper;
    private final TicketSaleTaskMapper saleTaskMapper;
    private final TicketOrderExecutionMapper orderExecutionMapper;
    private final TicketManagedAccountMapper accountMapper;
    private final TicketMailboxAccountMapper mailboxAccountMapper;

    public TicketDashboardOverviewVo populateOverview(TicketDashboardOverviewVo overview) {
        TicketDashboardStatsAggregate platformStats = platformMapper.selectDashboardStats();
        TicketDashboardStatsAggregate taskStats = saleTaskMapper.selectDashboardStats();
        TicketDashboardStatsAggregate executionStats = orderExecutionMapper.selectDashboardStats();
        TicketDashboardStatsAggregate accountStats = accountMapper.selectDashboardStats();
        TicketDashboardStatsAggregate mailboxStats = mailboxAccountMapper.selectDashboardStats();

        overview.setPlatformTotal(valueOf(platformStats == null ? null : platformStats.getPlatformTotal()));
        overview.setEnabledPlatformCount(valueOf(platformStats == null ? null : platformStats.getEnabledPlatformCount()));

        overview.setTaskTotal(valueOf(taskStats == null ? null : taskStats.getTaskTotal()));
        overview.setRunningTaskCount(valueOf(taskStats == null ? null : taskStats.getRunningTaskCount()));
        overview.setAbnormalTaskCount(valueOf(taskStats == null ? null : taskStats.getAbnormalTaskCount()));

        overview.setExecutionTotal(valueOf(executionStats == null ? null : executionStats.getExecutionTotal()));
        overview.setRunningExecutionCount(valueOf(executionStats == null ? null : executionStats.getRunningExecutionCount()));
        overview.setSuccessExecutionCount(valueOf(executionStats == null ? null : executionStats.getSuccessExecutionCount()));
        overview.setAbnormalExecutionCount(valueOf(executionStats == null ? null : executionStats.getAbnormalExecutionCount()));

        overview.setAccountTotal(valueOf(accountStats == null ? null : accountStats.getAccountTotal()));
        overview.setLoggedInAccountCount(valueOf(accountStats == null ? null : accountStats.getLoggedInAccountCount()));
        overview.setActivatedAccountCount(valueOf(accountStats == null ? null : accountStats.getActivatedAccountCount()));
        overview.setAccountErrorCount(valueOf(accountStats == null ? null : accountStats.getAccountErrorCount()));

        overview.setMailboxTotal(valueOf(mailboxStats == null ? null : mailboxStats.getMailboxTotal()));
        overview.setMailboxErrorCount(valueOf(mailboxStats == null ? null : mailboxStats.getMailboxErrorCount()));
        overview.setUnusedMailboxCount(valueOf(mailboxStats == null ? null : mailboxStats.getUnusedMailboxCount()));
        return overview;
    }

    private long valueOf(Long value) {
        return value == null ? 0L : value;
    }
}
