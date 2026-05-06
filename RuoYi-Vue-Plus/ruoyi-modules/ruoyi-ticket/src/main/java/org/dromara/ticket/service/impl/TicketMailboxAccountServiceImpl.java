package org.dromara.ticket.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.config.TicketStalwartProperties;
import org.dromara.ticket.config.TicketMailReaderProperties;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketMailboxAccount;
import org.dromara.ticket.domain.bo.TicketMailboxAccountBo;
import org.dromara.ticket.domain.bo.TicketMailboxBatchCreateBo;
import org.dromara.ticket.domain.bo.TicketMailboxMailSyncBo;
import org.dromara.ticket.domain.bo.TicketMailboxStatusBo;
import org.dromara.ticket.domain.vo.TicketMailboxAccountVo;
import org.dromara.ticket.domain.vo.TicketMailboxBatchCreateResultVo;
import org.dromara.ticket.mapper.TicketManagedAccountMapper;
import org.dromara.ticket.mapper.TicketMailboxAccountMapper;
import org.dromara.ticket.service.ITicketMailboxAccountService;
import org.dromara.ticket.service.TicketMailReaderService;
import org.dromara.ticket.service.TicketStalwartClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TicketMailboxAccountServiceImpl implements ITicketMailboxAccountService {

    private static final char[] EMAIL_NAME_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int EMAIL_NAME_MIN_LENGTH = 6;
    private static final int EMAIL_NAME_MAX_LENGTH = 10;
    private static final Set<String> SWITCHABLE_STATUSES = Set.of("available", "disabled");

    private final TicketMailboxAccountMapper mailboxMapper;
    private final TicketManagedAccountMapper accountMapper;
    private final TicketStalwartClient stalwartClient;
    private final TicketStalwartProperties stalwartProperties;
    private final TicketMailReaderProperties mailReaderProperties;
    private final TicketMailReaderService mailReaderService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public TableDataInfo<TicketMailboxAccountVo> selectMailboxPage(TicketMailboxAccountBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketMailboxAccount> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(bo.getEmail()), TicketMailboxAccount::getEmail, bo.getEmail())
            .eq(StrUtil.isNotBlank(bo.getStatus()), TicketMailboxAccount::getStatus, bo.getStatus())
            .orderByDesc(TicketMailboxAccount::getMailboxId);
        Page<TicketMailboxAccountVo> page = mailboxMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichUsedAccounts(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public TicketMailboxBatchCreateResultVo batchCreate(TicketMailboxBatchCreateBo bo) {
        int requestedCount = bo.getCount() == null ? 0 : bo.getCount();
        if (requestedCount <= 0 || requestedCount > 500) {
            throw new ServiceException("创建数量必须在 1-500 之间");
        }

        TicketMailboxBatchCreateResultVo result = new TicketMailboxBatchCreateResultVo();
        result.setRequestedCount(requestedCount);

        int maxAttempts = requestedCount * Math.max(1, stalwartProperties.getMaxCreateAttemptFactor());
        while (result.getSuccessCount() < requestedCount && result.getAttemptCount() < maxAttempts) {
            result.setAttemptCount(result.getAttemptCount() + 1);
            String email = generateEmail();
            if (existsEmail(email)) {
                continue;
            }

            try {
                createMailbox(email);

                result.setSuccessCount(result.getSuccessCount() + 1);
                result.getCreatedEmails().add(email);
            } catch (Exception ex) {
                String message = email + " 创建失败：" + ex.getMessage();
                log.warn("create mailbox account failed, email={}", email, ex);
                result.getFailedMessages().add(message);
            }
        }

        result.setFailedCount(result.getFailedMessages().size());
        if (result.getSuccessCount() < requestedCount) {
            result.getFailedMessages().add("达到最大尝试次数，仍缺少 " + (requestedCount - result.getSuccessCount()) + " 个邮箱");
        }
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public TicketMailboxAccount createAvailableMailbox() {
        int maxAttempts = Math.max(1, stalwartProperties.getMaxCreateAttemptFactor());
        Exception lastException = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String email = generateEmail();
            if (existsEmail(email)) {
                continue;
            }
            try {
                return createMailbox(email);
            } catch (Exception ex) {
                lastException = ex;
                log.warn("create single mailbox account failed, email={}", email, ex);
            }
        }
        String message = lastException == null ? "邮箱账号创建失败，请稍后重试" : lastException.getMessage();
        throw new ServiceException("邮箱账号池无可用邮箱，自动创建失败：" + message);
    }

    @Override
    public boolean changeStatus(TicketMailboxStatusBo bo) {
        if (CollUtil.isEmpty(bo.getMailboxIds())) {
            throw new ServiceException("请选择邮箱账号");
        }
        if (!SWITCHABLE_STATUSES.contains(bo.getStatus())) {
            throw new ServiceException("邮箱账号仅支持启用和禁用");
        }

        List<TicketMailboxAccount> mailboxes = mailboxMapper.selectByIds(CollUtil.distinct(bo.getMailboxIds()));
        if (CollUtil.isEmpty(mailboxes)) {
            throw new ServiceException("邮箱账号不存在");
        }
        boolean hasUnsupported = mailboxes.stream().anyMatch(item -> !SWITCHABLE_STATUSES.contains(item.getStatus()));
        if (hasUnsupported) {
            throw new ServiceException("已使用或异常邮箱不支持直接切换状态");
        }

        int rows = mailboxMapper.update(null, new LambdaUpdateWrapper<TicketMailboxAccount>()
            .set(TicketMailboxAccount::getStatus, bo.getStatus())
            .set(TicketMailboxAccount::getLastError, null)
            .in(TicketMailboxAccount::getMailboxId, mailboxes.stream().map(TicketMailboxAccount::getMailboxId).toList()));
        return rows > 0;
    }

    @Override
    public boolean syncLatestMail(Long mailboxId) {
        TicketMailboxAccount mailbox = mailboxMapper.selectById(mailboxId);
        if (mailbox == null) {
            throw new ServiceException("邮箱账号不存在");
        }
        syncLatestMailInternal(mailbox, true);
        return true;
    }

    @Override
    public boolean syncLatestMail(TicketMailboxMailSyncBo bo) {
        if (CollUtil.isEmpty(bo.getMailboxIds())) {
            throw new ServiceException("请选择邮箱账号");
        }
        List<TicketMailboxAccount> mailboxes = mailboxMapper.selectByIds(CollUtil.distinct(bo.getMailboxIds()));
        if (CollUtil.isEmpty(mailboxes)) {
            throw new ServiceException("邮箱账号不存在");
        }
        for (TicketMailboxAccount mailbox : mailboxes) {
            syncLatestMailInternal(mailbox, false);
        }
        return true;
    }

    @Scheduled(
        initialDelayString = "${ticket.mail-reader.sync-fixed-delay-ms:120000}",
        fixedDelayString = "${ticket.mail-reader.sync-fixed-delay-ms:120000}"
    )
    public void autoSyncLatestMail() {
        if (!mailReaderProperties.isEnabled() || !mailReaderProperties.isAutoSyncEnabled()) {
            return;
        }
        int batchSize = Math.max(1, mailReaderProperties.getSyncBatchSize());
        List<TicketMailboxAccount> mailboxes = mailboxMapper.selectList(new LambdaQueryWrapper<TicketMailboxAccount>()
            .in(TicketMailboxAccount::getStatus, List.of("available", "used"))
            .orderByAsc(TicketMailboxAccount::getLastMailSyncTime)
            .orderByAsc(TicketMailboxAccount::getMailboxId)
            .last("LIMIT " + batchSize));
        for (TicketMailboxAccount mailbox : mailboxes) {
            syncLatestMailInternal(mailbox, false);
        }
    }

    private String generateEmail() {
        int length = EMAIL_NAME_MIN_LENGTH + secureRandom.nextInt(EMAIL_NAME_MAX_LENGTH - EMAIL_NAME_MIN_LENGTH + 1);
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(EMAIL_NAME_CHARS[secureRandom.nextInt(EMAIL_NAME_CHARS.length)]);
        }
        return builder + "@" + StrUtil.blankToDefault(stalwartProperties.getDomain(), "gjcytech.com");
    }

    private String localPart(String email) {
        return StrUtil.subBefore(email, "@", false);
    }

    private boolean existsEmail(String email) {
        Long count = mailboxMapper.selectCount(new LambdaQueryWrapper<TicketMailboxAccount>()
            .eq(TicketMailboxAccount::getEmail, email));
        return count != null && count > 0;
    }

    private TicketMailboxAccount createMailbox(String email) {
        String username = localPart(email);
        TicketStalwartClient.CreatePrincipalResult createResult = stalwartClient.createMailboxAccount(username, email);
        TicketMailboxAccount mailbox = new TicketMailboxAccount();
        mailbox.setEmail(email);
        mailbox.setUsername(username);
        mailbox.setPassword(email);
        mailbox.setDomain(stalwartProperties.getDomain());
        mailbox.setProvider("stalwart");
        mailbox.setStalwartPrincipalId(createResult.getPrincipalId());
        mailbox.setStatus("available");
        mailbox.setLastError(null);
        mailboxMapper.insert(mailbox);
        return mailbox;
    }

    private void syncLatestMailInternal(TicketMailboxAccount mailbox, boolean rethrow) {
        Date now = new Date();
        try {
            TicketMailReaderService.MailReadResult result = mailReaderService.readLatestForMailbox(
                mailbox.getUsername(), mailbox.getPassword());
            mailboxMapper.update(null, Wrappers.<TicketMailboxAccount>lambdaUpdate()
                .eq(TicketMailboxAccount::getMailboxId, mailbox.getMailboxId())
                .set(TicketMailboxAccount::getLatestMailSubject, result.getSubject())
                .set(TicketMailboxAccount::getLatestMailFrom, result.getFromAddress())
                .set(TicketMailboxAccount::getLatestMailReceivedAt, result.getReceivedAt())
                .set(TicketMailboxAccount::getLatestMailMessageId, result.getMessageId())
                .set(TicketMailboxAccount::getLatestMailExcerpt, result.getBodyExcerpt())
                .set(TicketMailboxAccount::getLatestVerifyCode, result.getVerifyCode())
                .set(TicketMailboxAccount::getLatestActivationUrl, result.getActivationUrl())
                .set(TicketMailboxAccount::getLastMailSyncTime, now)
                .set(TicketMailboxAccount::getLastMailSyncError, null));
        } catch (Exception ex) {
            String message = StrUtil.maxLength(StrUtil.blankToDefault(ex.getMessage(), "同步失败"), 1000);
            log.warn("sync latest mailbox mail failed, mailboxId={}, email={}",
                mailbox.getMailboxId(), mailbox.getEmail(), ex);
            mailboxMapper.update(null, Wrappers.<TicketMailboxAccount>lambdaUpdate()
                .eq(TicketMailboxAccount::getMailboxId, mailbox.getMailboxId())
                .set(TicketMailboxAccount::getLastMailSyncTime, now)
                .set(TicketMailboxAccount::getLastMailSyncError, message));
            if (rethrow) {
                throw new ServiceException(message);
            }
        }
    }

    private void enrichUsedAccounts(List<TicketMailboxAccountVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        List<Long> accountIds = rows.stream()
            .map(TicketMailboxAccountVo::getUsedAccountId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (CollUtil.isEmpty(accountIds)) {
            return;
        }
        Map<Long, TicketManagedAccount> accountMap = accountMapper.selectByIds(accountIds).stream()
            .collect(Collectors.toMap(TicketManagedAccount::getAccountId, Function.identity(), (left, right) -> right));
        for (TicketMailboxAccountVo row : rows) {
            TicketManagedAccount account = accountMap.get(row.getUsedAccountId());
            if (account != null) {
                row.setUsedAccountEmail(account.getEmail());
            }
        }
    }
}
