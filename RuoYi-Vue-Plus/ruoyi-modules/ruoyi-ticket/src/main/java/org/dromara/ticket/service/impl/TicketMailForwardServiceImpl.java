package org.dromara.ticket.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.ticket.config.TicketMailForwardProperties;
import org.dromara.ticket.domain.TicketMailForwardRecord;
import org.dromara.ticket.domain.TicketMailRecord;
import org.dromara.ticket.domain.bo.TicketMailForwardRecordBo;
import org.dromara.ticket.domain.bo.TicketMailForwardSendBo;
import org.dromara.ticket.domain.vo.TicketMailForwardRecordVo;
import org.dromara.ticket.mapper.TicketMailForwardRecordMapper;
import org.dromara.ticket.mapper.TicketMailRecordMapper;
import org.dromara.ticket.service.ITicketMailForwardService;
import org.dromara.ticket.service.TicketMailForwardSender;
import org.springframework.stereotype.Service;

import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketMailForwardServiceImpl implements ITicketMailForwardService {

    private static final String UNKNOWN_SUBJECT = "未知邮件";

    private final TicketMailRecordMapper mailRecordMapper;
    private final TicketMailForwardRecordMapper mailForwardRecordMapper;
    private final TicketMailForwardSender mailForwardSender;
    private final TicketMailForwardProperties properties;

    @Override
    public TableDataInfo<TicketMailForwardRecordVo> selectPage(TicketMailForwardRecordBo bo, PageQuery pageQuery) {
        String sourceEmail = StrUtil.trim(bo.getSourceEmail());
        String targetEmail = StrUtil.trim(bo.getTargetEmail());
        String sendStatus = StrUtil.trim(bo.getSendStatus());
        String keyword = StrUtil.trim(bo.getKeyword());
        LambdaQueryWrapper<TicketMailForwardRecord> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(sourceEmail), TicketMailForwardRecord::getSourceEmail, sourceEmail)
            .like(StrUtil.isNotBlank(targetEmail), TicketMailForwardRecord::getTargetEmail, targetEmail)
            .eq(StrUtil.isNotBlank(sendStatus), TicketMailForwardRecord::getSendStatus, sendStatus)
            .and(StrUtil.isNotBlank(keyword), q -> q
                .like(TicketMailForwardRecord::getMailSubject, keyword)
                .or()
                .like(TicketMailForwardRecord::getForwardSubject, keyword)
                .or()
                .like(TicketMailForwardRecord::getForwardContent, keyword))
            .orderByDesc(TicketMailForwardRecord::getSentAt)
            .orderByDesc(TicketMailForwardRecord::getForwardId);
        Page<TicketMailForwardRecordVo> page = mailForwardRecordMapper.selectVoPage(pageQuery.build(), wrapper);
        return TableDataInfo.build(page);
    }

    @Override
    public boolean send(TicketMailForwardSendBo bo) {
        String targetEmail = StrUtil.trim(bo.getTargetEmail());
        TicketMailForwardRecord forwardRecord = new TicketMailForwardRecord();
        forwardRecord.setRecordId(bo.getRecordId());
        forwardRecord.setTargetEmail(targetEmail);
        forwardRecord.setSenderFrom(properties.getFrom());
        forwardRecord.setOperatorName(LoginHelper.getUsername());

        try {
            if (!properties.isEnabled()) {
                throw new ServiceException("邮件转发功能未启用");
            }

            TicketMailRecord source = mailRecordMapper.selectById(bo.getRecordId());
            if (source == null) {
                throw new ServiceException("邮件记录不存在");
            }

            populateSourceFields(forwardRecord, source);
            String forwardSubject = buildForwardSubject(source.getSubject());
            String forwardContent = buildForwardContent(source);
            forwardRecord.setForwardSubject(forwardSubject);
            forwardRecord.setForwardContent(forwardContent);

            String messageId = mailForwardSender.send(targetEmail, forwardSubject, forwardContent);
            forwardRecord.setForwardMessageId(messageId);
            forwardRecord.setSendStatus("success");
            forwardRecord.setErrorMessage(null);
            forwardRecord.setSentAt(new Date());
            mailForwardRecordMapper.insert(forwardRecord);
            return true;
        } catch (Exception ex) {
            ServiceException serviceException = toServiceException(ex);
            if (StrUtil.isBlank(forwardRecord.getForwardSubject())) {
                forwardRecord.setForwardSubject(buildForwardSubject(forwardRecord.getMailSubject()));
            }
            if (StrUtil.isBlank(forwardRecord.getForwardContent())) {
                forwardRecord.setForwardContent("暂无正文");
            }
            forwardRecord.setSendStatus("failed");
            forwardRecord.setErrorMessage(StrUtil.maxLength(serviceException.getMessage(), 2000));
            forwardRecord.setSentAt(new Date());
            try {
                mailForwardRecordMapper.insert(forwardRecord);
            } catch (Exception insertEx) {
                log.error("save mail forward failure record failed, recordId={}, targetEmail={}", bo.getRecordId(), targetEmail, insertEx);
                serviceException.addSuppressed(insertEx);
            }
            throw serviceException;
        }
    }

    private void populateSourceFields(TicketMailForwardRecord forwardRecord, TicketMailRecord source) {
        forwardRecord.setMailboxId(source.getMailboxId());
        forwardRecord.setSourceEmail(source.getEmail());
        forwardRecord.setSourceMessageId(source.getMessageId());
        forwardRecord.setMailSubject(StrUtil.blankToDefault(source.getSubject(), "无标题邮件"));
    }

    private String buildForwardSubject(String subject) {
        return "转发：" + StrUtil.blankToDefault(StrUtil.trim(subject), UNKNOWN_SUBJECT);
    }

    private String buildForwardContent(TicketMailRecord source) {
        return String.join("\n",
            "这是一封来自 OrderX 邮件总览的结构化转发邮件。",
            "",
            "原邮箱：" + StrUtil.blankToDefault(source.getEmail(), "-"),
            "原发件人：" + StrUtil.blankToDefault(source.getFromAddress(), "-"),
            "原收件时间：" + formatDateTime(source.getReceivedAt()),
            "原邮件类型：" + getParseLabel(source.getParseType()),
            "原主题：" + StrUtil.blankToDefault(source.getSubject(), "无标题邮件"),
            "原 Message-ID：" + StrUtil.blankToDefault(source.getMessageId(), "-"),
            "",
            "----- 原文开始 -----",
            resolveBodyContent(source),
            "----- 原文结束 -----");
    }

    private String resolveBodyContent(TicketMailRecord source) {
        String body = StrUtil.trimToNull(source.getBodyContent());
        if (body != null) {
            return body;
        }
        body = StrUtil.trimToNull(source.getBodyExcerpt());
        return body == null ? "暂无正文" : body;
    }

    private String formatDateTime(Date date) {
        return date == null ? "-" : DateUtil.formatDateTime(date);
    }

    private String getParseLabel(String parseType) {
        return switch (StrUtil.blankToDefault(parseType, "")) {
            case "verify_code" -> "验证码";
            case "activation_url" -> "激活邮件";
            case "lottery_applied" -> "抽选申请邮件";
            case "lottery_selected" -> "当选邮件";
            case "lottery_rejected" -> "落选邮件";
            case "unknown" -> "其他邮件";
            default -> "未解析";
        };
    }

    private ServiceException toServiceException(Exception ex) {
        if (ex instanceof ServiceException serviceException) {
            return serviceException;
        }
        return new ServiceException(StrUtil.blankToDefault(ex.getMessage(), "邮件转发失败"));
    }
}
