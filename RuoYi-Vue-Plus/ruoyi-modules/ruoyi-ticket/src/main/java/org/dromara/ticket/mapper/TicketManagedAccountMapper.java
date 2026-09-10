package org.dromara.ticket.mapper;

import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.dto.TicketDashboardStatsAggregate;
import org.dromara.ticket.domain.vo.TicketManagedAccountVo;

public interface TicketManagedAccountMapper extends BaseMapperPlus<TicketManagedAccount, TicketManagedAccountVo> {

    @Select("""
        SELECT COUNT(*) AS accountTotal,
               SUM(CASE WHEN login_status = 'logged_in' THEN 1 ELSE 0 END) AS loggedInAccountCount,
               SUM(CASE WHEN account_status = 'activated' THEN 1 ELSE 0 END) AS activatedAccountCount,
               SUM(CASE
                       WHEN login_status = 'login_failed'
                         OR (last_error IS NOT NULL AND last_error <> '')
                       THEN 1 ELSE 0
                   END) AS accountErrorCount
        FROM ticket_managed_account
        WHERE del_flag = 0
        """)
    TicketDashboardStatsAggregate selectDashboardStats();
}
