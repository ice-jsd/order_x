package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.vo.TicketJumpShopProductInfoVo;
import org.dromara.ticket.mapper.TicketPlatformConfigMapper;
import org.dromara.ticket.service.TicketPythonExecutorClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/jump-shop")
public class TicketJumpShopController {

    private final TicketPlatformConfigMapper platformMapper;
    private final TicketPythonExecutorClient ticketPythonExecutorClient;

    @SaCheckPermission("ticket:saleTask:add")
    @GetMapping("/product-info")
    public R<TicketJumpShopProductInfoVo> productInfo(@RequestParam Long platformId, @RequestParam String productUrl) {
        if (StringUtils.isBlank(productUrl)) {
            throw new ServiceException("商品链接不能为空");
        }
        TicketPlatformConfig platform = platformMapper.selectById(platformId);
        if (platform == null) {
            throw new ServiceException("平台不存在");
        }
        String adapterType = StringUtils.defaultIfBlank(platform.getAdapterType(), platform.getPlatformCode());
        if (!"jump-shop-online".equalsIgnoreCase(adapterType) && !"jump-shop".equalsIgnoreCase(platform.getPlatformCode())) {
            throw new ServiceException("当前平台不是 Jump Shop");
        }
        return R.ok(ticketPythonExecutorClient.fetchJumpShopProductInfo(productUrl));
    }
}
