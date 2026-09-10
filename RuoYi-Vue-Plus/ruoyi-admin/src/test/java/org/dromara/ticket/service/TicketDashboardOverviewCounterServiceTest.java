package org.dromara.ticket.service;

import org.dromara.ticket.domain.dto.TicketDashboardStatsAggregate;
import org.dromara.ticket.domain.vo.TicketDashboardOverviewVo;
import org.dromara.ticket.mapper.TicketMailboxAccountMapper;
import org.dromara.ticket.mapper.TicketManagedAccountMapper;
import org.dromara.ticket.mapper.TicketOrderExecutionMapper;
import org.dromara.ticket.mapper.TicketPlatformConfigMapper;
import org.dromara.ticket.mapper.TicketSaleTaskMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
public class TicketDashboardOverviewCounterServiceTest {

    @Mock
    private TicketPlatformConfigMapper platformMapper;
    @Mock
    private TicketSaleTaskMapper saleTaskMapper;
    @Mock
    private TicketOrderExecutionMapper orderExecutionMapper;
    @Mock
    private TicketManagedAccountMapper accountMapper;
    @Mock
    private TicketMailboxAccountMapper mailboxAccountMapper;

    @InjectMocks
    private TicketDashboardOverviewCounterService service;

    @Test
    public void populateOverviewFillsCountsFromAggregates() {
        when(platformMapper.selectDashboardStats()).thenReturn(stats(3L, 2L, null, null));
        when(saleTaskMapper.selectDashboardStats()).thenReturn(stats(10L, 4L, 1L, null));
        when(orderExecutionMapper.selectDashboardStats()).thenReturn(executionStats(20L, 5L, 7L, 3L));
        when(accountMapper.selectDashboardStats()).thenReturn(accountStats(30L, 6L, 8L, 2L));
        when(mailboxAccountMapper.selectDashboardStats()).thenReturn(mailboxStats(40L, 9L, 11L));

        TicketDashboardOverviewVo overview = service.populateOverview(new TicketDashboardOverviewVo());

        assertEquals(3L, overview.getPlatformTotal());
        assertEquals(2L, overview.getEnabledPlatformCount());
        assertEquals(10L, overview.getTaskTotal());
        assertEquals(4L, overview.getRunningTaskCount());
        assertEquals(1L, overview.getAbnormalTaskCount());
        assertEquals(20L, overview.getExecutionTotal());
        assertEquals(5L, overview.getRunningExecutionCount());
        assertEquals(7L, overview.getSuccessExecutionCount());
        assertEquals(3L, overview.getAbnormalExecutionCount());
        assertEquals(30L, overview.getAccountTotal());
        assertEquals(6L, overview.getLoggedInAccountCount());
        assertEquals(8L, overview.getActivatedAccountCount());
        assertEquals(2L, overview.getAccountErrorCount());
        assertEquals(40L, overview.getMailboxTotal());
        assertEquals(9L, overview.getMailboxErrorCount());
        assertEquals(11L, overview.getUnusedMailboxCount());
    }

    @Test
    public void populateOverviewDefaultsMissingStatsToZero() {
        when(platformMapper.selectDashboardStats()).thenReturn(null);
        when(saleTaskMapper.selectDashboardStats()).thenReturn(new TicketDashboardStatsAggregate());
        when(orderExecutionMapper.selectDashboardStats()).thenReturn(new TicketDashboardStatsAggregate());
        when(accountMapper.selectDashboardStats()).thenReturn(new TicketDashboardStatsAggregate());
        when(mailboxAccountMapper.selectDashboardStats()).thenReturn(new TicketDashboardStatsAggregate());

        TicketDashboardOverviewVo overview = service.populateOverview(new TicketDashboardOverviewVo());

        assertEquals(0L, overview.getPlatformTotal());
        assertEquals(0L, overview.getEnabledPlatformCount());
        assertEquals(0L, overview.getTaskTotal());
        assertEquals(0L, overview.getRunningTaskCount());
        assertEquals(0L, overview.getAbnormalTaskCount());
        assertEquals(0L, overview.getExecutionTotal());
        assertEquals(0L, overview.getRunningExecutionCount());
        assertEquals(0L, overview.getSuccessExecutionCount());
        assertEquals(0L, overview.getAbnormalExecutionCount());
        assertEquals(0L, overview.getAccountTotal());
        assertEquals(0L, overview.getLoggedInAccountCount());
        assertEquals(0L, overview.getActivatedAccountCount());
        assertEquals(0L, overview.getAccountErrorCount());
        assertEquals(0L, overview.getMailboxTotal());
        assertEquals(0L, overview.getMailboxErrorCount());
        assertEquals(0L, overview.getUnusedMailboxCount());
    }

    private static TicketDashboardStatsAggregate stats(Long total, Long first, Long second, Long third) {
        TicketDashboardStatsAggregate aggregate = new TicketDashboardStatsAggregate();
        aggregate.setPlatformTotal(total);
        aggregate.setEnabledPlatformCount(first);
        aggregate.setTaskTotal(total);
        aggregate.setRunningTaskCount(first);
        aggregate.setAbnormalTaskCount(second);
        aggregate.setMailboxTotal(total);
        aggregate.setMailboxErrorCount(first);
        aggregate.setUnusedMailboxCount(second);
        aggregate.setAccountTotal(total);
        aggregate.setLoggedInAccountCount(first);
        aggregate.setActivatedAccountCount(second);
        aggregate.setAccountErrorCount(third);
        return aggregate;
    }

    private static TicketDashboardStatsAggregate executionStats(Long total, Long running, Long success, Long abnormal) {
        TicketDashboardStatsAggregate aggregate = new TicketDashboardStatsAggregate();
        aggregate.setExecutionTotal(total);
        aggregate.setRunningExecutionCount(running);
        aggregate.setSuccessExecutionCount(success);
        aggregate.setAbnormalExecutionCount(abnormal);
        return aggregate;
    }

    private static TicketDashboardStatsAggregate accountStats(Long total, Long loggedIn, Long activated, Long errorCount) {
        TicketDashboardStatsAggregate aggregate = new TicketDashboardStatsAggregate();
        aggregate.setAccountTotal(total);
        aggregate.setLoggedInAccountCount(loggedIn);
        aggregate.setActivatedAccountCount(activated);
        aggregate.setAccountErrorCount(errorCount);
        return aggregate;
    }

    private static TicketDashboardStatsAggregate mailboxStats(Long total, Long errorCount, Long unused) {
        TicketDashboardStatsAggregate aggregate = new TicketDashboardStatsAggregate();
        aggregate.setMailboxTotal(total);
        aggregate.setMailboxErrorCount(errorCount);
        aggregate.setUnusedMailboxCount(unused);
        return aggregate;
    }
}
