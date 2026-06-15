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
import org.dromara.ticket.domain.bo.TicketManagedAccountLastNameBo;
import org.dromara.ticket.domain.bo.TicketManagedAccountBo;
import org.dromara.ticket.domain.bo.TicketManagedAccountUpdateBo;
import org.dromara.ticket.domain.bo.TicketHandsFormQuickCreateBo;
import org.dromara.ticket.domain.bo.TicketBatchLoginBo;
import org.dromara.ticket.domain.bo.TicketBatchRegisterBo;
import org.dromara.ticket.domain.bo.TicketBatchStatusUpdateBo;
import org.dromara.ticket.domain.bo.TicketAuditEventBo;
import org.dromara.ticket.domain.bo.TicketLoginEmailCodeBo;
import org.dromara.ticket.domain.bo.TicketPhoneNumberBo;
import org.dromara.ticket.domain.bo.TicketLoginBatchBo;
import org.dromara.ticket.domain.bo.TicketRegistrationBatchBo;
import org.dromara.ticket.domain.vo.TicketAuditEventVo;
import org.dromara.ticket.domain.vo.TicketLoginBatchDetailVo;
import org.dromara.ticket.domain.vo.TicketLoginBatchVo;
import org.dromara.ticket.domain.vo.TicketPhoneNumberVo;
import org.dromara.ticket.domain.vo.TicketHandsFormQuickCreateResultVo;
import org.dromara.ticket.domain.vo.TicketManagedAccountVo;
import org.dromara.ticket.domain.vo.TicketRegistrationBatchDetailVo;
import org.dromara.ticket.domain.vo.TicketRegistrationBatchVo;
import org.dromara.ticket.domain.vo.TicketSelectableAccountIdsVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;

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

    @SaCheckPermission("ticket:account:list")
    @GetMapping("/selectable-ids")
    public R<TicketSelectableAccountIdsVo> selectableIds(TicketManagedAccountBo bo) {
        return R.ok(ticketOpsService.selectSelectableAccountIds(bo));
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

    @SaCheckPermission("ticket:account:add")
    @Log(title = "Hands 快速建号", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/hands-form/quick-create")
    public R<TicketHandsFormQuickCreateResultVo> quickCreateHandsFormAccounts(@Valid @RequestBody TicketHandsFormQuickCreateBo bo) {
        return R.ok(ticketOpsService.quickCreateHandsFormAccounts(bo));
    }

    @SaCheckPermission("ticket:account:add")
    @Log(title = "账号池批量注册", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/batch-register")
    public R<Long> batchRegister(@RequestBody TicketBatchRegisterBo bo) {
        return ticketOpsService.registerFromPhones(bo.getPlatformId(), bo);
    }

    @SaCheckPermission("ticket:account:edit")
    @Log(title = "账号池批量登录", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/batch-login")
    public R<Long> batchLogin(@RequestBody TicketBatchLoginBo bo) {
        return ticketOpsService.loginAccounts(bo.getPlatformId(), bo);
    }

    @SaCheckPermission("ticket:account:list")
    @GetMapping("/registration-batch/list")
    public TableDataInfo<TicketRegistrationBatchVo> registrationBatchList(TicketRegistrationBatchBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectRegistrationBatchPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:account:list")
    @GetMapping("/registration-batch/{batchId}/details")
    public R<List<TicketRegistrationBatchDetailVo>> registrationBatchDetails(@PathVariable Long batchId) {
        return R.ok(ticketOpsService.selectRegistrationBatchDetails(batchId));
    }

    @SaCheckPermission("ticket:account:edit")
    @Log(title = "账号池注册批次状态", businessType = BusinessType.UPDATE)
    @PutMapping("/registration-batch/{batchId}/status")
    public R<Void> updateRegistrationBatchStatus(@PathVariable Long batchId, @Valid @RequestBody TicketBatchStatusUpdateBo bo) {
        return toAjax(ticketOpsService.updateRegistrationBatchStatus(batchId, bo));
    }

    @SaCheckPermission("ticket:account:list")
    @GetMapping("/login-batch/list")
    public TableDataInfo<TicketLoginBatchVo> loginBatchList(TicketLoginBatchBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectLoginBatchPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:account:list")
    @GetMapping("/login-batch/{batchId}/details")
    public R<List<TicketLoginBatchDetailVo>> loginBatchDetails(@PathVariable Long batchId) {
        return R.ok(ticketOpsService.selectLoginBatchDetails(batchId));
    }

    @SaCheckPermission("ticket:account:edit")
    @Log(title = "账号池登录邮箱验证码", businessType = BusinessType.UPDATE)
    @PostMapping("/login-batch/{batchId}/details/{detailId}/email-code")
    public R<Void> submitLoginEmailCode(
        @PathVariable Long batchId,
        @PathVariable Long detailId,
        @Valid @RequestBody TicketLoginEmailCodeBo bo
    ) {
        return ticketOpsService.submitLoginEmailCode(batchId, detailId, bo);
    }

    @SaCheckPermission("ticket:account:edit")
    @Log(title = "账号池登录批次状态", businessType = BusinessType.UPDATE)
    @PutMapping("/login-batch/{batchId}/status")
    public R<Void> updateLoginBatchStatus(@PathVariable Long batchId, @Valid @RequestBody TicketBatchStatusUpdateBo bo) {
        return toAjax(ticketOpsService.updateLoginBatchStatus(batchId, bo));
    }

    @SaCheckPermission("ticket:account:edit")
    @Log(title = "账号池", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<Void> edit(@Valid @RequestBody TicketManagedAccountUpdateBo bo) {
        return toAjax(ticketOpsService.updateManagedAccount(bo));
    }

    @SaCheckPermission("ticket:account:edit")
    @Log(title = "账号池", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{accountId}/last-name")
    public R<Void> editLastName(@PathVariable Long accountId, @Valid @RequestBody TicketManagedAccountLastNameBo bo) {
        return toAjax(ticketOpsService.updateManagedAccountLastName(accountId, bo));
    }

    @SaCheckPermission("ticket:account:list")
    @GetMapping("/last-name-record/list")
    public TableDataInfo<TicketAuditEventVo> lastNameRecordList(TicketAuditEventBo bo, PageQuery pageQuery) {
        bo.setModuleName("account");
        bo.setActionType("updateLastName");
        bo.setBusinessType("account");
        return ticketOpsService.selectAuditPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:account:remove")
    @Log(title = "账号池", businessType = BusinessType.DELETE)
    @DeleteMapping("/{accountIds}")
    public R<Void> remove(@PathVariable Long[] accountIds) {
        return toAjax(ticketOpsService.removeManagedAccounts(accountIds));
    }
}
