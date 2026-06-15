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
import org.dromara.ticket.domain.bo.TicketBatchStatusUpdateBo;
import org.dromara.ticket.domain.bo.TicketLotteryBatchTaskBo;
import org.dromara.ticket.domain.vo.TicketLotteryBatchTaskProcessVo;
import org.dromara.ticket.domain.vo.TicketLotteryBatchTaskVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/lottery-batch-task")
public class TicketLotteryBatchTaskController extends BaseController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:lotteryBatchTask:list")
    @GetMapping("/list")
    public TableDataInfo<TicketLotteryBatchTaskVo> list(TicketLotteryBatchTaskBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectLotteryBatchTaskPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:lotteryBatchTask:query")
    @GetMapping("/{batchTaskId}")
    public R<TicketLotteryBatchTaskVo> getInfo(@PathVariable Long batchTaskId) {
        return R.ok(ticketOpsService.selectLotteryBatchTaskById(batchTaskId));
    }

    @SaCheckPermission("ticket:lotteryBatchTask:query")
    @GetMapping("/{batchTaskId}/process")
    public R<TicketLotteryBatchTaskProcessVo> getProcess(@PathVariable Long batchTaskId) {
        return R.ok(ticketOpsService.selectLotteryBatchTaskProcess(batchTaskId));
    }

    @SaCheckPermission("ticket:lotteryBatchTask:add")
    @Log(title = "批量抽票任务", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@RequestBody TicketLotteryBatchTaskBo bo) {
        return toAjax(ticketOpsService.saveLotteryBatchTask(bo));
    }

    @SaCheckPermission("ticket:lotteryBatchTask:edit")
    @Log(title = "批量抽票任务", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<Void> edit(@RequestBody TicketLotteryBatchTaskBo bo) {
        return toAjax(ticketOpsService.updateLotteryBatchTask(bo));
    }

    @SaCheckPermission("ticket:lotteryBatchTask:edit")
    @Log(title = "批量抽票任务", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{batchTaskId}/status")
    public R<Void> updateStatus(@PathVariable Long batchTaskId, @RequestBody TicketBatchStatusUpdateBo bo) {
        return toAjax(ticketOpsService.updateLotteryBatchTaskStatus(batchTaskId, bo));
    }

    @SaCheckPermission("ticket:lotteryBatchTask:execute")
    @Log(title = "批量抽票任务", businessType = BusinessType.OTHER)
    @RepeatSubmit
    @PostMapping("/{batchTaskId}/execute-now")
    public R<Long> executeNow(@PathVariable Long batchTaskId) {
        return ticketOpsService.executeLotteryBatchTaskNow(batchTaskId);
    }
}
