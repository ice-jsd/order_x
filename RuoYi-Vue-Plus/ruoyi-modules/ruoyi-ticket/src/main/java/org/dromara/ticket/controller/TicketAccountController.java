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
import org.dromara.ticket.domain.bo.TicketManagedAccountCreateBo;
import org.dromara.ticket.domain.bo.TicketManagedAccountBo;
import org.dromara.ticket.domain.bo.TicketManagedAccountUpdateBo;
import org.dromara.ticket.domain.bo.TicketPhoneNumberBo;
import org.dromara.ticket.domain.vo.TicketPhoneNumberVo;
import org.dromara.ticket.domain.vo.TicketManagedAccountVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/account")
public class TicketAccountController extends BaseController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:account:list")
    @GetMapping("/list")
    public TableDataInfo<TicketManagedAccountVo> list(TicketManagedAccountBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectAccountPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:account:add")
    @GetMapping("/available-phone/list")
    public TableDataInfo<TicketPhoneNumberVo> availablePhones(Long platformId, TicketPhoneNumberBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectBindablePhonePage(platformId, bo, pageQuery);
    }

    @SaCheckPermission("ticket:account:add")
    @Log(title = "账号池", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@Valid @RequestBody TicketManagedAccountCreateBo bo) {
        return toAjax(ticketOpsService.createManagedAccount(bo));
    }

    @SaCheckPermission("ticket:account:edit")
    @Log(title = "账号池", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<Void> edit(@Valid @RequestBody TicketManagedAccountUpdateBo bo) {
        return toAjax(ticketOpsService.updateManagedAccount(bo));
    }
}
