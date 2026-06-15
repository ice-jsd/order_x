package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.ticket.domain.vo.TicketDashboardOverviewVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/dashboard")
public class TicketDashboardController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:account:list")
    @GetMapping("/overview")
    public R<TicketDashboardOverviewVo> overview() {
        return R.ok(ticketOpsService.selectDashboardOverview());
    }
}
