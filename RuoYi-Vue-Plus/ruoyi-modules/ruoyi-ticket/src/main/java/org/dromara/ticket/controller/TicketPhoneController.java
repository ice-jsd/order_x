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
import org.dromara.ticket.domain.bo.TicketPhoneBulkImportBo;
import org.dromara.ticket.domain.bo.TicketPhoneNumberBo;
import org.dromara.ticket.domain.bo.TicketPhoneStatusBo;
import org.dromara.ticket.domain.vo.TicketPhoneBulkImportResultVo;
import org.dromara.ticket.domain.vo.TicketPhoneNumberVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/phone")
public class TicketPhoneController extends BaseController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:phone:list")
    @GetMapping("/list")
    public TableDataInfo<TicketPhoneNumberVo> list(TicketPhoneNumberBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectPhonePage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:phone:import")
    @Log(title = "号码池", businessType = BusinessType.IMPORT)
    @RepeatSubmit
    @PostMapping("/bulk-import")
    public R<TicketPhoneBulkImportResultVo> bulkImport(@RequestBody TicketPhoneBulkImportBo bo) {
        return R.ok(ticketOpsService.importPhones(bo));
    }

    @SaCheckPermission("ticket:phone:edit")
    @Log(title = "号码池", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/changeStatus")
    public R<Void> changeStatus(@RequestBody TicketPhoneStatusBo bo) {
        return toAjax(ticketOpsService.changePhoneStatus(bo));
    }
}
