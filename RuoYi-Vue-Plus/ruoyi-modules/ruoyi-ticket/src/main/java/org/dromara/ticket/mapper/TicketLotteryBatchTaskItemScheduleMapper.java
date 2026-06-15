package org.dromara.ticket.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.ticket.domain.TicketLotteryBatchTaskItemSchedule;
import org.dromara.ticket.domain.vo.TicketLotteryBatchTaskItemScheduleVo;

import java.util.Collection;

public interface TicketLotteryBatchTaskItemScheduleMapper extends BaseMapperPlus<TicketLotteryBatchTaskItemSchedule, TicketLotteryBatchTaskItemScheduleVo> {

    @Delete({
        "<script>",
        "DELETE s FROM ticket_lottery_batch_task_item_schedule s",
        "INNER JOIN ticket_lottery_batch_task_item i ON i.batch_item_id = s.batch_item_id",
        "WHERE i.batch_task_id IN",
        "<foreach collection='batchTaskIds' item='batchTaskId' open='(' separator=',' close=')'>",
        "#{batchTaskId}",
        "</foreach>",
        "</script>"
    })
    int deleteByBatchTaskIdsPhysical(@Param("batchTaskIds") Collection<Long> batchTaskIds);
}
