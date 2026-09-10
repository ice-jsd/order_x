package org.dromara.ticket.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.ticket.domain.TicketOrderExecution;
import org.dromara.ticket.domain.dto.TicketDashboardStatsAggregate;
import org.dromara.ticket.domain.vo.TicketOrderExecutionVo;

import java.util.Collection;
import java.util.List;

public interface TicketOrderExecutionMapper extends BaseMapperPlus<TicketOrderExecution, TicketOrderExecutionVo> {

    @Select("""
        SELECT COUNT(*) AS executionTotal,
               SUM(CASE WHEN execution_status IN ('running', 'queued') THEN 1 ELSE 0 END) AS runningExecutionCount,
               SUM(CASE WHEN execution_status IN ('submitted', 'paid', 'completed') THEN 1 ELSE 0 END) AS successExecutionCount,
               SUM(CASE WHEN execution_status IN ('failed', 'timeout', 'blocked') THEN 1 ELSE 0 END) AS abnormalExecutionCount
        FROM ticket_order_execution
        WHERE del_flag = 0
        """)
    TicketDashboardStatsAggregate selectDashboardStats();

    @Select({
        "<script>",
        "SELECT execution_id, task_id, platform_id, account_id, lottery_event_url, execution_status, started_at, executed_at",
        "FROM ticket_order_execution FORCE INDEX (idx_ticket_order_execution_lottery_event_account)",
        "WHERE del_flag = 0",
        "AND account_id IN",
        "<foreach collection='accountIds' item='accountId' open='(' separator=',' close=')'>#{accountId}</foreach>",
        "<if test='platformIds != null and !platformIds.isEmpty()'>",
        "AND platform_id IN",
        "<foreach collection='platformIds' item='platformId' open='(' separator=',' close=')'>#{platformId}</foreach>",
        "</if>",
        "AND purchase_type = 'lottery'",
        "AND execution_status IN ('submitted', 'pending_payment', 'paid', 'completed')",
        "AND lottery_event_url = #{eventUrl}",
        "<if test='excludeTaskId != null'>AND task_id &lt;&gt; #{excludeTaskId}</if>",
        "</script>"
    })
    List<TicketOrderExecution> selectLotteryOccupanciesByEvent(@Param("accountIds") Collection<Long> accountIds,
                                                               @Param("platformIds") Collection<Long> platformIds,
                                                               @Param("eventUrl") String eventUrl,
                                                               @Param("excludeTaskId") Long excludeTaskId);

    @Select({
        "<script>",
        "SELECT execution_id, task_id, platform_id, account_id, config_snapshot, lottery_event_url, execution_status, started_at, executed_at",
        "FROM ticket_order_execution FORCE INDEX (idx_ticket_order_execution_lottery_event_account)",
        "WHERE del_flag = 0",
        "AND account_id IN",
        "<foreach collection='accountIds' item='accountId' open='(' separator=',' close=')'>#{accountId}</foreach>",
        "<if test='platformIds != null and !platformIds.isEmpty()'>",
        "AND platform_id IN",
        "<foreach collection='platformIds' item='platformId' open='(' separator=',' close=')'>#{platformId}</foreach>",
        "</if>",
        "AND purchase_type = 'lottery'",
        "AND execution_status IN ('submitted', 'pending_payment', 'paid', 'completed')",
        "AND lottery_event_url = ''",
        "<if test='excludeTaskId != null'>AND task_id &lt;&gt; #{excludeTaskId}</if>",
        "</script>"
    })
    List<TicketOrderExecution> selectLotteryOccupanciesWithBlankEventUrl(@Param("accountIds") Collection<Long> accountIds,
                                                                         @Param("platformIds") Collection<Long> platformIds,
                                                                         @Param("excludeTaskId") Long excludeTaskId);

    @Select({
        "<script>",
        "SELECT execution_id, task_id, platform_id, account_id, config_snapshot, lottery_event_url, execution_status, started_at, executed_at",
        "FROM ticket_order_execution FORCE INDEX (idx_ticket_order_execution_lottery_event_account)",
        "WHERE del_flag = 0",
        "AND account_id IN",
        "<foreach collection='accountIds' item='accountId' open='(' separator=',' close=')'>#{accountId}</foreach>",
        "<if test='platformIds != null and !platformIds.isEmpty()'>",
        "AND platform_id IN",
        "<foreach collection='platformIds' item='platformId' open='(' separator=',' close=')'>#{platformId}</foreach>",
        "</if>",
        "AND purchase_type = 'lottery'",
        "AND execution_status IN ('submitted', 'pending_payment', 'paid', 'completed')",
        "AND lottery_event_url IS NULL",
        "<if test='excludeTaskId != null'>AND task_id &lt;&gt; #{excludeTaskId}</if>",
        "</script>"
    })
    List<TicketOrderExecution> selectLotteryOccupanciesWithNullEventUrl(@Param("accountIds") Collection<Long> accountIds,
                                                                        @Param("platformIds") Collection<Long> platformIds,
                                                                        @Param("excludeTaskId") Long excludeTaskId);
}
