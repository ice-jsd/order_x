package org.dromara.ticket.mapper;

import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.dto.TicketDashboardStatsAggregate;
import org.dromara.ticket.domain.vo.TicketPlatformConfigVo;

public interface TicketPlatformConfigMapper extends BaseMapperPlus<TicketPlatformConfig, TicketPlatformConfigVo> {

    @Select("""
        SELECT COUNT(*) AS platformTotal,
               SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END) AS enabledPlatformCount
        FROM ticket_platform_config
        WHERE del_flag = 0
        """)
    TicketDashboardStatsAggregate selectDashboardStats();
}
