package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.ticket.domain.bo.TicketExternalLoginReportBo;
import org.dromara.ticket.domain.vo.TicketExternalOfflineAccountVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SaIgnore
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/external/account")
public class TicketExternalAccountController {

    private final ITicketOpsService ticketOpsService;

    @PostMapping("/login-success")
    public R<Void> reportLoginSuccess(@Validated @RequestBody TicketExternalLoginReportBo bo) {
        return ticketOpsService.reportExternalLoginSuccess(bo);
    }

    @GetMapping("/next-offline")
    public R<TicketExternalOfflineAccountVo> fetchNextOfflineAccount(
        @RequestParam @NotBlank(message = "platformCode不能为空") String platformCode) {
        return ticketOpsService.fetchNextOfflineAccount(platformCode);
    }
}
