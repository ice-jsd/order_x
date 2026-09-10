package org.dromara.ticket.service;

import org.dromara.ticket.domain.TicketOrderExecution;
import org.dromara.ticket.mapper.TicketOrderExecutionMapper;
import org.dromara.ticket.mapper.TicketSaleTaskMapper;
import org.dromara.ticket.service.impl.TicketLotteryLinkOccupancyService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
public class TicketLotteryLinkOccupancyServiceTest {

    @Mock
    private TicketOrderExecutionMapper orderExecutionMapper;
    @Mock
    private TicketSaleTaskMapper saleTaskMapper;

    @InjectMocks
    private TicketLotteryLinkOccupancyService service;

    @Test
    public void querySkipsFallbackWhenDirectMatchesAllAccounts() {
        when(saleTaskMapper.selectByIds(any())).thenReturn(List.of());
        when(orderExecutionMapper.selectLotteryOccupanciesByEvent(eq(List.of(1L, 2L)), eq(List.of(10L)), eq("https://livepocket.jp/e/foo"), eq(null)))
            .thenReturn(List.of(
                execution(100L, 1L, "submitted", "{\"lotteryEventUrl\":\"https://livepocket.jp/e/foo\"}"),
                execution(101L, 2L, "paid", "{\"lotteryEventUrl\":\"https://livepocket.jp/e/foo\"}")
            ));

        Map<Long, TicketLotteryLinkOccupancyService.OccupancyInfo> result = service.query(
            List.of(1L, 2L),
            List.of(10L),
            "https://livepocket.jp/e/foo",
            null
        );

        assertEquals(2, result.size());
        assertTrue(result.containsKey(1L));
        assertTrue(result.containsKey(2L));
        verify(orderExecutionMapper, never()).selectLotteryOccupanciesWithBlankEventUrl(any(), any(), any());
        verify(orderExecutionMapper, never()).selectLotteryOccupanciesWithNullEventUrl(any(), any(), any());
    }

    @Test
    public void queryFallsBackToBlankAndNullEventUrlResults() {
        when(saleTaskMapper.selectByIds(any())).thenReturn(List.of());
        when(orderExecutionMapper.selectLotteryOccupanciesByEvent(eq(List.of(1L, 2L, 3L)), eq(List.of(10L)), eq("https://livepocket.jp/e/foo"), eq(null)))
            .thenReturn(List.of(execution(100L, 1L, "submitted", "{\"lotteryEventUrl\":\"https://livepocket.jp/e/foo\"}")));
        when(orderExecutionMapper.selectLotteryOccupanciesWithBlankEventUrl(eq(List.of(2L, 3L)), eq(List.of(10L)), eq(null)))
            .thenReturn(List.of(execution(200L, 2L, "completed", "{\"lotteryEventUrl\":\"https://livepocket.jp/e/foo\"}")));
        when(orderExecutionMapper.selectLotteryOccupanciesWithNullEventUrl(eq(List.of(2L, 3L)), eq(List.of(10L)), eq(null)))
            .thenReturn(List.of(execution(300L, 3L, "paid", "{\"lotteryEventUrl\":\"https://livepocket.jp/e/foo\"}")));

        Map<Long, TicketLotteryLinkOccupancyService.OccupancyInfo> result = service.query(
            List.of(1L, 2L, 3L),
            List.of(10L),
            "https://livepocket.jp/e/foo",
            null
        );

        assertEquals(3, result.size());
        assertTrue(result.containsKey(1L));
        assertTrue(result.containsKey(2L));
        assertTrue(result.containsKey(3L));
        verify(orderExecutionMapper).selectLotteryOccupanciesWithBlankEventUrl(eq(List.of(2L, 3L)), eq(List.of(10L)), eq(null));
        verify(orderExecutionMapper).selectLotteryOccupanciesWithNullEventUrl(eq(List.of(2L, 3L)), eq(List.of(10L)), eq(null));
    }

    private static TicketOrderExecution execution(Long executionId, Long accountId, String status, String configSnapshot) {
        TicketOrderExecution execution = new TicketOrderExecution();
        execution.setExecutionId(executionId);
        execution.setTaskId(executionId);
        execution.setAccountId(accountId);
        execution.setExecutionStatus(status);
        execution.setConfigSnapshot(configSnapshot);
        execution.setExecutedAt(new Date(executionId));
        return execution;
    }
}
