package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.domain.bo.TicketManagedAccountBo;
import org.dromara.ticket.domain.vo.TicketManagedAccountVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/account")
public class TicketAccountController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:account:list")
    @GetMapping("/list")
    public TableDataInfo<TicketManagedAccountVo> list(TicketManagedAccountBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectAccountPage(bo, pageQuery);
    }
}
