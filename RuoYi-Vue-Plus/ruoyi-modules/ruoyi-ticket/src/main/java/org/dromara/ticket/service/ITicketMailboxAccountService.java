package org.dromara.ticket.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.domain.bo.TicketMailboxAccountBo;
import org.dromara.ticket.domain.bo.TicketMailboxBatchCreateBo;
import org.dromara.ticket.domain.bo.TicketMailboxMailSyncBo;
import org.dromara.ticket.domain.bo.TicketMailboxStatusBo;
import org.dromara.ticket.domain.vo.TicketMailboxAccountVo;
import org.dromara.ticket.domain.vo.TicketMailboxBatchCreateResultVo;

public interface ITicketMailboxAccountService {

    TableDataInfo<TicketMailboxAccountVo> selectMailboxPage(TicketMailboxAccountBo bo, PageQuery pageQuery);

    TicketMailboxBatchCreateResultVo batchCreate(TicketMailboxBatchCreateBo bo);

    boolean changeStatus(TicketMailboxStatusBo bo);

    boolean syncLatestMail(Long mailboxId);

    boolean syncLatestMail(TicketMailboxMailSyncBo bo);
}
