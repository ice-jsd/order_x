package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import lombok.RequiredArgsConstructor;
import org.dromara.ticket.service.TicketMockPlatformService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SaIgnore
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/mock-platform")
public class TicketMockPlatformController {

    private final TicketMockPlatformService ticketMockPlatformService;

    @PostMapping("/order/{platformCode}")
    public Map<String, Object> acceptOrderStep(@PathVariable String platformCode, @RequestBody Map<String, Object> payload) {
        return ticketMockPlatformService.acceptStep(platformCode, payload);
    }
}
