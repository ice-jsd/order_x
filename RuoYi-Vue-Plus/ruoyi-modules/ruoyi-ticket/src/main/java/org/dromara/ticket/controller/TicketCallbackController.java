package org.dromara.ticket.controller;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/callback")
public class TicketCallbackController {

    private final ITicketOpsService ticketOpsService;

    @PostMapping("/{platformCode}")
    public R<String> callback(@PathVariable String platformCode, @RequestBody Map<String, Object> payload) {
        return ticketOpsService.handleCallback(platformCode, payload);
    }
}
