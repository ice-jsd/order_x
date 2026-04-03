package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.domain.bo.TicketRegistrationBatchBo;
import org.dromara.ticket.domain.vo.TicketRegistrationBatchDetailVo;
import org.dromara.ticket.domain.vo.TicketRegistrationBatchVo;
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
@RequestMapping("/ticket/registration-batch")
public class TicketRegistrationBatchController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:registrationBatch:list")
    @GetMapping("/list")
    public TableDataInfo<TicketRegistrationBatchVo> list(TicketRegistrationBatchBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectRegistrationBatchPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:registrationBatch:list")
    @GetMapping("/{batchId}")
    public R<TicketRegistrationBatchVo> getInfo(@PathVariable Long batchId) {
        return R.ok(ticketOpsService.selectRegistrationBatchById(batchId));
    }

    @SaCheckPermission("ticket:registrationBatch:list")
    @GetMapping("/{batchId}/details")
    public R<List<TicketRegistrationBatchDetailVo>> details(@PathVariable Long batchId) {
        return R.ok(ticketOpsService.selectRegistrationBatchDetails(batchId));
    }
}
