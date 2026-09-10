package org.dromara.ticket.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.ticket.domain.TicketSaleTaskAccount;
import org.dromara.ticket.domain.dto.TicketSaleTaskAccountCountDto;

import java.util.Collection;
import java.util.List;

public interface TicketSaleTaskAccountMapper extends BaseMapperPlus<TicketSaleTaskAccount, TicketSaleTaskAccount> {

    @Delete({
        "<script>",
        "DELETE FROM ticket_sale_task_account",
        "WHERE task_id IN",
        "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
        "#{taskId}",
        "</foreach>",
        "</script>"
    })
    int deleteByTaskIdsPhysical(@Param("taskIds") Collection<Long> taskIds);

    @Select({
        "<script>",
        "SELECT task_id AS taskId, COUNT(*) AS accountCount",
        "FROM ticket_sale_task_account",
        "WHERE del_flag = 0",
        "<if test='taskIds != null and taskIds.size() > 0'>",
        "AND task_id IN",
        "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
        "#{taskId}",
        "</foreach>",
        "</if>",
        "GROUP BY task_id",
        "</script>"
    })
    List<TicketSaleTaskAccountCountDto> selectAccountCountsByTaskIds(@Param("taskIds") Collection<Long> taskIds);
}
