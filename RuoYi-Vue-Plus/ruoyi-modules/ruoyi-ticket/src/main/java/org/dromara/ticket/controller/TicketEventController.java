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
import org.dromara.ticket.domain.bo.TicketEventConfigBo;
import org.dromara.ticket.domain.vo.TicketEventConfigVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/event")
public class TicketEventController extends BaseController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:event:list")
    @GetMapping("/list")
    public TableDataInfo<TicketEventConfigVo> list(TicketEventConfigBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectEventPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:event:query")
    @GetMapping("/{eventId}")
    public R<TicketEventConfigVo> getInfo(@PathVariable Long eventId) {
        return R.ok(ticketOpsService.selectEventById(eventId));
    }

    @SaCheckPermission("ticket:event:add")
    @Log(title = "票务活动", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@RequestBody TicketEventConfigBo bo) {
        return toAjax(ticketOpsService.saveEvent(bo));
    }

    @SaCheckPermission("ticket:event:edit")
    @Log(title = "票务活动", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<Void> edit(@RequestBody TicketEventConfigBo bo) {
        return toAjax(ticketOpsService.updateEvent(bo));
    }

    @SaCheckPermission("ticket:event:remove")
    @Log(title = "票务活动", businessType = BusinessType.DELETE)
    @DeleteMapping("/{eventIds}")
    public R<Void> remove(@PathVariable Long[] eventIds) {
        return toAjax(ticketOpsService.removeEvents(eventIds));
    }
}
