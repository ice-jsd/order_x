package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.ticket.domain.bo.TicketOrderExecutionPaymentBo;
import org.dromara.ticket.domain.bo.TicketOrderExecutionBo;
import org.dromara.ticket.domain.vo.TicketOrderExecutionVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/order-execution")
public class TicketOrderExecutionController extends BaseController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:orderExecution:list")
    @GetMapping("/list")
    public TableDataInfo<TicketOrderExecutionVo> list(TicketOrderExecutionBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectOrderExecutionPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:orderExecution:edit")
    @Log(title = "下单执行", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{executionId}/mark-paid")
    public R<Void> markPaid(@PathVariable Long executionId, @RequestBody TicketOrderExecutionPaymentBo bo) {
        return toAjax(ticketOpsService.markOrderExecutionPaid(executionId, bo));
    }
}
