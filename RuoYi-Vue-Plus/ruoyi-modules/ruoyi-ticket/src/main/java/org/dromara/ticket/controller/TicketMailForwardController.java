package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.ticket.domain.bo.TicketMailForwardRecordBo;
import org.dromara.ticket.domain.bo.TicketMailForwardSendBo;
import org.dromara.ticket.domain.vo.TicketMailForwardRecordVo;
import org.dromara.ticket.service.ITicketMailForwardService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/mail-forward")
public class TicketMailForwardController extends BaseController {

    private final ITicketMailForwardService ticketMailForwardService;

    @SaCheckPermission("ticket:mailForward:list")
    @GetMapping("/list")
    public TableDataInfo<TicketMailForwardRecordVo> list(TicketMailForwardRecordBo bo, PageQuery pageQuery) {
        return ticketMailForwardService.selectPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:mailForward:send")
    @Log(title = "邮件转发", businessType = BusinessType.OTHER)
    @RepeatSubmit
    @PostMapping("/send")
    public R<Void> send(@Valid @RequestBody TicketMailForwardSendBo bo) {
        return toAjax(ticketMailForwardService.send(bo));
    }
}
