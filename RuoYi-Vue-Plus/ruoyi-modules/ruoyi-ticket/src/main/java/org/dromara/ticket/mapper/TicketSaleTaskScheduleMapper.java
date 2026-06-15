package org.dromara.ticket.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.ticket.domain.TicketSaleTaskSchedule;
import org.dromara.ticket.domain.vo.TicketSaleTaskScheduleVo;

import java.util.Collection;

public interface TicketSaleTaskScheduleMapper extends BaseMapperPlus<TicketSaleTaskSchedule, TicketSaleTaskScheduleVo> {

    @Delete({
        "<script>",
        "DELETE FROM ticket_sale_task_schedule",
        "WHERE task_id IN",
        "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
        "#{taskId}",
        "</foreach>",
        "</script>"
    })
    int deleteByTaskIdsPhysical(@Param("taskIds") Collection<Long> taskIds);
}
