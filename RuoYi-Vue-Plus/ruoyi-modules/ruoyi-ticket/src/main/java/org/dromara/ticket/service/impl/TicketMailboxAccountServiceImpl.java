package org.dromara.ticket.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.ticket.config.TicketStalwartProperties;
import org.dromara.ticket.config.TicketMailReaderProperties;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketMailboxAccount;
import org.dromara.ticket.domain.TicketMailRecord;
import org.dromara.ticket.domain.TicketOrderExecution;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTaskSchedule;
import org.dromara.ticket.domain.bo.TicketMailboxAccountBo;
import org.dromara.ticket.domain.bo.TicketMailboxBatchCreateBo;
import org.dromara.ticket.domain.bo.TicketMailFeedBo;
import org.dromara.ticket.domain.bo.TicketMailboxMailSyncBo;
import org.dromara.ticket.domain.bo.TicketMailboxStatusBo;
import org.dromara.ticket.domain.vo.TicketMailboxAccountVo;
import org.dromara.ticket.domain.vo.TicketMailboxBatchCreateResultVo;
import org.dromara.ticket.domain.vo.TicketMailFeedAppliedExportVo;
import org.dromara.ticket.domain.vo.TicketMailFeedSelectedExportVo;
import org.dromara.ticket.domain.vo.TicketMailRecordVo;
import org.dromara.ticket.domain.vo.TicketMailRecordReparseResultVo;
import org.dromara.ticket.mapper.TicketManagedAccountMapper;
import org.dromara.ticket.mapper.TicketMailboxAccountMapper;
import org.dromara.ticket.mapper.TicketMailRecordMapper;
import org.dromara.ticket.mapper.TicketOrderExecutionMapper;
import org.dromara.ticket.mapper.TicketPlatformConfigMapper;
import org.dromara.ticket.mapper.TicketSaleTaskScheduleMapper;
import org.dromara.ticket.service.ITicketMailboxAccountService;
import org.dromara.ticket.service.TicketLotteryResultMailService;
import org.dromara.ticket.service.TicketMailReaderService;
import org.dromara.ticket.service.TicketStalwartClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TicketMailboxAccountServiceImpl implements ITicketMailboxAccountService {

    private static final char[] EMAIL_NAME_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int EMAIL_NAME_MIN_LENGTH = 6;
    private static final int EMAIL_NAME_MAX_LENGTH = 10;
    private static final int MAIL_REPARSE_BATCH_SIZE = 500;
    private static final Set<String> SWITCHABLE_STATUSES = Set.of("available", "disabled");
    private static final Set<String> EXPORTABLE_PARSE_TYPES = Set.of("lottery_applied", "lottery_selected", "purchase_completed");
    private static final String PARSE_TYPE_PURCHASE_COMPLETED = "purchase_completed";
    private static final String GLOBAL_MAIL_UID_CURSOR_KEY_PREFIX = "ticket:mail-reader:global:uid:";
    private static final Pattern EVENT_NAME_PATTERN = Pattern.compile(
        "イベント名\\s*[：:]\\s*(.+?)\\s*(?:会場|イベント開催日|イベントページURL|販売受付名|受付名|チケット名)\\s*[：:]");
    private static final Pattern PURCHASE_TICKET_NAME_PATTERN = Pattern.compile(
        "チケット名\\s*[：:]\\s*(.+?)\\s*(?:チケット枚数|申込番号|チケット料金|購入手数料|システム利用料|合計金額|支払方法)\\s*[：:]");

    private final TicketMailboxAccountMapper mailboxMapper;
    private final TicketManagedAccountMapper accountMapper;
    private final TicketMailRecordMapper mailRecordMapper;
    private final TicketPlatformConfigMapper platformConfigMapper;
    private final TicketOrderExecutionMapper orderExecutionMapper;
    private final TicketSaleTaskScheduleMapper saleTaskScheduleMapper;
    private final TicketStalwartClient stalwartClient;
    private final TicketStalwartProperties stalwartProperties;
    private final TicketMailReaderProperties mailReaderProperties;
    private final TicketMailReaderService mailReaderService;
    private final TicketLotteryResultMailService lotteryResultMailService;
    @Qualifier("scheduledExecutorService")
    private final ScheduledExecutorService scheduledExecutorService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicBoolean reparseRunning = new AtomicBoolean(false);
    private final AtomicReference<TicketMailRecordReparseResultVo> lastReparseSummary = new AtomicReference<>();

    @Override
    public TableDataInfo<TicketMailboxAccountVo> selectMailboxPage(TicketMailboxAccountBo bo, PageQuery pageQuery) {
        String mailKeyword = StrUtil.trim(bo.getMailKeyword());
        LambdaQueryWrapper<TicketMailboxAccount> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(bo.getEmail()), TicketMailboxAccount::getEmail, bo.getEmail())
            .eq(StrUtil.isNotBlank(bo.getStatus()), TicketMailboxAccount::getStatus, bo.getStatus())
            .apply(StrUtil.isNotBlank(mailKeyword),
                "EXISTS (SELECT 1 FROM ticket_mail_record mr " +
                    "WHERE mr.mailbox_id = ticket_mailbox_account.mailbox_id " +
                    "AND mr.tenant_id = ticket_mailbox_account.tenant_id " +
                    "AND mr.del_flag = 0 " +
                    "AND (mr.subject LIKE CONCAT('%',{0},'%') OR mr.body_content LIKE CONCAT('%',{0},'%')))",
                mailKeyword)
            .orderByDesc(TicketMailboxAccount::getMailboxId);
        Page<TicketMailboxAccountVo> page = mailboxMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichUsedAccounts(page.getRecords());
        enrichMatchedMailRecords(page.getRecords(), mailKeyword);
        return TableDataInfo.build(page);
    }

    @Override
    public TableDataInfo<TicketMailRecordVo> selectMailRecordPage(Long mailboxId, PageQuery pageQuery) {
        TicketMailboxAccount mailbox = mailboxMapper.selectById(mailboxId);
        if (mailbox == null) {
            throw new ServiceException("邮箱账号不存在");
        }
        Page<TicketMailRecordVo> page = mailRecordMapper.selectVoPage(
            pageQuery.build(),
            Wrappers.<TicketMailRecord>lambdaQuery()
                .eq(TicketMailRecord::getMailboxId, mailboxId)
                .orderByDesc(TicketMailRecord::getReceivedAt)
                .orderByDesc(TicketMailRecord::getRecordId)
        );
        return TableDataInfo.build(page);
    }

    @Override
    public TableDataInfo<TicketMailRecordVo> selectMailFeedPage(TicketMailFeedBo bo, PageQuery pageQuery) {
        String mode = normalizeMailFeedMode(bo.getMode());
        List<Long> platformAccountIds = loadPlatformAccountIds(bo.getPlatformId());
        if (bo.getPlatformId() != null && CollUtil.isEmpty(platformAccountIds)) {
            return TableDataInfo.build(pageQuery.build());
        }
        LambdaQueryWrapper<TicketMailRecord> wrapper = buildMailFeedWrapper(bo, platformAccountIds);
        if ("latest".equalsIgnoreCase(mode)) {
            applyLatestPerMailboxFilter(wrapper, bo, platformAccountIds);
        }
        wrapper.orderByDesc(TicketMailRecord::getReceivedAt)
            .orderByDesc(TicketMailRecord::getRecordId);
        Page<TicketMailRecordVo> page = mailRecordMapper.selectVoPage(pageQuery.build(), wrapper);
        return TableDataInfo.build(page);
    }

    @Override
    public void exportMailFeed(TicketMailFeedBo bo, HttpServletResponse response) {
        String parseType = StrUtil.trim(bo.getParseType());
        if (!EXPORTABLE_PARSE_TYPES.contains(parseType)) {
            throw new ServiceException("仅支持导出申请邮件、当选邮件和购入邮件");
        }
        List<Long> platformAccountIds = loadPlatformAccountIds(bo.getPlatformId());
        List<TicketMailRecord> records = selectMailFeedRecords(bo, platformAccountIds);
        if ("lottery_selected".equals(parseType)) {
            List<TicketMailFeedSelectedExportVo> rows = buildSelectedExportRows(records);
            ExcelUtil.exportExcel(rows, "邮件总览-当选", TicketMailFeedSelectedExportVo.class, response);
            return;
        }
        if (PARSE_TYPE_PURCHASE_COMPLETED.equals(parseType)) {
            List<TicketMailFeedSelectedExportVo> rows = buildSelectedExportRows(records);
            ExcelUtil.exportExcel(rows, "邮件总览-购入", TicketMailFeedSelectedExportVo.class, response);
            return;
        }
        List<TicketMailFeedAppliedExportVo> rows = buildAppliedExportRows(records);
        ExcelUtil.exportExcel(rows, "邮件总览-申请", TicketMailFeedAppliedExportVo.class, response);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeMailFeedRecords(TicketMailFeedBo bo) {
        String parseType = StrUtil.trim(bo.getParseType());
        if (StrUtil.isBlank(parseType)) {
            throw new ServiceException("请选择要删除的邮件类型");
        }
        String mode = normalizeMailFeedMode(bo.getMode());
        if (!"timeline".equalsIgnoreCase(mode)) {
            throw new ServiceException("全部删除仅支持在全部邮件模式下执行");
        }
        List<Long> platformAccountIds = loadPlatformAccountIds(bo.getPlatformId());
        if (bo.getPlatformId() != null && CollUtil.isEmpty(platformAccountIds)) {
            return 0;
        }
        int totalDeleted = 0;
        int batchSize = normalizeCleanupBatchSize(1000);
        while (true) {
            List<Long> recordIds = mailRecordMapper.selectList(buildMailFeedWrapper(bo, platformAccountIds)
                    .select(TicketMailRecord::getRecordId)
                    .orderByAsc(TicketMailRecord::getRecordId)
                    .last("LIMIT " + batchSize))
                .stream()
                .map(TicketMailRecord::getRecordId)
                .filter(Objects::nonNull)
                .toList();
            if (CollUtil.isEmpty(recordIds)) {
                break;
            }
            totalDeleted += logicDeleteMailRecords(recordIds);
            if (recordIds.size() < batchSize) {
                break;
            }
        }
        return totalDeleted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeMailRecords(Long[] recordIds) {
        return logicDeleteMailRecords(normalizeRecordIds(recordIds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanupOldMailRecords(Date cutoffTime, int batchSize) {
        if (cutoffTime == null) {
            return 0;
        }
        int limit = normalizeCleanupBatchSize(batchSize);
        List<Long> recordIds = mailRecordMapper.selectList(Wrappers.<TicketMailRecord>lambdaQuery()
                .select(TicketMailRecord::getRecordId)
                .apply("COALESCE(received_at, sync_time, create_time) < {0}", cutoffTime)
                .orderByAsc(TicketMailRecord::getReceivedAt)
                .orderByAsc(TicketMailRecord::getRecordId)
                .last("LIMIT " + limit))
            .stream()
            .map(TicketMailRecord::getRecordId)
            .filter(Objects::nonNull)
            .toList();
        return logicDeleteMailRecords(recordIds);
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

    private List<Long> normalizeRecordIds(Long[] recordIds) {
        return Arrays.stream(Objects.requireNonNullElse(recordIds, new Long[0]))
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private int normalizeCleanupBatchSize(int batchSize) {
        if (batchSize <= 0) {
            return 1000;
        }
        return Math.min(batchSize, 5000);
    }

    private int logicDeleteMailRecords(Collection<Long> recordIds) {
        if (CollUtil.isEmpty(recordIds)) {
            return 0;
        }
        return mailRecordMapper.update(null, Wrappers.<TicketMailRecord>lambdaUpdate()
            .in(TicketMailRecord::getRecordId, recordIds)
            .eq(TicketMailRecord::getDelFlag, 0L)
            .setSql("del_flag = record_id"));
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

    @Override
    public boolean syncGlobalMail() {
        if (!mailReaderProperties.isGlobalSyncEnabled()) {
            throw new ServiceException("中心邮箱全局同步未启用");
        }
        syncGlobalMailInternal(true);
        return true;
    }

    @Override
    public TicketMailRecordReparseResultVo reparseAllMailRecords() {
        TicketMailRecordReparseResultVo response = new TicketMailRecordReparseResultVo();
        response.setStartedAt(new Date());
        if (!reparseRunning.compareAndSet(false, true)) {
            response.setStarted(false);
            response.setRunning(true);
            response.setMessage("历史邮件重解析正在执行，请稍后查看结果");
            TicketMailRecordReparseResultVo last = lastReparseSummary.get();
            if (last != null) {
                response.setTotalScanned(last.getTotalScanned());
                response.setUpdatedCount(last.getUpdatedCount());
                response.setUnknownToAppliedCount(last.getUnknownToAppliedCount());
                response.setUnknownToSelectedCount(last.getUnknownToSelectedCount());
                response.setUnknownToRejectedCount(last.getUnknownToRejectedCount());
                response.setUnknownToPurchaseCompletedCount(last.getUnknownToPurchaseCompletedCount());
                response.setFailedCount(last.getFailedCount());
                response.setFinishedAt(last.getFinishedAt());
            }
            return response;
        }

        response.setStarted(true);
        response.setRunning(true);
        response.setMessage("历史邮件重解析已开始，请稍后刷新查看");
        scheduledExecutorService.execute(this::runReparseAllMailRecords);
        return response;
    }

    @Scheduled(
        initialDelayString = "${ticket.mail-reader.sync-fixed-delay-ms:120000}",
        fixedDelayString = "${ticket.mail-reader.sync-fixed-delay-ms:120000}"
    )
    public void autoSyncLatestMail() {
        if (!mailReaderProperties.isEnabled() || !mailReaderProperties.isAutoSyncEnabled()
            || mailReaderProperties.isGlobalSyncEnabled()) {
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

    @Scheduled(
        initialDelayString = "${ticket.mail-reader.global-sync-fixed-delay-ms:15000}",
        fixedDelayString = "${ticket.mail-reader.global-sync-fixed-delay-ms:15000}"
    )
    public void autoSyncGlobalMail() {
        if (!mailReaderProperties.isEnabled() || !mailReaderProperties.isAutoSyncEnabled()
            || !mailReaderProperties.isGlobalSyncEnabled()) {
            return;
        }
        syncGlobalMailInternal(false);
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
            List<TicketMailReaderService.MailReadResult> results = mailReaderService.readRecentForMailbox(
                mailbox.getUsername(), mailbox.getPassword());
            TicketMailReaderService.MailReadResult latestResult = results.stream()
                .max(Comparator.comparing(TicketMailReaderService.MailReadResult::getReceivedAt,
                    Comparator.nullsLast(Date::compareTo)))
                .orElseThrow(() -> new ServiceException("邮箱没有邮件"));
            for (TicketMailReaderService.MailReadResult result : results) {
                if (findExistingMailRecord(mailbox, result) == null) {
                    saveMailRecord(mailbox, result, "sync_latest");
                }
            }
            mailboxMapper.update(null, Wrappers.<TicketMailboxAccount>lambdaUpdate()
                .eq(TicketMailboxAccount::getMailboxId, mailbox.getMailboxId())
                .set(TicketMailboxAccount::getLatestMailSubject, latestResult.getSubject())
                .set(TicketMailboxAccount::getLatestMailFrom, latestResult.getFromAddress())
                .set(TicketMailboxAccount::getLatestMailReceivedAt, latestResult.getReceivedAt())
                .set(TicketMailboxAccount::getLatestMailMessageId, latestResult.getMessageId())
                .set(TicketMailboxAccount::getLatestMailExcerpt, latestResult.getBodyExcerpt())
                .set(TicketMailboxAccount::getLatestVerifyCode, latestResult.getVerifyCode())
                .set(TicketMailboxAccount::getLatestActivationUrl, latestResult.getActivationUrl())
                .set(TicketMailboxAccount::getLastMailSyncTime, now)
                .set(TicketMailboxAccount::getLastMailSyncError, null));
        } catch (ServiceException ex) {
            if ("邮箱没有邮件".equals(ex.getMessage())) {
                mailboxMapper.update(null, Wrappers.<TicketMailboxAccount>lambdaUpdate()
                    .eq(TicketMailboxAccount::getMailboxId, mailbox.getMailboxId())
                    .set(TicketMailboxAccount::getLastMailSyncTime, now)
                    .set(TicketMailboxAccount::getLastMailSyncError, null));
                return;
            }
            handleLatestMailSyncError(mailbox, now, ex, rethrow);
        } catch (Exception ex) {
            handleLatestMailSyncError(mailbox, now, ex, rethrow);
        }
    }

    private void syncGlobalMailInternal(boolean rethrow) {
        Date now = new Date();
        try {
            List<TicketMailReaderService.GlobalMailReadBatch> batches = mailReaderService.readRecentForGlobalMailbox(this::loadGlobalMailUidCursor);
            List<TicketMailReaderService.MailReadResult> results = batches.stream()
                .flatMap(batch -> batch.getResults().stream())
                .toList();
            if (CollUtil.isEmpty(results)) {
                return;
            }
            Map<String, TicketMailboxAccount> mailboxByEmail = loadMailboxMapByRecipient(results);
            Map<Long, TicketMailboxAccount> touchedMailboxes = new LinkedHashMap<>();
            Map<Long, TicketMailReaderService.MailReadResult> latestByMailbox = new LinkedHashMap<>();
            int savedCount = 0;
            int existingCount = 0;
            int unmatchedCount = 0;

            for (TicketMailReaderService.MailReadResult result : results) {
                List<TicketMailboxAccount> matchedMailboxes = matchRecipientMailboxes(result, mailboxByEmail);
                if (CollUtil.isEmpty(matchedMailboxes)) {
                    unmatchedCount++;
                    log.warn("global mail sync skipped unmatched mail, subject={}, messageId={}, recipients={}",
                        result.getSubject(), result.getMessageId(), result.getRecipientEmails());
                    continue;
                }
                for (TicketMailboxAccount mailbox : matchedMailboxes) {
                    touchedMailboxes.put(mailbox.getMailboxId(), mailbox);
                    TicketMailRecord existing = findExistingMailRecord(mailbox, result);
                    if (existing == null) {
                        saveMailRecord(mailbox, result, "sync_global");
                        savedCount++;
                    } else {
                        existingCount++;
                    }
                    if (shouldRefreshMailboxLatest(mailbox, result)) {
                        latestByMailbox.merge(
                            mailbox.getMailboxId(),
                            result,
                            this::pickLaterMailResult
                        );
                    }
                }
            }

            for (Map.Entry<Long, TicketMailboxAccount> entry : touchedMailboxes.entrySet()) {
                TicketMailReaderService.MailReadResult latest = latestByMailbox.get(entry.getKey());
                if (latest != null) {
                    updateMailboxLatestSnapshot(entry.getValue(), latest, now);
                } else {
                    clearMailboxSyncError(entry.getValue(), now);
                }
            }
            saveGlobalMailUidCursors(batches);
            if (savedCount > 0 || unmatchedCount > 0) {
                log.info("global mail sync completed, total={}, saved={}, existing={}, unmatched={}",
                    results.size(), savedCount, existingCount, unmatchedCount);
            } else {
                log.debug("global mail sync completed, total={}, saved={}, existing={}, unmatched={}",
                    results.size(), savedCount, existingCount, unmatchedCount);
            }
        } catch (ServiceException ex) {
            if ("中心邮箱没有邮件".equals(ex.getMessage())) {
                return;
            }
            handleGlobalMailSyncError(ex, rethrow);
        } catch (Exception ex) {
            handleGlobalMailSyncError(ex, rethrow);
        }
    }

    private TicketMailReaderService.GlobalMailUidCursor loadGlobalMailUidCursor(String folderName) {
        String value = RedisUtils.getCacheObject(globalMailUidCursorKey(folderName));
        if (StrUtil.isBlank(value)) {
            return null;
        }
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            TicketMailReaderService.GlobalMailUidCursor cursor = new TicketMailReaderService.GlobalMailUidCursor();
            cursor.setUidValidity(Long.parseLong(parts[0]));
            cursor.setLastUid(Long.parseLong(parts[1]));
            return cursor;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void saveGlobalMailUidCursors(List<TicketMailReaderService.GlobalMailReadBatch> batches) {
        if (CollUtil.isEmpty(batches)) {
            return;
        }
        for (TicketMailReaderService.GlobalMailReadBatch batch : batches) {
            if (batch == null || StrUtil.isBlank(batch.getFolderName())
                || batch.getUidValidity() == null || batch.getNextLastUid() == null || batch.getNextLastUid() <= 0) {
                continue;
            }
            RedisUtils.setCacheObject(
                globalMailUidCursorKey(batch.getFolderName()),
                batch.getUidValidity() + ":" + batch.getNextLastUid()
            );
        }
    }

    private String globalMailUidCursorKey(String folderName) {
        String username = StrUtil.blankToDefault(mailReaderProperties.getGlobalUsername(), mailReaderProperties.getUsername());
        return GLOBAL_MAIL_UID_CURSOR_KEY_PREFIX
            + StrUtil.blankToDefault(username, "default")
            + ":"
            + StrUtil.blankToDefault(folderName, "INBOX");
    }

    private Map<String, TicketMailboxAccount> loadMailboxMapByRecipient(List<TicketMailReaderService.MailReadResult> results) {
        List<String> recipientEmails = results.stream()
            .filter(Objects::nonNull)
            .flatMap(result -> result.getRecipientEmails() == null ? List.<String>of().stream() : result.getRecipientEmails().stream())
            .map(this::normalizeEmail)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
        if (CollUtil.isEmpty(recipientEmails)) {
            return Map.of();
        }
        return mailboxMapper.selectList(new LambdaQueryWrapper<TicketMailboxAccount>()
                .in(TicketMailboxAccount::getEmail, recipientEmails))
            .stream()
            .collect(Collectors.toMap(item -> normalizeEmail(item.getEmail()), Function.identity(), (left, right) -> right));
    }

    private List<TicketMailboxAccount> matchRecipientMailboxes(
        TicketMailReaderService.MailReadResult result,
        Map<String, TicketMailboxAccount> mailboxByEmail
    ) {
        if (result == null || CollUtil.isEmpty(result.getRecipientEmails()) || mailboxByEmail == null || mailboxByEmail.isEmpty()) {
            return List.of();
        }
        Map<Long, TicketMailboxAccount> matched = new LinkedHashMap<>();
        for (String recipientEmail : result.getRecipientEmails()) {
            TicketMailboxAccount mailbox = mailboxByEmail.get(normalizeEmail(recipientEmail));
            if (mailbox != null && mailbox.getMailboxId() != null) {
                matched.put(mailbox.getMailboxId(), mailbox);
            }
        }
        return new ArrayList<>(matched.values());
    }

    private String normalizeEmail(String email) {
        return StrUtil.trimToEmpty(email).toLowerCase(Locale.ROOT);
    }

    private boolean shouldRefreshMailboxLatest(TicketMailboxAccount mailbox, TicketMailReaderService.MailReadResult result) {
        if (mailbox == null || result == null) {
            return false;
        }
        Date current = mailbox.getLatestMailReceivedAt();
        Date candidate = result.getReceivedAt();
        return current == null || candidate == null || !candidate.before(current);
    }

    private TicketMailReaderService.MailReadResult pickLaterMailResult(
        TicketMailReaderService.MailReadResult left,
        TicketMailReaderService.MailReadResult right
    ) {
        Date leftDate = left == null ? null : left.getReceivedAt();
        Date rightDate = right == null ? null : right.getReceivedAt();
        if (leftDate == null) {
            return right == null ? left : right;
        }
        if (rightDate == null) {
            return left;
        }
        return rightDate.after(leftDate) ? right : left;
    }

    private void updateMailboxLatestSnapshot(TicketMailboxAccount mailbox, TicketMailReaderService.MailReadResult latestResult, Date now) {
        mailboxMapper.update(null, Wrappers.<TicketMailboxAccount>lambdaUpdate()
            .eq(TicketMailboxAccount::getMailboxId, mailbox.getMailboxId())
            .set(TicketMailboxAccount::getLatestMailSubject, latestResult.getSubject())
            .set(TicketMailboxAccount::getLatestMailFrom, latestResult.getFromAddress())
            .set(TicketMailboxAccount::getLatestMailReceivedAt, latestResult.getReceivedAt())
            .set(TicketMailboxAccount::getLatestMailMessageId, latestResult.getMessageId())
            .set(TicketMailboxAccount::getLatestMailExcerpt, latestResult.getBodyExcerpt())
            .set(TicketMailboxAccount::getLatestVerifyCode, latestResult.getVerifyCode())
            .set(TicketMailboxAccount::getLatestActivationUrl, latestResult.getActivationUrl())
            .set(TicketMailboxAccount::getLastMailSyncTime, now)
            .set(TicketMailboxAccount::getLastMailSyncError, null));
    }

    private void clearMailboxSyncError(TicketMailboxAccount mailbox, Date now) {
        mailboxMapper.update(null, Wrappers.<TicketMailboxAccount>lambdaUpdate()
            .eq(TicketMailboxAccount::getMailboxId, mailbox.getMailboxId())
            .set(TicketMailboxAccount::getLastMailSyncTime, now)
            .set(TicketMailboxAccount::getLastMailSyncError, null));
    }

    private void handleGlobalMailSyncError(Exception ex, boolean rethrow) {
        String message = StrUtil.maxLength(StrUtil.blankToDefault(ex.getMessage(), "中心邮箱同步失败"), 1000);
        log.warn("global mail sync failed: {}", message, ex);
        if (rethrow) {
            throw new ServiceException(message);
        }
    }

    private void runReparseAllMailRecords() {
        TicketMailRecordReparseResultVo summary = new TicketMailRecordReparseResultVo();
        summary.setStarted(true);
        summary.setRunning(true);
        summary.setMessage("历史邮件重解析执行中");
        summary.setStartedAt(new Date());
        try {
            long lastRecordId = 0L;
            while (true) {
                List<TicketMailRecord> records = mailRecordMapper.selectList(Wrappers.<TicketMailRecord>lambdaQuery()
                    .gt(TicketMailRecord::getRecordId, lastRecordId)
                    .orderByAsc(TicketMailRecord::getRecordId)
                    .last("LIMIT " + MAIL_REPARSE_BATCH_SIZE));
                if (CollUtil.isEmpty(records)) {
                    break;
                }
                for (TicketMailRecord record : records) {
                    lastRecordId = record.getRecordId();
                    summary.setTotalScanned(summary.getTotalScanned() + 1);
                    try {
                        reparseMailRecord(record, summary);
                        summary.setUpdatedCount(summary.getUpdatedCount() + 1);
                    } catch (Exception ex) {
                        summary.setFailedCount(summary.getFailedCount() + 1);
                        log.warn("reparse history mail record failed, recordId={}, mailboxId={}",
                            record.getRecordId(), record.getMailboxId(), ex);
                    }
                }
            }
            summary.setMessage("历史邮件重解析完成");
            log.info(
                "mail record reparse completed: totalScanned={}, updatedCount={}, unknownToApplied={}, unknownToSelected={}, unknownToRejected={}, unknownToPurchaseCompleted={}, failedCount={}",
                summary.getTotalScanned(),
                summary.getUpdatedCount(),
                summary.getUnknownToAppliedCount(),
                summary.getUnknownToSelectedCount(),
                summary.getUnknownToRejectedCount(),
                summary.getUnknownToPurchaseCompletedCount(),
                summary.getFailedCount()
            );
        } catch (Exception ex) {
            summary.setMessage("历史邮件重解析异常结束: " + StrUtil.blankToDefault(ex.getMessage(), "unknown"));
            log.error("mail record reparse failed", ex);
        } finally {
            summary.setRunning(false);
            summary.setFinishedAt(new Date());
            lastReparseSummary.set(summary);
            reparseRunning.set(false);
        }
    }

    private void reparseMailRecord(TicketMailRecord record, TicketMailRecordReparseResultVo summary) {
        TicketMailReaderService.MailReadResult parsed = mailReaderService.reparseStoredMailRecord(
            record.getSubject(),
            record.getBodyContent(),
            record.getBodyExcerpt()
        );
        String oldParseType = StrUtil.blankToDefault(record.getParseType(), "unknown");
        String newParseType = StrUtil.blankToDefault(parsed.getParseType(), "unknown");
        if ("unknown".equals(oldParseType)) {
            switch (newParseType) {
                case "lottery_applied" -> summary.setUnknownToAppliedCount(summary.getUnknownToAppliedCount() + 1);
                case "lottery_selected" -> summary.setUnknownToSelectedCount(summary.getUnknownToSelectedCount() + 1);
                case "lottery_rejected" -> summary.setUnknownToRejectedCount(summary.getUnknownToRejectedCount() + 1);
                case PARSE_TYPE_PURCHASE_COMPLETED -> summary.setUnknownToPurchaseCompletedCount(summary.getUnknownToPurchaseCompletedCount() + 1);
                default -> {
                }
            }
        }

        record.setParseType(StrUtil.maxLength(parsed.getParseType(), 32));
        record.setVerifyCode(StrUtil.maxLength(parsed.getVerifyCode(), 32));
        record.setActivationUrl(parsed.getActivationUrl());
        record.setLotteryApplicationNo(StrUtil.maxLength(parsed.getLotteryApplicationNo(), 64));
        record.setLotteryResultStatus(StrUtil.maxLength(parsed.getLotteryResultStatus(), 32));
        record.setParsed(parsed.isParsed());
        record.setSyncTime(new Date());
        mailRecordMapper.updateById(record);
        refreshMailboxLatestSnapshotIfMatched(record);
        lotteryResultMailService.processSelectedMail(record);
        lotteryResultMailService.processPurchaseCompletedMail(record);
    }

    private void refreshMailboxLatestSnapshotIfMatched(TicketMailRecord record) {
        if (record == null || record.getMailboxId() == null) {
            return;
        }
        LambdaUpdateWrapper<TicketMailboxAccount> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(TicketMailboxAccount::getMailboxId, record.getMailboxId());
        if (StrUtil.isNotBlank(record.getMessageId())) {
            updateWrapper.eq(TicketMailboxAccount::getLatestMailMessageId, record.getMessageId());
        } else {
            updateWrapper.eq(record.getReceivedAt() != null, TicketMailboxAccount::getLatestMailReceivedAt, record.getReceivedAt())
                .eq(StrUtil.isNotBlank(record.getSubject()), TicketMailboxAccount::getLatestMailSubject, record.getSubject());
        }
        updateWrapper
            .set(TicketMailboxAccount::getLatestVerifyCode, record.getVerifyCode())
            .set(TicketMailboxAccount::getLatestActivationUrl, record.getActivationUrl());
        mailboxMapper.update(null, updateWrapper);
    }

    private void handleLatestMailSyncError(TicketMailboxAccount mailbox, Date now, Exception ex, boolean rethrow) {
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

    private void saveMailRecord(TicketMailboxAccount mailbox, TicketMailReaderService.MailReadResult result, String readSource) {
        if (mailbox == null || result == null) {
            return;
        }
        TicketMailRecord record = findExistingMailRecord(mailbox, result);
        if (record == null) {
            record = new TicketMailRecord();
            record.setMailboxId(mailbox.getMailboxId());
            record.setEmail(mailbox.getEmail());
            record.setUsername(mailbox.getUsername());
        }
        record.setAccountId(mailbox.getUsedAccountId());
        record.setFolderName(StrUtil.maxLength(result.getFolderName(), 128));
        record.setMessageId(StrUtil.maxLength(result.getMessageId(), 255));
        record.setSubject(StrUtil.maxLength(result.getSubject(), 1000));
        record.setFromAddress(StrUtil.maxLength(result.getFromAddress(), 500));
        record.setReceivedAt(result.getReceivedAt());
        record.setBodyExcerpt(StrUtil.maxLength(result.getBodyExcerpt(), 2000));
        record.setBodyContent(result.getBodyContent());
        record.setParseType(StrUtil.maxLength(result.getParseType(), 32));
        record.setVerifyCode(StrUtil.maxLength(result.getVerifyCode(), 32));
        record.setActivationUrl(result.getActivationUrl());
        record.setLotteryApplicationNo(StrUtil.maxLength(result.getLotteryApplicationNo(), 64));
        record.setLotteryResultStatus(StrUtil.maxLength(result.getLotteryResultStatus(), 32));
        record.setParsed(result.isParsed());
        record.setReadSource(StrUtil.maxLength(readSource, 64));
        record.setSyncTime(new Date());
        if (record.getRecordId() == null) {
            mailRecordMapper.insert(record);
        } else {
            mailRecordMapper.updateById(record);
        }
        lotteryResultMailService.processSelectedMail(record);
        lotteryResultMailService.processPurchaseCompletedMail(record);
    }

    private TicketMailRecord findExistingMailRecord(TicketMailboxAccount mailbox, TicketMailReaderService.MailReadResult result) {
        LambdaQueryWrapper<TicketMailRecord> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TicketMailRecord::getMailboxId, mailbox.getMailboxId());
        if (StrUtil.isNotBlank(result.getMessageId())) {
            wrapper.eq(TicketMailRecord::getMessageId, result.getMessageId());
        } else {
            wrapper.eq(result.getReceivedAt() != null, TicketMailRecord::getReceivedAt, result.getReceivedAt())
                .eq(StrUtil.isNotBlank(result.getSubject()), TicketMailRecord::getSubject, StrUtil.maxLength(result.getSubject(), 1000));
        }
        return mailRecordMapper.selectOne(wrapper.last("limit 1"), false);
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

    private void enrichMatchedMailRecords(List<TicketMailboxAccountVo> rows, String mailKeyword) {
        if (CollUtil.isEmpty(rows) || StrUtil.isBlank(mailKeyword)) {
            return;
        }
        List<Long> mailboxIds = rows.stream()
            .map(TicketMailboxAccountVo::getMailboxId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (CollUtil.isEmpty(mailboxIds)) {
            return;
        }
        List<TicketMailRecord> matchedRecords = mailRecordMapper.selectList(new LambdaQueryWrapper<TicketMailRecord>()
            .in(TicketMailRecord::getMailboxId, mailboxIds)
            .and(wrapper -> wrapper
                .like(TicketMailRecord::getSubject, mailKeyword)
                .or()
                .like(TicketMailRecord::getBodyContent, mailKeyword))
            .orderByDesc(TicketMailRecord::getReceivedAt)
            .orderByDesc(TicketMailRecord::getRecordId));
        if (CollUtil.isEmpty(matchedRecords)) {
            return;
        }
        Map<Long, TicketMailRecord> latestByMailbox = matchedRecords.stream()
            .filter(record -> record.getMailboxId() != null)
            .collect(Collectors.toMap(TicketMailRecord::getMailboxId, Function.identity(), (left, right) -> left));
        for (TicketMailboxAccountVo row : rows) {
            TicketMailRecord matched = latestByMailbox.get(row.getMailboxId());
            if (matched == null) {
                continue;
            }
            row.setMatchedMailRecordId(matched.getRecordId());
            row.setMatchedMailSubject(matched.getSubject());
            row.setMatchedMailFrom(matched.getFromAddress());
            row.setMatchedMailReceivedAt(matched.getReceivedAt());
            row.setMatchedMailExcerpt(compactMailText(StrUtil.blankToDefault(matched.getBodyExcerpt(), matched.getBodyContent())));
        }
    }

    private String compactMailText(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > 220 ? compact.substring(0, 220) + "..." : compact;
    }

    private List<TicketMailRecord> selectMailFeedRecords(TicketMailFeedBo bo, List<Long> platformAccountIds) {
        if (bo.getPlatformId() != null && CollUtil.isEmpty(platformAccountIds)) {
            return List.of();
        }
        String mode = normalizeMailFeedMode(bo.getMode());
        LambdaQueryWrapper<TicketMailRecord> wrapper = buildMailFeedWrapper(bo, platformAccountIds);
        if ("latest".equalsIgnoreCase(mode)) {
            applyLatestPerMailboxFilter(wrapper, bo, platformAccountIds);
        }
        wrapper.orderByDesc(TicketMailRecord::getReceivedAt)
            .orderByDesc(TicketMailRecord::getRecordId);
        return mailRecordMapper.selectList(wrapper);
    }

    private String normalizeMailFeedMode(String mode) {
        String normalizedMode = StrUtil.blankToDefault(StrUtil.trim(mode), "latest");
        if (!"latest".equalsIgnoreCase(normalizedMode) && !"timeline".equalsIgnoreCase(normalizedMode)) {
            throw new ServiceException("不支持的邮件总览模式");
        }
        return normalizedMode;
    }

    private List<Long> loadPlatformAccountIds(Long platformId) {
        if (platformId == null) {
            return null;
        }
        return accountMapper.selectList(new QueryWrapper<TicketManagedAccount>()
                .select("account_id")
                .eq("platform_id", platformId))
            .stream()
            .map(TicketManagedAccount::getAccountId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private LambdaQueryWrapper<TicketMailRecord> buildMailFeedWrapper(TicketMailFeedBo bo, List<Long> platformAccountIds) {
        String email = StrUtil.trim(bo.getEmail());
        String keyword = StrUtil.trim(bo.getKeyword());
        String parseType = StrUtil.trim(bo.getParseType());
        String beginReceivedAt = StrUtil.trim(bo.getBeginReceivedAt());
        String endReceivedAt = StrUtil.trim(bo.getEndReceivedAt());
        LambdaQueryWrapper<TicketMailRecord> wrapper = Wrappers.<TicketMailRecord>lambdaQuery()
            .in(CollUtil.isNotEmpty(platformAccountIds), TicketMailRecord::getAccountId, platformAccountIds)
            .eq(StrUtil.isNotBlank(parseType), TicketMailRecord::getParseType, parseType)
            .ge(StrUtil.isNotBlank(beginReceivedAt), TicketMailRecord::getReceivedAt, beginReceivedAt)
            .le(StrUtil.isNotBlank(endReceivedAt), TicketMailRecord::getReceivedAt, endReceivedAt)
            .and(StrUtil.isNotBlank(keyword), nested -> nested
                .like(TicketMailRecord::getSubject, keyword)
                .or()
                .like(TicketMailRecord::getBodyContent, keyword));
        if (StrUtil.isNotBlank(email)) {
            if (looksLikeExactEmail(email)) {
                wrapper.eq(TicketMailRecord::getEmail, email);
            } else {
                wrapper.like(TicketMailRecord::getEmail, email);
            }
        }
        return wrapper;
    }

    private void applyLatestPerMailboxFilter(LambdaQueryWrapper<TicketMailRecord> wrapper, TicketMailFeedBo bo, List<Long> platformAccountIds) {
        String email = StrUtil.trim(bo.getEmail());
        String keyword = StrUtil.trim(bo.getKeyword());
        String parseType = StrUtil.trim(bo.getParseType());
        String beginReceivedAt = StrUtil.trim(bo.getBeginReceivedAt());
        String endReceivedAt = StrUtil.trim(bo.getEndReceivedAt());
        boolean exactEmail = looksLikeExactEmail(email);
        List<Object> args = CollUtil.newArrayList();
        StringBuilder sql = new StringBuilder("""
            NOT EXISTS (
                SELECT 1
                FROM ticket_mail_record newer
                WHERE newer.mailbox_id = ticket_mail_record.mailbox_id
                  AND newer.tenant_id = ticket_mail_record.tenant_id
                  AND newer.del_flag = 0
            """);
        if (StrUtil.isNotBlank(email)) {
            int argIndex = args.size();
            if (exactEmail) {
                sql.append(" AND newer.email = {").append(argIndex).append('}');
            } else {
                sql.append(" AND newer.email LIKE CONCAT('%',{").append(argIndex).append("},'%')");
            }
            args.add(email);
        }
        if (StrUtil.isNotBlank(parseType)) {
            int argIndex = args.size();
            sql.append(" AND newer.parse_type = {").append(argIndex).append('}');
            args.add(parseType);
        }
        if (StrUtil.isNotBlank(beginReceivedAt)) {
            int argIndex = args.size();
            sql.append(" AND newer.received_at >= {").append(argIndex).append('}');
            args.add(beginReceivedAt);
        }
        if (StrUtil.isNotBlank(endReceivedAt)) {
            int argIndex = args.size();
            sql.append(" AND newer.received_at <= {").append(argIndex).append('}');
            args.add(endReceivedAt);
        }
        if (CollUtil.isNotEmpty(platformAccountIds)) {
            appendInCondition(sql, args, "newer.account_id", platformAccountIds);
        }
        if (StrUtil.isNotBlank(keyword)) {
            int subjectIndex = args.size();
            int bodyIndex = subjectIndex + 1;
            sql.append(" AND (newer.subject LIKE CONCAT('%',{").append(subjectIndex).append("},'%')")
                .append(" OR newer.body_content LIKE CONCAT('%',{").append(bodyIndex).append("},'%'))");
            args.add(keyword);
            args.add(keyword);
        }
        sql.append("""
                  AND (
                        newer.received_at > ticket_mail_record.received_at
                     OR (
                            newer.received_at = ticket_mail_record.received_at
                        AND newer.record_id > ticket_mail_record.record_id
                        )
                  )
            )
            """);
        wrapper.apply(sql.toString(), args.toArray());
    }

    private boolean looksLikeExactEmail(String email) {
        return StrUtil.isNotBlank(email)
            && StrUtil.contains(email, "@")
            && !StrUtil.containsAny(email, "%", "_", " ");
    }

    private void appendInCondition(StringBuilder sql, List<Object> args, String columnName, List<Long> values) {
        sql.append(" AND ").append(columnName).append(" IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('{').append(args.size()).append('}');
            args.add(values.get(i));
        }
        sql.append(')');
    }

    List<TicketMailFeedAppliedExportVo> buildAppliedExportRows(List<TicketMailRecord> records) {
        if (CollUtil.isEmpty(records)) {
            return List.of();
        }
        Map<Long, TicketManagedAccount> accountMap = loadManagedAccountMap(records);
        Map<Long, TicketMailboxAccount> mailboxMap = loadMailboxMap(records);
        Map<Long, TicketPlatformConfig> platformMap = loadPlatformMap(accountMap);
        return records.stream().map(record -> {
            TicketManagedAccount account = getMapValue(accountMap, record.getAccountId());
            TicketMailboxAccount mailbox = getMapValue(mailboxMap, record.getMailboxId());
            TicketPlatformConfig platform = account == null ? null : getMapValue(platformMap, account.getPlatformId());
            TicketMailFeedAppliedExportVo row = new TicketMailFeedAppliedExportVo();
            row.setPlatformName(platform == null ? null : platform.getPlatformName());
            row.setMailboxEmail(record.getEmail());
            row.setMailboxPassword(mailbox == null ? null : mailbox.getPassword());
            row.setFullName(extractFullName(account == null ? null : account.getAccountInfo()));
            row.setFurigana(extractFurigana(account == null ? null : account.getAccountInfo()));
            row.setLotteryApplicationNo(record.getLotteryApplicationNo());
            return row;
        }).toList();
    }

    List<TicketMailFeedSelectedExportVo> buildSelectedExportRows(List<TicketMailRecord> records) {
        if (CollUtil.isEmpty(records)) {
            return List.of();
        }
        Map<Long, TicketManagedAccount> accountMap = loadManagedAccountMap(records);
        Map<Long, TicketMailboxAccount> mailboxMap = loadMailboxMap(records);
        Map<Long, TicketPlatformConfig> platformMap = loadPlatformMap(accountMap);
        Map<String, List<TicketOrderExecution>> executionMap = loadOrderExecutionMap(records);
        Map<Long, TicketSaleTaskSchedule> scheduleMap = loadScheduleMap(executionMap);
        return records.stream().map(record -> {
            TicketManagedAccount account = getMapValue(accountMap, record.getAccountId());
            TicketMailboxAccount mailbox = getMapValue(mailboxMap, record.getMailboxId());
            TicketPlatformConfig platform = account == null ? null : getMapValue(platformMap, account.getPlatformId());
            TicketOrderExecution execution = matchOrderExecution(record, getMapValue(executionMap, record.getLotteryApplicationNo()));
            TicketSaleTaskSchedule schedule = execution == null ? null : getMapValue(scheduleMap, execution.getLotteryScheduleId());
            TicketMailFeedSelectedExportVo row = new TicketMailFeedSelectedExportVo();
            row.setPlatformName(platform == null ? null : platform.getPlatformName());
            row.setTitle(resolveExportTitle(record));
            row.setMailboxEmail(record.getEmail());
            row.setMailboxPassword(mailbox == null ? null : mailbox.getPassword());
            row.setFullName(extractFullName(account == null ? null : account.getAccountInfo()));
            row.setFurigana(extractFurigana(account == null ? null : account.getAccountInfo()));
            row.setLotteryApplicationNo(record.getLotteryApplicationNo());
            row.setSessionInfo(resolveExportSessionInfo(record, schedule));
            return row;
        }).toList();
    }

    private String resolveExportTitle(TicketMailRecord record) {
        if (record == null) {
            return null;
        }
        return StrUtil.blankToDefault(extractEventName(record.getBodyContent()), StrUtil.trim(record.getSubject()));
    }

    private String extractEventName(String bodyContent) {
        Matcher matcher = EVENT_NAME_PATTERN.matcher(StrUtil.blankToDefault(bodyContent, ""));
        return matcher.find() ? StrUtil.trim(matcher.group(1)) : null;
    }

    private String resolveExportSessionInfo(TicketMailRecord record, TicketSaleTaskSchedule schedule) {
        String scheduleInfo = schedule == null ? null : StrUtil.blankToDefault(schedule.getSessionLabel(), schedule.getSessionId());
        if (StrUtil.isNotBlank(scheduleInfo) || record == null || !PARSE_TYPE_PURCHASE_COMPLETED.equals(record.getParseType())) {
            return scheduleInfo;
        }
        return extractPurchaseTicketName(record.getBodyContent());
    }

    private String extractPurchaseTicketName(String bodyContent) {
        Matcher matcher = PURCHASE_TICKET_NAME_PATTERN.matcher(StrUtil.blankToDefault(bodyContent, ""));
        return matcher.find() ? StrUtil.trim(matcher.group(1)) : null;
    }

    private <K, V> V getMapValue(Map<K, V> map, K key) {
        if (map == null || key == null) {
            return null;
        }
        return map.get(key);
    }

    private Map<Long, TicketManagedAccount> loadManagedAccountMap(List<TicketMailRecord> records) {
        List<Long> accountIds = records.stream()
            .map(TicketMailRecord::getAccountId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (CollUtil.isEmpty(accountIds)) {
            return Map.of();
        }
        return accountMapper.selectByIds(accountIds).stream()
            .collect(Collectors.toMap(TicketManagedAccount::getAccountId, Function.identity(), (left, right) -> right));
    }

    private Map<Long, TicketMailboxAccount> loadMailboxMap(List<TicketMailRecord> records) {
        List<Long> mailboxIds = records.stream()
            .map(TicketMailRecord::getMailboxId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (CollUtil.isEmpty(mailboxIds)) {
            return Map.of();
        }
        return mailboxMapper.selectByIds(mailboxIds).stream()
            .collect(Collectors.toMap(TicketMailboxAccount::getMailboxId, Function.identity(), (left, right) -> right));
    }

    private Map<Long, TicketPlatformConfig> loadPlatformMap(Map<Long, TicketManagedAccount> accountMap) {
        List<Long> platformIds = accountMap.values().stream()
            .map(TicketManagedAccount::getPlatformId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (CollUtil.isEmpty(platformIds)) {
            return Map.of();
        }
        return platformConfigMapper.selectByIds(platformIds).stream()
            .collect(Collectors.toMap(TicketPlatformConfig::getPlatformId, Function.identity(), (left, right) -> right));
    }

    private Map<String, List<TicketOrderExecution>> loadOrderExecutionMap(List<TicketMailRecord> records) {
        List<String> orderNos = records.stream()
            .map(TicketMailRecord::getLotteryApplicationNo)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
        if (CollUtil.isEmpty(orderNos)) {
            return Map.of();
        }
        return orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
                .in(TicketOrderExecution::getOrderNo, orderNos))
            .stream()
            .collect(Collectors.groupingBy(TicketOrderExecution::getOrderNo));
    }

    private Map<Long, TicketSaleTaskSchedule> loadScheduleMap(Map<String, List<TicketOrderExecution>> executionMap) {
        List<Long> scheduleIds = executionMap.values().stream()
            .flatMap(List::stream)
            .map(TicketOrderExecution::getLotteryScheduleId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (CollUtil.isEmpty(scheduleIds)) {
            return Map.of();
        }
        return saleTaskScheduleMapper.selectByIds(scheduleIds).stream()
            .collect(Collectors.toMap(TicketSaleTaskSchedule::getScheduleId, Function.identity(), (left, right) -> right));
    }

    private TicketOrderExecution matchOrderExecution(TicketMailRecord record, List<TicketOrderExecution> candidates) {
        if (record == null || CollUtil.isEmpty(candidates)) {
            return null;
        }
        if (record.getAccountId() != null) {
            return candidates.stream()
                .filter(item -> Objects.equals(item.getAccountId(), record.getAccountId()))
                .findFirst()
                .orElse(null);
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private String extractFullName(String accountInfoText) {
        JSONObject accountInfo = parseAccountInfo(accountInfoText);
        if (accountInfo == null) {
            return null;
        }
        String fullName = StrUtil.trim(accountInfo.getStr("fullName"));
        if (StrUtil.isNotBlank(fullName)) {
            return fullName;
        }
        String familyName = StrUtil.trim(accountInfo.getStr("familyName"));
        String givenName = StrUtil.trim(accountInfo.getStr("givenName"));
        String combined = StrUtil.blankToDefault(familyName, "") + StrUtil.blankToDefault(givenName, "");
        combined = StrUtil.trim(combined);
        return StrUtil.isBlank(combined) ? null : combined;
    }

    private String extractFurigana(String accountInfoText) {
        JSONObject accountInfo = parseAccountInfo(accountInfoText);
        if (accountInfo == null) {
            return null;
        }
        String furigana = StrUtil.trim(accountInfo.getStr("furigana"));
        return StrUtil.isBlank(furigana) ? null : furigana;
    }

    private JSONObject parseAccountInfo(String accountInfoText) {
        if (StrUtil.isBlank(accountInfoText)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(accountInfoText);
        } catch (Exception ex) {
            log.warn("parse managed account info failed, accountInfo={}", accountInfoText, ex);
            return null;
        }
    }
}
