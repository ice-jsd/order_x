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
import org.dromara.ticket.domain.bo.TicketJumpShopProfileBo;
import org.dromara.ticket.domain.bo.TicketJumpShopProfileQueryBo;
import org.dromara.ticket.domain.vo.TicketJumpShopProfileVo;
import org.dromara.ticket.service.ITicketJumpShopProfileService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/jump-shop-profile")
public class TicketJumpShopProfileController extends BaseController {

    private final ITicketJumpShopProfileService jumpShopProfileService;

    @SaCheckPermission("ticket:jumpShopProfile:list")
    @GetMapping("/list")
    public TableDataInfo<TicketJumpShopProfileVo> list(TicketJumpShopProfileQueryBo bo, PageQuery pageQuery) {
        return jumpShopProfileService.selectPage(bo, pageQuery);
    }

    @SaCheckPermission("ticket:jumpShopProfile:list")
    @GetMapping("/options")
    public R<List<TicketJumpShopProfileVo>> options() {
        return R.ok(jumpShopProfileService.selectEnabledOptions());
    }

    @SaCheckPermission("ticket:jumpShopProfile:query")
    @GetMapping("/{profileId}")
    public R<TicketJumpShopProfileVo> getInfo(@PathVariable Long profileId) {
        return R.ok(jumpShopProfileService.selectById(profileId));
    }

    @SaCheckPermission("ticket:jumpShopProfile:add")
    @Log(title = "Jump Shop 资料", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@Validated @RequestBody TicketJumpShopProfileBo bo) {
        return toAjax(jumpShopProfileService.saveProfile(bo));
    }

    @SaCheckPermission("ticket:jumpShopProfile:edit")
    @Log(title = "Jump Shop 资料", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<Void> edit(@Validated @RequestBody TicketJumpShopProfileBo bo) {
        return toAjax(jumpShopProfileService.updateProfile(bo));
    }

    @SaCheckPermission("ticket:jumpShopProfile:remove")
    @Log(title = "Jump Shop 资料", businessType = BusinessType.DELETE)
    @DeleteMapping("/{profileIds}")
    public R<Void> remove(@PathVariable Long[] profileIds) {
        return toAjax(jumpShopProfileService.removeProfiles(profileIds));
    }
}
