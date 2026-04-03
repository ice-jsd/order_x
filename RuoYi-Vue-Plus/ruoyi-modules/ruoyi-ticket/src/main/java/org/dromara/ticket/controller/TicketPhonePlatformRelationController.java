package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.domain.bo.TicketPhonePlatformRelationBo;
import org.dromara.ticket.domain.vo.TicketPhonePlatformRelationVo;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/phone-platform-relation")
public class TicketPhonePlatformRelationController {

    private final ITicketOpsService ticketOpsService;

    @SaCheckPermission("ticket:relation:list")
    @GetMapping("/list")
    public TableDataInfo<TicketPhonePlatformRelationVo> list(TicketPhonePlatformRelationBo bo, PageQuery pageQuery) {
        return ticketOpsService.selectRelationPage(bo, pageQuery);
    }
}
