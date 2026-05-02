package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.ticket.domain.bo.TicketExternalActivationConfirmBo;
import org.dromara.ticket.domain.bo.TicketExternalLoginReqDataBo;
import org.dromara.ticket.domain.bo.TicketExternalLoginReportBo;
import org.dromara.ticket.domain.bo.TicketExternalRegistrationConfirmBo;
import org.dromara.ticket.domain.vo.TicketExternalOfflineAccountVo;
import org.dromara.ticket.domain.vo.TicketExternalRegisterAccountVo;
import org.dromara.ticket.domain.vo.TicketExternalSmsCodeVo;
import org.dromara.ticket.domain.vo.TicketExternalVerifyCodeVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.dromara.ticket.service.TicketExternalRegistrationService;
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
    private final TicketExternalRegistrationService externalRegistrationService;

    @PostMapping("/login-success")
    public R<Void> reportLoginSuccess(@Validated @RequestBody TicketExternalLoginReportBo bo) {
        return ticketOpsService.reportExternalLoginSuccess(bo);
    }

    @PostMapping("/login-req-data")
    public R<Void> submitLoginReqData(@Validated @RequestBody TicketExternalLoginReqDataBo bo) {
        return ticketOpsService.submitExternalLoginReqData(bo);
    }

    @GetMapping("/next-offline")
    public R<TicketExternalOfflineAccountVo> fetchNextOfflineAccount(
        @RequestParam @NotBlank(message = "platformCode不能为空") String platformCode) {
        return ticketOpsService.fetchNextOfflineAccount(platformCode);
    }

    @GetMapping("/verifyCode")
    public R<TicketExternalVerifyCodeVo> verifyCode(
        @RequestParam @NotBlank(message = "platformCode不能为空") String platformCode,  @RequestParam @NotBlank(message = "email不能为空") String email) {
        return ticketOpsService.verifyCode(platformCode, email);
    }

    @GetMapping("/email-verify-code")
    public R<TicketExternalVerifyCodeVo> emailVerifyCode(
        @RequestParam @NotBlank(message = "platformCode不能为空") String platformCode,
        @RequestParam @NotBlank(message = "email不能为空") String email) {
        return ticketOpsService.emailVerifyCode(platformCode, email);
    }

    @GetMapping("/email-activation-link")
    public R<TicketExternalVerifyCodeVo> emailActivationLink(
        @RequestParam @NotBlank(message = "platformCode不能为空") String platformCode,
        @RequestParam @NotBlank(message = "email不能为空") String email) {
        return ticketOpsService.emailActivationLink(platformCode, email);
    }

    @PostMapping("/next-register")
    public R<TicketExternalRegisterAccountVo> nextRegister(
        @RequestParam @NotBlank(message = "platformCode不能为空") String platformCode) {
        return externalRegistrationService.nextRegister(platformCode);
    }

    @PostMapping("/register-confirm")
    public R<Void> confirmRegister(@Validated @RequestBody TicketExternalRegistrationConfirmBo bo) {
        return externalRegistrationService.confirmRegister(bo);
    }

    @GetMapping("/phone-activation-code")
    public R<TicketExternalSmsCodeVo> phoneActivationCode(
        @RequestParam @NotBlank(message = "platformCode不能为空") String platformCode,
        @RequestParam @NotBlank(message = "email不能为空") String email) {
        return externalRegistrationService.getPhoneActivationCode(platformCode, email);
    }

    @PostMapping("/activate-confirm")
    public R<Void> confirmActivate(@Validated @RequestBody TicketExternalActivationConfirmBo bo) {
        return externalRegistrationService.confirmActivate(bo);
    }
}
