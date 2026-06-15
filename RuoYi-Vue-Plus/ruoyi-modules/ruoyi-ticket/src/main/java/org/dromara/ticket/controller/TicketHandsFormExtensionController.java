package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.config.TicketHandsFormExtensionProperties;
import org.dromara.ticket.domain.bo.TicketHandsFormExtensionClaimBo;
import org.dromara.ticket.domain.bo.TicketHandsFormExtensionHeartbeatBo;
import org.dromara.ticket.domain.bo.TicketHandsFormExtensionReportBo;
import org.dromara.ticket.domain.vo.TicketHandsFormExtensionTaskVo;
import org.dromara.ticket.service.TicketHandsFormExtensionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SaIgnore
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/hands-form-extension")
public class TicketHandsFormExtensionController {

    private static final String SECRET_HEADER = "X-Hands-Extension-Secret";

    private final TicketHandsFormExtensionProperties properties;
    private final TicketHandsFormExtensionService handsFormExtensionService;

    @PostMapping("/claim")
    public R<TicketHandsFormExtensionTaskVo> claim(
        @RequestHeader(value = SECRET_HEADER, required = false) String secret,
        @Valid @RequestBody TicketHandsFormExtensionClaimBo bo
    ) {
        verifySecret(secret);
        return R.ok(handsFormExtensionService.claimNextHandsFormExtensionTask(bo));
    }

    @PostMapping("/heartbeat")
    public R<Void> heartbeat(
        @RequestHeader(value = SECRET_HEADER, required = false) String secret,
        @Valid @RequestBody TicketHandsFormExtensionHeartbeatBo bo
    ) {
        verifySecret(secret);
        return handsFormExtensionService.heartbeatHandsFormExtensionTask(bo);
    }

    @PostMapping("/report")
    public R<Void> report(
        @RequestHeader(value = SECRET_HEADER, required = false) String secret,
        @Valid @RequestBody TicketHandsFormExtensionReportBo bo
    ) {
        verifySecret(secret);
        return handsFormExtensionService.reportHandsFormExtensionTask(bo);
    }

    private void verifySecret(String secret) {
        if (!properties.isEnabled()) {
            throw new ServiceException("Hands Form 扩展执行未启用");
        }
        if (StringUtils.isBlank(properties.getApiSecret())) {
            throw new ServiceException("Hands Form 扩展密钥未配置");
        }
        if (!properties.getApiSecret().equals(secret)) {
            throw new ServiceException("Hands Form 扩展密钥无效");
        }
    }
}
