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
import org.dromara.ticket.domain.bo.TicketSaleTaskBo;
import org.dromara.ticket.domain.vo.TicketSaleTaskVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/sale-task")
public class TicketSaleTaskController extends BaseController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:saleTask:list")
    @GetMapping("/list")
    public TableDataInfo<TicketSaleTaskVo> list(TicketSaleTaskBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectSaleTaskPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:saleTask:query")
    @GetMapping("/{taskId}")
    public R<TicketSaleTaskVo> getInfo(@PathVariable Long taskId) {
        return R.ok(ticketOpsService.selectSaleTaskById(taskId));
    }

    @SaCheckPermission("ticket:saleTask:add")
    @Log(title = "销售任务", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@RequestBody TicketSaleTaskBo bo) {
        return toAjax(ticketOpsService.saveSaleTask(bo));
    }

    @SaCheckPermission("ticket:saleTask:edit")
    @Log(title = "销售任务", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<Void> edit(@RequestBody TicketSaleTaskBo bo) {
        return toAjax(ticketOpsService.updateSaleTask(bo));
    }

    @SaCheckPermission("ticket:saleTask:remove")
    @Log(title = "销售任务", businessType = BusinessType.DELETE)
    @DeleteMapping("/{taskIds}")
    public R<Void> remove(@PathVariable Long[] taskIds) {
        return toAjax(ticketOpsService.removeSaleTasks(taskIds));
    }

    @SaCheckPermission("ticket:saleTask:execute")
    @Log(title = "销售任务", businessType = BusinessType.OTHER)
    @RepeatSubmit
    @PostMapping("/{taskId}/execute")
    public R<Long> execute(@PathVariable Long taskId) {
        return ticketOpsService.executeSaleTask(taskId);
    }
}
