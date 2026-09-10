package org.dromara.ticket.mapper;

import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.ticket.domain.TicketSaleTask;
import org.dromara.ticket.domain.dto.TicketDashboardStatsAggregate;
import org.dromara.ticket.domain.vo.TicketSaleTaskVo;

public interface TicketSaleTaskMapper extends BaseMapperPlus<TicketSaleTask, TicketSaleTaskVo> {

    @Select("""
        SELECT COUNT(*) AS taskTotal,
               SUM(CASE WHEN task_status = 'executing' THEN 1 ELSE 0 END) AS runningTaskCount,
               SUM(CASE WHEN task_status IN ('failed', 'blocked') THEN 1 ELSE 0 END) AS abnormalTaskCount
        FROM ticket_sale_task
        WHERE del_flag = 0
        """)
    TicketDashboardStatsAggregate selectDashboardStats();
}
