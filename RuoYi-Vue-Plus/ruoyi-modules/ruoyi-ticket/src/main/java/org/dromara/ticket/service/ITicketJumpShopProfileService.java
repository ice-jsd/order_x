package org.dromara.ticket.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.domain.TicketJumpShopProfile;
import org.dromara.ticket.domain.bo.TicketJumpShopProfileBo;
import org.dromara.ticket.domain.bo.TicketJumpShopProfileQueryBo;
import org.dromara.ticket.domain.vo.TicketJumpShopProfileVo;

import java.util.List;

public interface ITicketJumpShopProfileService {

    TableDataInfo<TicketJumpShopProfileVo> selectPage(TicketJumpShopProfileQueryBo bo, PageQuery pageQuery);

    TicketJumpShopProfileVo selectById(Long profileId);

    TicketJumpShopProfile requireById(Long profileId);

    List<TicketJumpShopProfileVo> selectEnabledOptions();

    int saveProfile(TicketJumpShopProfileBo bo);

    int updateProfile(TicketJumpShopProfileBo bo);

    int removeProfiles(Long[] profileIds);
}
