package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.domain.bo.TicketLoginBatchBo;
import org.dromara.ticket.domain.vo.TicketLoginBatchDetailVo;
import org.dromara.ticket.domain.vo.TicketLoginBatchVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/login-batch")
public class TicketLoginBatchController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:loginBatch:list")
    @GetMapping("/list")
    public TableDataInfo<TicketLoginBatchVo> list(TicketLoginBatchBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectLoginBatchPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:loginBatch:list")
    @GetMapping("/{batchId}")
    public R<TicketLoginBatchVo> getInfo(@PathVariable Long batchId) {
        return R.ok(ticketOpsService.selectLoginBatchById(batchId));
    }

    @SaCheckPermission("ticket:loginBatch:list")
    @GetMapping("/{batchId}/details")
    public R<List<TicketLoginBatchDetailVo>> details(@PathVariable Long batchId) {
        return R.ok(ticketOpsService.selectLoginBatchDetails(batchId));
    }
}
