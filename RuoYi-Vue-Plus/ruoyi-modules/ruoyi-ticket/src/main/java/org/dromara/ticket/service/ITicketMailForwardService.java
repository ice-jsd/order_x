package org.dromara.ticket.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.domain.bo.TicketMailForwardRecordBo;
import org.dromara.ticket.domain.bo.TicketMailForwardSendBo;
import org.dromara.ticket.domain.vo.TicketMailForwardRecordVo;

public interface ITicketMailForwardService {

    TableDataInfo<TicketMailForwardRecordVo> selectPage(TicketMailForwardRecordBo bo, PageQuery pageQuery);

    boolean send(TicketMailForwardSendBo bo);
}
