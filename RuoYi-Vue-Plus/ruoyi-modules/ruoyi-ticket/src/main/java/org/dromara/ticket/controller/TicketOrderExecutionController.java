package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.domain.bo.TicketOrderExecutionBo;
import org.dromara.ticket.domain.vo.TicketOrderExecutionVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/order-execution")
public class TicketOrderExecutionController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:orderExecution:list")
    @GetMapping("/list")
    public TableDataInfo<TicketOrderExecutionVo> list(TicketOrderExecutionBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectOrderExecutionPage(bo, pageQuery);
    }
}
