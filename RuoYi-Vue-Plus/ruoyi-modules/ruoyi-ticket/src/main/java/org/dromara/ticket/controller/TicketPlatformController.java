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
import org.dromara.ticket.domain.bo.TicketBatchLoginBo;
import org.dromara.ticket.domain.bo.TicketBatchRegisterBo;
import org.dromara.ticket.domain.bo.TicketManagedAccountBo;
import org.dromara.ticket.domain.bo.TicketPhoneNumberBo;
import org.dromara.ticket.domain.bo.TicketPlatformConfigBo;
import org.dromara.ticket.domain.vo.TicketManagedAccountVo;
import org.dromara.ticket.domain.vo.TicketPhoneNumberVo;
import org.dromara.ticket.domain.vo.TicketPlatformConfigVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/platform")
public class TicketPlatformController extends BaseController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:platform:list")
    @GetMapping("/list")
    public TableDataInfo<TicketPlatformConfigVo> list(TicketPlatformConfigBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectPlatformPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:platform:query")
    @GetMapping("/{platformId}")
    public R<TicketPlatformConfigVo> getInfo(@PathVariable Long platformId) {
        return R.ok(ticketOpsService.selectPlatformById(platformId));
    }

    @SaCheckPermission("ticket:platform:add")
    @Log(title = "票务平台", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@RequestBody TicketPlatformConfigBo bo) {
        return toAjax(ticketOpsService.savePlatform(bo));
    }

    @SaCheckPermission("ticket:platform:edit")
    @Log(title = "票务平台", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<Void> edit(@RequestBody TicketPlatformConfigBo bo) {
        return toAjax(ticketOpsService.updatePlatform(bo));
    }

    @SaCheckPermission("ticket:platform:remove")
    @Log(title = "票务平台", businessType = BusinessType.DELETE)
    @DeleteMapping("/{platformIds}")
    public R<Void> remove(@PathVariable Long[] platformIds) {
        return toAjax(ticketOpsService.removePlatforms(platformIds));
    }

    @SaCheckPermission("ticket:platform:register")
    @GetMapping("/{platformId}/registerable-phones")
    public TableDataInfo<TicketPhoneNumberVo> registerablePhones(@PathVariable Long platformId, TicketPhoneNumberBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectRegisterablePhonePage(platformId, bo, pageQuery);
    }

    @SaCheckPermission("ticket:platform:register")
    @Log(title = "平台批量注册", businessType = BusinessType.OTHER)
    @RepeatSubmit
    @PostMapping("/{platformId}/register-from-phones")
    public R<Long> registerFromPhones(@PathVariable Long platformId, @RequestBody TicketBatchRegisterBo bo) {
        return ticketOpsService.registerFromPhones(platformId, bo);
    }

    @SaCheckPermission("ticket:account:login")
    @GetMapping("/{platformId}/loginable-accounts")
    public TableDataInfo<TicketManagedAccountVo> loginableAccounts(@PathVariable Long platformId, TicketManagedAccountBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectLoginableAccountPage(platformId, bo, pageQuery);
    }

    @SaCheckPermission("ticket:account:login")
    @Log(title = "平台批量登录", businessType = BusinessType.OTHER)
    @RepeatSubmit
    @PostMapping("/{platformId}/login-accounts")
    public R<Long> loginAccounts(@PathVariable Long platformId, @RequestBody TicketBatchLoginBo bo) {
        return ticketOpsService.loginAccounts(platformId, bo);
    }
}
