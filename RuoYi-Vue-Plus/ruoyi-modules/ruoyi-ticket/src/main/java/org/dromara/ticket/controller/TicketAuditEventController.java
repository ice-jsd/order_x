package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.domain.bo.TicketAuditEventBo;
import org.dromara.ticket.domain.vo.TicketAuditEventVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/audit-log")
public class TicketAuditEventController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:audit:list")
    @GetMapping("/list")
    public TableDataInfo<TicketAuditEventVo> list(TicketAuditEventBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectAuditPage(bo, pageQuery);
    }
}
