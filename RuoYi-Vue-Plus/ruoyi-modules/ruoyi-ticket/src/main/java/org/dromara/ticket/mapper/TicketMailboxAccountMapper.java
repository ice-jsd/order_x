package org.dromara.ticket.mapper;

import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.ticket.domain.TicketMailboxAccount;
import org.dromara.ticket.domain.dto.TicketDashboardStatsAggregate;
import org.dromara.ticket.domain.vo.TicketMailboxAccountVo;

public interface TicketMailboxAccountMapper extends BaseMapperPlus<TicketMailboxAccount, TicketMailboxAccountVo> {

    @Select("""
        SELECT COUNT(*) AS mailboxTotal,
               SUM(CASE
                       WHEN (last_error IS NOT NULL AND last_error <> '')
                         OR (last_mail_sync_error IS NOT NULL AND last_mail_sync_error <> '')
                       THEN 1 ELSE 0
                   END) AS mailboxErrorCount,
               SUM(CASE WHEN used_account_id IS NULL THEN 1 ELSE 0 END) AS unusedMailboxCount
        FROM ticket_mailbox_account
        WHERE del_flag = 0
        """)
    TicketDashboardStatsAggregate selectDashboardStats();
}
