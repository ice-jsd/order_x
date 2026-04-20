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
import org.dromara.ticket.domain.bo.TicketMailboxAccountBo;
import org.dromara.ticket.domain.bo.TicketMailboxBatchCreateBo;
import org.dromara.ticket.domain.bo.TicketMailboxMailSyncBo;
import org.dromara.ticket.domain.bo.TicketMailboxStatusBo;
import org.dromara.ticket.domain.vo.TicketMailboxAccountVo;
import org.dromara.ticket.domain.vo.TicketMailboxBatchCreateResultVo;
import org.dromara.ticket.service.ITicketMailboxAccountService;
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
@RequestMapping("/ticket/mailbox-account")
public class TicketMailboxAccountController extends BaseController {

    private final ITicketMailboxAccountService mailboxAccountService;

    @SaCheckPermission("ticket:mailbox:list")
    @GetMapping("/list")
    public TableDataInfo<TicketMailboxAccountVo> list(TicketMailboxAccountBo bo, PageQuery pageQuery) {
        return mailboxAccountService.selectMailboxPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:mailbox:create")
    @Log(title = "邮箱账号池", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/batch-create")
    public R<TicketMailboxBatchCreateResultVo> batchCreate(@Valid @RequestBody TicketMailboxBatchCreateBo bo) {
        return R.ok(mailboxAccountService.batchCreate(bo));
    }

    @SaCheckPermission("ticket:mailbox:edit")
    @Log(title = "邮箱账号池", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/changeStatus")
    public R<Void> changeStatus(@RequestBody TicketMailboxStatusBo bo) {
        return toAjax(mailboxAccountService.changeStatus(bo));
    }

    @SaCheckPermission("ticket:mailbox:sync")
    @Log(title = "邮箱账号池", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{mailboxId}/sync-mail")
    public R<Void> syncMail(@PathVariable Long mailboxId) {
        return toAjax(mailboxAccountService.syncLatestMail(mailboxId));
    }

    @SaCheckPermission("ticket:mailbox:sync")
    @Log(title = "邮箱账号池", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/sync-mail")
    public R<Void> syncMail(@RequestBody TicketMailboxMailSyncBo bo) {
        return toAjax(mailboxAccountService.syncLatestMail(bo));
    }
}
