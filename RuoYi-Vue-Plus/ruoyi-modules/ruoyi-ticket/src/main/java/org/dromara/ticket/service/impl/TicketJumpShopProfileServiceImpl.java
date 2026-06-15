package org.dromara.ticket.service.impl;

import cn.hutool.core.util.ObjectUtil;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.domain.TicketJumpShopProfile;
import org.dromara.ticket.domain.bo.TicketJumpShopProfileBo;
import org.dromara.ticket.domain.bo.TicketJumpShopProfileQueryBo;
import org.dromara.ticket.domain.vo.TicketJumpShopProfileVo;
import org.dromara.ticket.mapper.TicketJumpShopProfileMapper;
import org.dromara.ticket.service.ITicketJumpShopProfileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketJumpShopProfileServiceImpl implements ITicketJumpShopProfileService {

    private final TicketJumpShopProfileMapper profileMapper;

    @Override
    public TableDataInfo<TicketJumpShopProfileVo> selectPage(TicketJumpShopProfileQueryBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketJumpShopProfile> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StringUtils.isNotBlank(bo.getProfileName()), TicketJumpShopProfile::getProfileName, bo.getProfileName())
            .eq(ObjectUtil.isNotNull(bo.getEnabled()), TicketJumpShopProfile::getEnabled, bo.getEnabled())
            .orderByDesc(TicketJumpShopProfile::getProfileId);
        Page<TicketJumpShopProfile> page = profileMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toVo).toList(), page.getTotal());
    }

    @Override
    public TicketJumpShopProfileVo selectById(Long profileId) {
        return toVo(requireById(profileId));
    }

    @Override
    public TicketJumpShopProfile requireById(Long profileId) {
        TicketJumpShopProfile profile = profileMapper.selectById(profileId);
        if (profile == null) {
            throw new ServiceException("Jump Shop 资料不存在");
        }
        return profile;
    }

    @Override
    public List<TicketJumpShopProfileVo> selectEnabledOptions() {
        return profileMapper.selectList(Wrappers.lambdaQuery(TicketJumpShopProfile.class)
            .eq(TicketJumpShopProfile::getEnabled, Boolean.TRUE)
            .orderByDesc(TicketJumpShopProfile::getProfileId)).stream().map(this::toVo).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveProfile(TicketJumpShopProfileBo bo) {
        TicketJumpShopProfile entity = MapstructUtils.convert(bo, TicketJumpShopProfile.class);
        normalizeEntity(entity);
        if (StringUtils.isBlank(entity.getCardNumber()) || StringUtils.isBlank(entity.getCvv())) {
            throw new ServiceException("信用卡号和 CVV 不能为空");
        }
        return profileMapper.insert(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateProfile(TicketJumpShopProfileBo bo) {
        if (bo.getProfileId() == null) {
            throw new ServiceException("资料主键不能为空");
        }
        TicketJumpShopProfile existing = requireById(bo.getProfileId());
        TicketJumpShopProfile entity = MapstructUtils.convert(bo, TicketJumpShopProfile.class);
        preserveSensitiveFields(existing, entity);
        normalizeEntity(entity);
        return profileMapper.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeProfiles(Long[] profileIds) {
        return profileMapper.deleteByIds(Arrays.asList(profileIds));
    }

    private void normalizeEntity(TicketJumpShopProfile entity) {
        if (entity == null) {
            return;
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(Boolean.TRUE);
        }
        if (entity.getBillingSameAsShipping() == null) {
            entity.setBillingSameAsShipping(Boolean.TRUE);
        }
        if (Boolean.TRUE.equals(entity.getBillingSameAsShipping())) {
            entity.setBillingLastName(entity.getLastName());
            entity.setBillingFirstName(entity.getFirstName());
            entity.setBillingPhone(entity.getPhone());
            entity.setBillingPostalCode(entity.getPostalCode());
            entity.setBillingProvince(entity.getProvince());
            entity.setBillingCity(entity.getCity());
            entity.setBillingAddress1(entity.getAddress1());
            entity.setBillingAddress2(entity.getAddress2());
            entity.setBillingCountryCode(entity.getCountryCode());
        }
        if (StringUtils.isBlank(entity.getCountryCode())) {
            entity.setCountryCode("JP");
        }
        if (StringUtils.isBlank(entity.getBillingCountryCode())) {
            entity.setBillingCountryCode(entity.getCountryCode());
        }
    }

    private void preserveSensitiveFields(TicketJumpShopProfile existing, TicketJumpShopProfile entity) {
        if (StringUtils.isBlank(entity.getCardNumber())) {
            entity.setCardNumber(existing.getCardNumber());
        }
        if (StringUtils.isBlank(entity.getCvv())) {
            entity.setCvv(existing.getCvv());
        }
        if (StringUtils.isBlank(entity.getIssueNumber())) {
            entity.setIssueNumber(existing.getIssueNumber());
        }
    }

    private TicketJumpShopProfileVo toVo(TicketJumpShopProfile entity) {
        TicketJumpShopProfileVo vo = MapstructUtils.convert(entity, TicketJumpShopProfileVo.class);
        if (vo == null) {
            return null;
        }
        vo.setCardNumberMasked(maskTail(entity.getCardNumber(), 4));
        vo.setCardNumberConfigured(StringUtils.isNotBlank(entity.getCardNumber()));
        vo.setCvvConfigured(StringUtils.isNotBlank(entity.getCvv()));
        vo.setIssueNumberMasked(maskTail(entity.getIssueNumber(), 2));
        return vo;
    }

    private String maskTail(String value, int visibleTailLength) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        String normalized = value.trim();
        int keep = Math.max(Math.min(visibleTailLength, normalized.length()), 0);
        String suffix = keep == 0 ? "" : normalized.substring(normalized.length() - keep);
        return "*".repeat(Math.max(normalized.length() - keep, 0)) + suffix;
    }
}
