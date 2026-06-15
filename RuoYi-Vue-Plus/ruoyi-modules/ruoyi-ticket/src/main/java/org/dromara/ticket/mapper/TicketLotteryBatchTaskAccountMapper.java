package org.dromara.ticket.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.ticket.domain.TicketLotteryBatchTaskAccount;

import java.util.Collection;

public interface TicketLotteryBatchTaskAccountMapper extends BaseMapperPlus<TicketLotteryBatchTaskAccount, TicketLotteryBatchTaskAccount> {

    @Delete({
        "<script>",
        "DELETE FROM ticket_lottery_batch_task_account",
        "WHERE batch_task_id IN",
        "<foreach collection='batchTaskIds' item='batchTaskId' open='(' separator=',' close=')'>",
        "#{batchTaskId}",
        "</foreach>",
        "</script>"
    })
    int deleteByBatchTaskIdsPhysical(@Param("batchTaskIds") Collection<Long> batchTaskIds);
}
