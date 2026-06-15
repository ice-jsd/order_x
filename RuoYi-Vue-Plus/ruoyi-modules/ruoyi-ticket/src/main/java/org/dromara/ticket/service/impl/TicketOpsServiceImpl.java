package org.dromara.ticket.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.sse.dto.SseMessageDto;
import org.dromara.common.sse.utils.SseMessageUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.ticket.adapter.*;
import org.dromara.ticket.config.TicketPythonExecutorProperties;
import org.dromara.ticket.config.TicketOrderExecutorProperties;
import org.dromara.ticket.config.TicketStalwartProperties;
import org.dromara.ticket.domain.*;
import org.dromara.ticket.domain.bo.*;
import org.dromara.ticket.domain.dto.TicketLoginProgressMessage;
import org.dromara.ticket.domain.dto.TicketOrderDispatchRequest;
import org.dromara.ticket.domain.dto.TicketRegisterProgressMessage;
import org.dromara.ticket.domain.vo.*;
import org.dromara.ticket.mapper.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.dromara.ticket.service.ITicketJumpShopProfileService;
import org.dromara.ticket.service.ITicketOpsService;
import org.dromara.ticket.service.ITicketMailboxAccountService;
import org.dromara.ticket.service.TicketLotteryResultMailService;
import org.dromara.ticket.service.TicketPythonExecutorClient;
import org.dromara.ticket.service.TicketPythonQueueHandler;
import org.dromara.ticket.service.TicketMailReaderService;
import org.dromara.ticket.service.TicketOrderExecutorClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TicketOpsServiceImpl implements ITicketOpsService, TicketPythonQueueHandler {

    private static final Set<String> ACTIVE_RELATION_STATUSES = Set.of("registered", "logged_in", "registering", "verification_pending");
    private static final Set<String> ENABLED_PHONE_STATUSES = Set.of("available", "disabled");
    private static final Set<String> RUNNING_REGISTER_RELATION_STATUSES = Set.of("registering", "verification_pending");
    private static final Set<String> ACCOUNT_STATUSES = Set.of("pending_register", "pending_activation", "activated", "registered", "disabled");
    private static final Set<String> LOGIN_STATUSES = Set.of("offline", "logged_in", "login_failed");
    private static final Set<String> BATCH_STATUSES = Set.of("executing", "completed", "partial", "blocked");
    private static final Set<String> BATCH_TERMINAL_STATUSES = Set.of("completed", "partial", "blocked");
    private static final Set<String> EXECUTION_RUNNING_STATUSES = Set.of("running");
    private static final Pattern UNICODE_CODE_POINT_TOKEN = Pattern.compile("(?i)(?:U\\+|\\\\u|0x)([0-9a-f]{4,6})");
    private static final Set<String> EXECUTION_PAYMENT_PENDING_STATUSES = Set.of("submitted", "pending_payment");
    private static final Set<String> LOTTERY_PAYABLE_PAYMENT_STATUSES = Set.of("offline_pending", "pending_online", "manual_pending");
    private static final Set<String> EXECUTION_FAILURE_STATUSES = Set.of("failed", "blocked", "timeout", "cancelled");
    private static final Set<String> LOTTERY_RETRYABLE_STATUSES = Set.of("failed", "timeout", "blocked");
    private static final Set<String> LOTTERY_RETRY_PENDING_STATUSES = Set.of("queued", "running");
    private static final Set<String> LOTTERY_SUCCESS_STATUSES = Set.of("submitted", "pending_payment", "paid");
    private static final Set<String> SALE_TASK_EDITABLE_STATUSES = Set.of("draft");
    private static final Set<String> SALE_TASK_CANCELABLE_STATUSES = Set.of("draft", "executing", "partial");
    private static final Set<String> SALE_TASK_DELETABLE_STATUSES = Set.of("draft", "cancelled", "completed", "paid", "failed", "blocked");
    private static final Set<String> SALE_TASK_EXECUTE_NOW_STATUSES = Set.of("draft");
    private static final Set<String> LOTTERY_BATCH_TASK_EDITABLE_STATUSES = Set.of("draft");
    private static final Set<String> LOTTERY_BATCH_TASK_EXECUTE_NOW_STATUSES = Set.of("draft");
    private static final String LIVEPOCKET_PLATFORM_CODE = "livepocket";
    private static final String HANDS_FORM_PLATFORM_CODE = "hands-form";
    private static final String JUMP_SHOP_PLATFORM_CODE = "jump-shop";
    private static final String JUMP_SHOP_ADAPTER_TYPE = "jump-shop-online";
    private static final String LOGIN_MODE_AUTO_MAILBOX = "auto_mailbox";
    private static final String LOGIN_MODE_MANUAL_EMAIL_CODE = "manual_email_code";
    private static final String EMAIL_CODE_MODE_AUTO = "auto";
    private static final String EMAIL_CODE_MODE_MANUAL = "manual";
    private static final String LOGIN_DETAIL_STATUS_WAITING_CODE = "waiting_code";
    private static final String LIVEPOCKET_LAST_NAME_LOGIN_CONTEXT_REQUIRED_MESSAGE = "账号缺少登录上下文，请先批量登录成功后再改姓";
    private static final int MANUAL_EMAIL_CODE_TIMEOUT_SECONDS = 5 * 60;
    private static final int MANUAL_EMAIL_CODE_POLL_INTERVAL_SECONDS = 2;
    private static final int MANUAL_EMAIL_CODE_MAX_ATTEMPTS = 3;
    private static final String MANUAL_EMAIL_CODE_REDIS_PREFIX = "ticket:login:manual-email-code:";
    private static final String MAILBOX_BINDING_BOUND = "bound";
    private static final String MAILBOX_BINDING_SHARED = "shared";
    private static final String MAILBOX_BINDING_AVAILABLE = "available";
    private static final String MAILBOX_BINDING_INTERNAL_DOMAIN = "internal_domain";
    private static final String MAILBOX_BINDING_EXTERNAL = "external";
    private static final List<NamePair> HANDS_FORM_FAMILY_NAMES = List.of(
        new NamePair("佐藤", "サトウ"),
        new NamePair("鈴木", "スズキ"),
        new NamePair("高橋", "タカハシ"),
        new NamePair("田中", "タナカ"),
        new NamePair("伊藤", "イトウ"),
        new NamePair("渡辺", "ワタナベ"),
        new NamePair("山本", "ヤマモト"),
        new NamePair("中村", "ナカムラ"),
        new NamePair("小林", "コバヤシ"),
        new NamePair("加藤", "カトウ")
    );
    private static final List<NamePair> HANDS_FORM_GIVEN_NAMES = List.of(
        new NamePair("太郎", "タロウ"),
        new NamePair("次郎", "ジロウ"),
        new NamePair("健太", "ケンタ"),
        new NamePair("翔太", "ショウタ"),
        new NamePair("大輔", "ダイスケ"),
        new NamePair("花子", "ハナコ"),
        new NamePair("美咲", "ミサキ"),
        new NamePair("愛子", "アイコ"),
        new NamePair("結衣", "ユイ"),
        new NamePair("葵", "アオイ")
    );
    private static final List<String> LIVEPOCKET_LOTTERY_TICKETS_URL_KEYS = List.of("ticketEntryUrl", "ticketsUrl", "lotteryTicketsUrl", "lotteryEntryUrl");
    private static final String HANDS_EXTENSION_WAITING_MESSAGE = "等待 Hands Chrome 扩展领取";
    private static final String HANDS_EXTENSION_RUNNING_MESSAGE = "Hands Chrome 扩展处理中";
    private static final int AUDIT_BUSINESS_KEY_MAX_LENGTH = 128;
    private static final int AUDIT_MESSAGE_MAX_LENGTH = 500;
    private static final int LAST_ERROR_MAX_LENGTH = 500;
    private static final int RESULT_MESSAGE_MAX_LENGTH = 500;
    private static final long EMAIL_VERIFY_TIME_DRIFT_MILLIS = 30_000L;
    private static final long DEFAULT_WARMUP_LEAD_MILLIS = 10 * 60 * 1000L;
    private static final long SALE_TASK_EDIT_LOCK_LEAD_MILLIS = 5 * 60 * 1000L;
    private static final java.util.regex.Pattern YEAR_VERIFY_CODE_PATTERN = java.util.regex.Pattern.compile("^(?:19|20)\\d{2}$");

    private final TicketPlatformConfigMapper platformMapper;
    private final TicketPhoneNumberMapper phoneMapper;
    private final TicketPhonePlatformRelationMapper relationMapper;
    private final TicketManagedAccountMapper accountMapper;
    private final TicketRegistrationBatchMapper registrationBatchMapper;
    private final TicketRegistrationBatchDetailMapper registrationBatchDetailMapper;
    private final TicketLoginBatchMapper loginBatchMapper;
    private final TicketLoginBatchDetailMapper loginBatchDetailMapper;
    private final TicketMailboxAccountMapper mailboxAccountMapper;
    private final ITicketMailboxAccountService mailboxAccountService;
    private final TicketMailRecordMapper mailRecordMapper;
    private final TicketEventConfigMapper eventMapper;
    private final TicketLotteryEventParseRecordMapper parseRecordMapper;
    private final TicketSaleTaskMapper saleTaskMapper;
    private final TicketSaleTaskAccountMapper saleTaskAccountMapper;
    private final TicketSaleTaskScheduleMapper saleTaskScheduleMapper;
    private final TicketOrderExecutionMapper orderExecutionMapper;
    private final TicketAuditEventMapper auditEventMapper;
    private final TicketPlatformAdapterRegistry adapterRegistry;
    private final TicketOrderExecutorClient ticketOrderExecutorClient;
    private final TicketOrderExecutorProperties ticketOrderExecutorProperties;
    private final TicketPythonExecutorClient ticketPythonExecutorClient;
    private final TicketPythonExecutorProperties ticketPythonExecutorProperties;
    private final TicketStalwartProperties ticketStalwartProperties;
    private final TicketMailReaderService ticketMailReaderService;
    private final TicketLotteryResultMailService lotteryResultMailService;
    private final TicketLotteryBatchTaskService ticketLotteryBatchTaskService;
    private final TicketLotteryLinkOccupancyService ticketLotteryLinkOccupancyService;
    private final ITicketJumpShopProfileService jumpShopProfileService;
    @Qualifier("ticketPythonStringRedisTemplate")
    private final StringRedisTemplate ticketPythonStringRedisTemplate;
    private final TransactionTemplate transactionTemplate;
    @Qualifier("scheduledExecutorService")
    private final ScheduledExecutorService scheduledExecutorService;

    @Override
    public TicketDashboardOverviewVo selectDashboardOverview() {
        TicketDashboardOverviewVo overview = new TicketDashboardOverviewVo();
        overview.setPlatformTotal(platformMapper.selectCount(Wrappers.lambdaQuery()));
        overview.setEnabledPlatformCount(platformMapper.selectCount(Wrappers.lambdaQuery(TicketPlatformConfig.class)
            .eq(TicketPlatformConfig::getEnabled, Boolean.TRUE)));

        overview.setTaskTotal(saleTaskMapper.selectCount(Wrappers.lambdaQuery()));
        overview.setRunningTaskCount(saleTaskMapper.selectCount(Wrappers.lambdaQuery(TicketSaleTask.class)
            .eq(TicketSaleTask::getTaskStatus, "executing")));
        overview.setAbnormalTaskCount(saleTaskMapper.selectCount(Wrappers.lambdaQuery(TicketSaleTask.class)
            .in(TicketSaleTask::getTaskStatus, List.of("failed", "blocked"))));

        overview.setExecutionTotal(orderExecutionMapper.selectCount(Wrappers.lambdaQuery()));
        overview.setRunningExecutionCount(orderExecutionMapper.selectCount(Wrappers.lambdaQuery(TicketOrderExecution.class)
            .in(TicketOrderExecution::getExecutionStatus, List.of("running", "queued"))));
        overview.setSuccessExecutionCount(orderExecutionMapper.selectCount(Wrappers.lambdaQuery(TicketOrderExecution.class)
            .in(TicketOrderExecution::getExecutionStatus, List.of("submitted", "paid", "completed"))));
        overview.setAbnormalExecutionCount(orderExecutionMapper.selectCount(Wrappers.lambdaQuery(TicketOrderExecution.class)
            .in(TicketOrderExecution::getExecutionStatus, List.of("failed", "timeout", "blocked"))));

        overview.setAccountTotal(accountMapper.selectCount(Wrappers.lambdaQuery()));
        overview.setLoggedInAccountCount(accountMapper.selectCount(Wrappers.lambdaQuery(TicketManagedAccount.class)
            .eq(TicketManagedAccount::getLoginStatus, "logged_in")));
        overview.setActivatedAccountCount(accountMapper.selectCount(Wrappers.lambdaQuery(TicketManagedAccount.class)
            .eq(TicketManagedAccount::getAccountStatus, "activated")));
        overview.setAccountErrorCount(accountMapper.selectCount(Wrappers.lambdaQuery(TicketManagedAccount.class)
            .and(wrapper -> wrapper.eq(TicketManagedAccount::getLoginStatus, "login_failed")
                .or()
                .isNotNull(TicketManagedAccount::getLastError)
                .ne(TicketManagedAccount::getLastError, ""))));

        overview.setMailboxTotal(mailboxAccountMapper.selectCount(Wrappers.lambdaQuery()));
        overview.setMailboxErrorCount(mailboxAccountMapper.selectCount(Wrappers.lambdaQuery(TicketMailboxAccount.class)
            .and(wrapper -> wrapper.isNotNull(TicketMailboxAccount::getLastError)
                .ne(TicketMailboxAccount::getLastError, "")
                .or()
                .isNotNull(TicketMailboxAccount::getLastMailSyncError)
                .ne(TicketMailboxAccount::getLastMailSyncError, ""))));
        overview.setUnusedMailboxCount(mailboxAccountMapper.selectCount(Wrappers.lambdaQuery(TicketMailboxAccount.class)
            .isNull(TicketMailboxAccount::getUsedAccountId)));

        overview.setRecentTasks(selectSaleTaskPage(new TicketSaleTaskBo(), dashboardPage(8)).getRows());
        overview.setRecentExecutions(selectOrderExecutionPage(new TicketOrderExecutionBo(), dashboardPage(6)).getRows());
        overview.setRecentRegistrationBatches(selectRegistrationBatchPage(new TicketRegistrationBatchBo(), dashboardPage(6)).getRows());
        overview.setRecentLoginBatches(selectLoginBatchPage(new TicketLoginBatchBo(), dashboardPage(6)).getRows());
        return overview;
    }

    @Override
    public TableDataInfo<TicketPlatformConfigVo> selectPlatformPage(TicketPlatformConfigBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketPlatformConfig> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StringUtils.isNotBlank(bo.getPlatformCode()), TicketPlatformConfig::getPlatformCode, bo.getPlatformCode())
            .like(StringUtils.isNotBlank(bo.getPlatformName()), TicketPlatformConfig::getPlatformName, bo.getPlatformName())
            .eq(ObjectUtil.isNotNull(bo.getEnabled()), TicketPlatformConfig::getEnabled, bo.getEnabled())
            .orderByDesc(TicketPlatformConfig::getPlatformId);
        Page<TicketPlatformConfigVo> page = platformMapper.selectVoPage(pageQuery.build(), wrapper);
        return TableDataInfo.build(page);
    }

    @Override
    public TicketPlatformConfigVo selectPlatformById(Long platformId) {
        return platformMapper.selectVoById(platformId);
    }

    @Override
    public TicketPurchaseTemplateVo getPurchaseTemplate(Long platformId, String purchaseType) {
        TicketPlatformConfig platform = requirePlatform(platformId);
        TicketPlatformAdapter adapter = adapterRegistry.getAdapter(platform.getAdapterType());
        return adapter.getPurchaseTemplate(platform, TicketOrderFlowSupport.defaultPurchaseType(purchaseType));
    }

    @Override
    public int savePlatform(TicketPlatformConfigBo bo) {
        TicketPlatformConfig entity = MapstructUtils.convert(bo, TicketPlatformConfig.class);
        entity.setAdapterType(resolvePlatformAdapterType(entity.getPlatformCode(), entity.getAdapterType()));
        applyPlatformCapabilityDefaults(entity);
        if (StringUtils.isBlank(entity.getEnvironment())) {
            entity.setEnvironment("sandbox");
        }
        if (ObjectUtil.isNull(entity.getEnabled())) {
            entity.setEnabled(Boolean.TRUE);
        }
        int rows = platformMapper.insert(entity);
        recordAudit("platform", "create", "platform", String.valueOf(entity.getPlatformId()), "success", "Platform created", bo);
        return rows;
    }

    @Override
    public int updatePlatform(TicketPlatformConfigBo bo) {
        TicketPlatformConfig entity = MapstructUtils.convert(bo, TicketPlatformConfig.class);
        entity.setAdapterType(resolvePlatformAdapterType(entity.getPlatformCode(), entity.getAdapterType()));
        applyPlatformCapabilityDefaults(entity);
        if (StringUtils.isBlank(entity.getEnvironment())) {
            entity.setEnvironment("sandbox");
        }
        int rows = platformMapper.updateById(entity);
        recordAudit("platform", "update", "platform", String.valueOf(entity.getPlatformId()), "success", "Platform updated", bo);
        return rows;
    }

    @Override
    public int removePlatforms(Long[] platformIds) {
        int rows = platformMapper.deleteByIds(Arrays.asList(platformIds));
        recordAudit("platform", "remove", "platform", Arrays.toString(platformIds), "success", "Platforms removed", platformIds);
        return rows;
    }

    @Override
    public TableDataInfo<TicketPhoneNumberVo> selectPhonePage(TicketPhoneNumberBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketPhoneNumber> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StringUtils.isNotBlank(bo.getPhoneNumber()), TicketPhoneNumber::getPhoneNumber, bo.getPhoneNumber())
            .eq(StringUtils.isNotBlank(bo.getCountryCode()), TicketPhoneNumber::getCountryCode, bo.getCountryCode())
            .eq(StringUtils.isNotBlank(bo.getSupplier()), TicketPhoneNumber::getSupplier, bo.getSupplier())
            .eq(StringUtils.isNotBlank(bo.getStatus()), TicketPhoneNumber::getStatus, bo.getStatus())
            .orderByDesc(TicketPhoneNumber::getPhoneId);
        Page<TicketPhoneNumberVo> page = phoneMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichPhonePage(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketPhoneBulkImportResultVo importPhones(TicketPhoneBulkImportBo bo) {
        List<String> numbers = Arrays.stream(StringUtils.defaultString(bo.getNumbers()).split("\\r?\\n"))
            .map(item -> item.replace(" ", "").trim())
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
        TicketPhoneBulkImportResultVo resultVo = new TicketPhoneBulkImportResultVo();
        resultVo.setTotalCount(numbers.size());
        if (CollUtil.isEmpty(numbers)) {
            resultVo.setImportedCount(0);
            resultVo.setSkippedCount(0);
            resultVo.setSkippedNumbers(List.of());
            return resultVo;
        }

        Set<String> exists = phoneMapper.selectList(new LambdaQueryWrapper<TicketPhoneNumber>()
                .select(TicketPhoneNumber::getPhoneNumber)
                .in(TicketPhoneNumber::getPhoneNumber, numbers))
            .stream()
            .map(TicketPhoneNumber::getPhoneNumber)
            .collect(Collectors.toSet());

        List<TicketPhoneNumber> entities = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String number : numbers) {
            if (exists.contains(number)) {
                skipped.add(number);
                continue;
            }
            TicketPhoneNumber entity = new TicketPhoneNumber();
            entity.setPhoneNumber(number);
            entity.setCountryCode(bo.getCountryCode());
            entity.setSupplier(bo.getSupplier());
            entity.setStatus(StringUtils.defaultIfBlank(bo.getStatus(), "available"));
            entity.setNote(bo.getNote());
            entities.add(entity);
        }
        if (CollUtil.isNotEmpty(entities)) {
            phoneMapper.insertBatch(entities);
        }
        resultVo.setImportedCount(entities.size());
        resultVo.setSkippedCount(skipped.size());
        resultVo.setSkippedNumbers(skipped);
        recordAudit("phone", "bulkImport", "phone", String.valueOf(entities.size()), "success", "号码批量导入完成", resultVo);
        return resultVo;
    }

    private PageQuery dashboardPage(int pageSize) {
        return new PageQuery(1, pageSize);
    }

    @Override
    public boolean changePhoneStatus(TicketPhoneStatusBo bo) {
        if (CollUtil.isEmpty(bo.getPhoneIds())) {
            throw new ServiceException("Please select phone numbers to update");
        }
        if (!ENABLED_PHONE_STATUSES.contains(bo.getStatus())) {
            throw new ServiceException("Only enable and disable are supported");
        }
        List<TicketPhoneNumber> phones = phoneMapper.selectByIds(CollUtil.distinct(bo.getPhoneIds()));
        if (CollUtil.isEmpty(phones)) {
            throw new ServiceException("Phone numbers were not found");
        }
        if ("available".equals(bo.getStatus())) {
            boolean hasIllegalStatus = phones.stream().anyMatch(item -> !"disabled".equals(item.getStatus()));
            if (hasIllegalStatus) {
                throw new ServiceException("Only disabled phone numbers can be enabled");
            }
        } else {
            boolean hasIllegalStatus = phones.stream().anyMatch(item -> !"available".equals(item.getStatus()));
            if (hasIllegalStatus) {
                throw new ServiceException("Only available phone numbers can be disabled");
            }
            Long runningCount = relationMapper.selectCount(new LambdaQueryWrapper<TicketPhonePlatformRelation>()
                .in(TicketPhonePlatformRelation::getPhoneId, phones.stream().map(TicketPhoneNumber::getPhoneId).toList())
                .in(TicketPhonePlatformRelation::getStatus, RUNNING_REGISTER_RELATION_STATUSES));
            if (runningCount != null && runningCount > 0) {
                throw new ServiceException("Some phone numbers are registering or waiting for verification");
            }
        }
        int rows = phoneMapper.update(null, new LambdaUpdateWrapper<TicketPhoneNumber>()
            .set(TicketPhoneNumber::getStatus, bo.getStatus())
            .in(TicketPhoneNumber::getPhoneId, phones.stream().map(TicketPhoneNumber::getPhoneId).toList()));
        recordAudit("phone", "changeStatus", "phone", bo.getPhoneIds().toString(), "success", "phone status updated", bo);
        return rows > 0;
    }

    @Override
    public TableDataInfo<TicketPhonePlatformRelationVo> selectRelationPage(TicketPhonePlatformRelationBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketPhonePlatformRelation> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ObjectUtil.isNotNull(bo.getPhoneId()), TicketPhonePlatformRelation::getPhoneId, bo.getPhoneId())
            .eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketPhonePlatformRelation::getPlatformId, bo.getPlatformId())
            .eq(ObjectUtil.isNotNull(bo.getAccountId()), TicketPhonePlatformRelation::getAccountId, bo.getAccountId())
            .eq(StringUtils.isNotBlank(bo.getStatus()), TicketPhonePlatformRelation::getStatus, bo.getStatus())
            .orderByDesc(TicketPhonePlatformRelation::getRelationId);
        Page<TicketPhonePlatformRelationVo> page = relationMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichRelations(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public TableDataInfo<TicketPhoneNumberVo> selectRegisterablePhonePage(Long platformId, TicketPhoneNumberBo bo, PageQuery pageQuery) {
        requirePlatform(platformId);
        LambdaQueryWrapper<TicketPhoneNumber> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StringUtils.isNotBlank(bo.getCountryCode()), TicketPhoneNumber::getCountryCode, bo.getCountryCode())
            .eq(StringUtils.isNotBlank(bo.getSupplier()), TicketPhoneNumber::getSupplier, bo.getSupplier())
            .eq(TicketPhoneNumber::getStatus, "available")
            .like(StringUtils.isNotBlank(bo.getPhoneNumber()), TicketPhoneNumber::getPhoneNumber, bo.getPhoneNumber())
            .orderByDesc(TicketPhoneNumber::getPhoneId);

        List<Long> activeRelationPhoneIds = relationMapper.selectList(new LambdaQueryWrapper<TicketPhonePlatformRelation>()
                .select(TicketPhonePlatformRelation::getPhoneId)
                .eq(TicketPhonePlatformRelation::getPlatformId, platformId)
                .in(TicketPhonePlatformRelation::getStatus, ACTIVE_RELATION_STATUSES))
            .stream()
            .map(TicketPhonePlatformRelation::getPhoneId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (CollUtil.isNotEmpty(activeRelationPhoneIds)) {
            wrapper.notIn(TicketPhoneNumber::getPhoneId, activeRelationPhoneIds);
        }

        Page<TicketPhoneNumberVo> page = phoneMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichPhonePage(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Long> registerFromPhones(Long platformId, TicketBatchRegisterBo bo) {
        TicketPlatformConfig platform = requirePlatform(platformId);
        assertPlatformSupportsBatchRegister(platform);
        assertNoRunningRegistrationBatch(platformId);
        int count = Optional.ofNullable(bo.getCount()).orElse(0);
        if (count <= 0) {
            return R.warn("注册数量必须大于 0");
        }

        TicketRegistrationBatch batch = new TicketRegistrationBatch();
        batch.setPlatformId(platformId);
        batch.setBatchNo("REG-" + System.currentTimeMillis() + RandomUtil.randomNumbers(4));
        batch.setBatchStatus("executing");
        batch.setTotalCount(count);
        batch.setSuccessCount(0);
        batch.setSkippedCount(0);
        batch.setFailedCount(0);
        batch.setResultSummary("[]");
        batch.setExecutedAt(null);
        registrationBatchMapper.insert(batch);

        try {
            if (isJumpShopPlatform(platform)) {
                ticketPythonExecutorClient.startJumpShopRegisterBatch(batch.getBatchId(), platform.getPlatformCode(), count);
            } else {
                ticketPythonExecutorClient.startLivePocketRegisterBatch(batch.getBatchId(), platform.getPlatformCode(), count);
            }
        } catch (Exception ex) {
            String message = "批量注册提交 Python 失败: " + StringUtils.defaultString(ex.getMessage(), "未知错误");
            registrationBatchMapper.update(null, Wrappers.lambdaUpdate(TicketRegistrationBatch.class)
                .eq(TicketRegistrationBatch::getBatchId, batch.getBatchId())
                .set(TicketRegistrationBatch::getBatchStatus, "blocked")
                .set(TicketRegistrationBatch::getFailedCount, count)
                .set(TicketRegistrationBatch::getResultSummary, JSONUtil.toJsonStr(List.of(buildDetail(null, "failed", message))))
                .set(TicketRegistrationBatch::getExecutedAt, new Date()));
            recordAudit("registration", "startBatch", "registrationBatch", String.valueOf(batch.getBatchId()), "failed", message, batch);
            throw new ServiceException(message);
        }

        recordAudit("registration", "startBatch", "registrationBatch", String.valueOf(batch.getBatchId()), "success", "Python 批量注册已提交", batch);
        return R.ok("批量注册已提交", batch.getBatchId());
    }

    @SuppressWarnings("unused")
    @Transactional(rollbackFor = Exception.class)
    protected R<Long> registerFromPhonesLegacy(Long platformId, TicketBatchRegisterBo bo) {
        TicketPlatformConfig platform = requirePlatform(platformId);
        List<TicketPhoneNumber> phones = loadPhonesForRegister(bo);
        if (CollUtil.isEmpty(phones)) {
            return R.warn("号码列表为空");
        }

        TicketPlatformAdapter adapter = adapterRegistry.getAdapter(platform.getAdapterType());
        List<TicketPhoneNumber> toRegister = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();
        int skipped = 0;
        for (TicketPhoneNumber phone : phones) {
            TicketPhonePlatformRelation relation = getRelation(platformId, phone.getPhoneId());
            if (relation != null && ACTIVE_RELATION_STATUSES.contains(relation.getStatus())) {
                skipped++;
                details.add(buildDetail(phone.getPhoneId(), "skipped", "该平台已存在有效注册关系"));
                continue;
            }
            TicketPhonePlatformRelation pendingRelation = relation == null ? new TicketPhonePlatformRelation() : relation;
            pendingRelation.setPhoneId(phone.getPhoneId());
            pendingRelation.setPlatformId(platformId);
            pendingRelation.setStatus("registering");
            pendingRelation.setLastError(null);
            pendingRelation.setLastOperateTime(new Date());
            saveRelation(pendingRelation);
            toRegister.add(phone);
        }

        List<TicketRegisterResult> results = adapter.batchRegister(platform, toRegister);
        Map<Long, TicketRegisterResult> resultMap = results.stream()
            .collect(Collectors.toMap(TicketRegisterResult::getPhoneId, Function.identity(), (left, right) -> right));

        int success = 0;
        int failed = 0;
        for (TicketPhoneNumber phone : toRegister) {
            TicketPhonePlatformRelation relation = getRelation(platformId, phone.getPhoneId());
            TicketRegisterResult result = resultMap.get(phone.getPhoneId());
            if (result != null && result.isSuccess()) {
                TicketManagedAccount account = getOrCreateAccount(platformId, phone.getPhoneId());
                account.setPlatformId(platformId);
                account.setPhoneId(phone.getPhoneId());
                account.setEmail(result.getEmail());
                account.setAccountInfo(result.getAccountInfo());
                account.setReqData(result.getReqData());
                account.setAccountStatus("registered");
                account.setLoginStatus("offline");
                account.setLastError(null);
                saveAccount(account);

                relation.setAccountId(account.getAccountId());
                relation.setStatus("registered");
                relation.setLastError(null);
                relation.setLastOperateTime(new Date());
                saveRelation(relation);

                success++;
                details.add(buildDetail(phone.getPhoneId(), "success", result.getMessage()));
            } else {
                String error = adapter.normalizeError(result == null ? "register_result_missing" : result.getMessage());
                relation.setStatus("register_failed");
                relation.setLastError(error);
                relation.setLastOperateTime(new Date());
                saveRelation(relation);
                failed++;
                details.add(buildDetail(phone.getPhoneId(), "failed", error));
            }
        }

        TicketRegistrationBatch batch = new TicketRegistrationBatch();
        batch.setPlatformId(platformId);
        batch.setBatchNo("REG-" + System.currentTimeMillis() + RandomUtil.randomNumbers(4));
        batch.setBatchStatus(failed > 0 ? "partial" : "completed");
        batch.setTotalCount(phones.size());
        batch.setSuccessCount(success);
        batch.setSkippedCount(skipped);
        batch.setFailedCount(failed);
        batch.setResultSummary(JSONUtil.toJsonStr(details));
        batch.setExecutedAt(new Date());
        registrationBatchMapper.insert(batch);

        recordAudit("registration", "batchRegister", "registrationBatch", String.valueOf(batch.getBatchId()), "success", "Registration batch completed", batch);
        return R.ok("Registration batch started", batch.getBatchId());
    }

    @Override
    public TableDataInfo<TicketManagedAccountVo> selectAccountPage(TicketManagedAccountBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketManagedAccount> wrapper = buildAccountQueryWrapper(bo);
        Page<TicketManagedAccountVo> page = accountMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichAccounts(page.getRecords(), bo);
        return TableDataInfo.build(page);
    }

    @Override
    public TicketSelectableAccountIdsVo selectSelectableAccountIds(TicketManagedAccountBo bo) {
        TicketManagedAccountBo query = bo == null ? new TicketManagedAccountBo() : bo;
        LambdaQueryWrapper<TicketManagedAccount> wrapper = buildAccountQueryWrapper(query)
            .select(TicketManagedAccount::getAccountId, TicketManagedAccount::getPlatformId);
        List<TicketManagedAccount> accounts = accountMapper.selectList(wrapper);
        TicketSelectableAccountIdsVo result = new TicketSelectableAccountIdsVo();
        result.setTotalCount(accounts.size());
        if (CollUtil.isEmpty(accounts)) {
            result.setAccountIds(List.of());
            result.setAvailableCount(0);
            result.setOccupiedCount(0);
            return result;
        }
        Set<Long> occupiedIds = Set.of();
        if (TicketOrderFlowSupport.isLottery(query.getPurchaseType()) && StringUtils.isNotBlank(query.getLotteryEventUrl())) {
            Map<Long, TicketLotteryLinkOccupancyService.OccupancyInfo> occupiedMap = ticketLotteryLinkOccupancyService.query(
                accounts.stream().map(TicketManagedAccount::getAccountId).filter(Objects::nonNull).toList(),
                ObjectUtil.isNotNull(query.getPlatformId())
                    ? List.of(query.getPlatformId())
                    : accounts.stream().map(TicketManagedAccount::getPlatformId).filter(Objects::nonNull).distinct().toList(),
                query.getLotteryEventUrl(),
                null
            );
            occupiedIds = occupiedMap.keySet();
        }
        Set<Long> occupiedIdSet = occupiedIds;
        List<Long> accountIds = accounts.stream()
            .map(TicketManagedAccount::getAccountId)
            .filter(Objects::nonNull)
            .filter(accountId -> !occupiedIdSet.contains(accountId))
            .toList();
        result.setAccountIds(accountIds);
        result.setAvailableCount(accountIds.size());
        result.setOccupiedCount(Math.max(0, result.getTotalCount() - accountIds.size()));
        return result;
    }

    private LambdaQueryWrapper<TicketManagedAccount> buildAccountQueryWrapper(TicketManagedAccountBo bo) {
        TicketManagedAccountBo query = bo == null ? new TicketManagedAccountBo() : bo;
        return Wrappers.lambdaQuery(TicketManagedAccount.class)
            .eq(ObjectUtil.isNotNull(query.getAccountId()), TicketManagedAccount::getAccountId, query.getAccountId())
            .eq(ObjectUtil.isNotNull(query.getPlatformId()), TicketManagedAccount::getPlatformId, query.getPlatformId())
            .eq(ObjectUtil.isNotNull(query.getPhoneId()), TicketManagedAccount::getPhoneId, query.getPhoneId())
            .like(StringUtils.isNotBlank(query.getEmail()), TicketManagedAccount::getEmail, query.getEmail())
            .eq(StringUtils.isNotBlank(query.getAccountStatus()), TicketManagedAccount::getAccountStatus, query.getAccountStatus())
            .eq(StringUtils.isNotBlank(query.getLoginStatus()), TicketManagedAccount::getLoginStatus, query.getLoginStatus())
            .orderByDesc(TicketManagedAccount::getAccountId);
    }

    @Override
    public TableDataInfo<TicketPhoneNumberVo> selectBindablePhonePage(Long platformId, TicketPhoneNumberBo bo, PageQuery pageQuery) {
        if (platformId == null) {
            return new TableDataInfo<>(List.of(), 0);
        }
        TicketPlatformConfig platform = requirePlatform(platformId);
        if (!platformSupportsPhoneIdentity(platform)) {
            return new TableDataInfo<>(List.of(), 0);
        }
        return selectRegisterablePhonePage(platformId, bo, pageQuery);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int createManagedAccount(TicketManagedAccountCreateBo bo) {
        TicketPlatformConfig platform = requirePlatform(bo.getPlatformId());
        boolean supportsPhoneIdentity = platformSupportsPhoneIdentity(platform);

        String email = StringUtils.trim(bo.getEmail());
        if (StringUtils.isBlank(email)) {
            throw new ServiceException("邮箱不能为空");
        }

        String accountInfo = normalizeJsonText(bo.getAccountInfo());
        boolean externalPasswordAccount = supportsPhoneIdentity && ObjectUtil.isNull(bo.getPhoneId()) && hasAccountPlatformPassword(accountInfo);
        TicketPhoneNumber phone = null;
        if (supportsPhoneIdentity) {
            if (ObjectUtil.isNull(bo.getPhoneId())) {
                if (!externalPasswordAccount) {
                    throw new ServiceException("来源号码不能为空；外部邮箱账号请填写平台密码");
                }
            } else {
                phone = phoneMapper.selectById(bo.getPhoneId());
                if (phone == null) {
                    throw new ServiceException("号码不存在");
                }
                if (!"available".equals(phone.getStatus())) {
                    throw new ServiceException("号码不可用");
                }
            }
        }

        long emailExists = accountMapper.selectCount(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platform.getPlatformId())
            .eq(TicketManagedAccount::getEmail, email));
        if (emailExists > 0) {
            throw new ServiceException("同平台下邮箱已存在");
        }

        TicketPhonePlatformRelation relation = null;
        if (supportsPhoneIdentity && phone != null) {
            relation = getRelation(platform.getPlatformId(), phone.getPhoneId());
            if (relation != null && ACTIVE_RELATION_STATUSES.contains(relation.getStatus())) {
                throw new ServiceException("该号码在当前平台已存在有效关系");
            }
        }

        TicketManagedAccount account = new TicketManagedAccount();
        account.setPlatformId(platform.getPlatformId());
        account.setPhoneId(phone == null ? null : phone.getPhoneId());
        account.setEmail(email);
        account.setAccountInfo(accountInfo);
        account.setReqData(normalizeJsonText(bo.getReqData()));
        account.setLoginReqData(normalizeJsonText(bo.getLoginReqData()));
        account.setAccountStatus(externalPasswordAccount || !supportsPhoneIdentity ? "activated" : "registered");
        account.setLoginStatus("offline");
        account.setLastLoginTime(null);
        account.setLastError(null);
        saveAccount(account);

        if (supportsPhoneIdentity && phone != null) {
            TicketPhonePlatformRelation targetRelation = relation == null ? new TicketPhonePlatformRelation() : relation;
            targetRelation.setPhoneId(phone.getPhoneId());
            targetRelation.setPlatformId(platform.getPlatformId());
            targetRelation.setAccountId(account.getAccountId());
            targetRelation.setStatus("registered");
            targetRelation.setLastError(null);
            targetRelation.setLastOperateTime(new Date());
            saveRelation(targetRelation);
        }

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("platformId", platform.getPlatformId());
        auditPayload.put("phoneId", phone == null ? null : phone.getPhoneId());
        auditPayload.put("accountId", account.getAccountId());
        auditPayload.put("email", account.getEmail());
        recordAudit("account", "create", "account", String.valueOf(account.getAccountId()), "success", "账号已创建", auditPayload);
        return 1;
    }

    @Override
    public TicketHandsFormQuickCreateResultVo quickCreateHandsFormAccounts(TicketHandsFormQuickCreateBo bo) {
        TicketPlatformConfig platform = findPlatformByCode(HANDS_FORM_PLATFORM_CODE);
        if (platform == null) {
            throw new ServiceException("未找到 hands-form 平台配置");
        }
        TicketHandsFormQuickCreateResultVo result = new TicketHandsFormQuickCreateResultVo();
        int requestedCount = bo.getCount() == null ? 0 : bo.getCount();
        result.setRequestedCount(requestedCount);
        for (int index = 0; index < requestedCount; index++) {
            boolean created = false;
            String lastMessage = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    TicketManagedAccount account = transactionTemplate.execute(status -> quickCreateSingleHandsFormAccount(platform));
                    if (account != null) {
                        created = true;
                        result.setSuccessCount(result.getSuccessCount() + 1);
                        result.getCreatedAccountIds().add(account.getAccountId());
                        result.getCreatedEmails().add(account.getEmail());
                        break;
                    }
                    lastMessage = "创建结果为空";
                } catch (Exception ex) {
                    lastMessage = StringUtils.defaultIfBlank(ex.getMessage(), "未知错误");
                    log.warn("quick create hands-form account failed, attempt={}, requestedIndex={}", attempt + 1, index + 1, ex);
                }
            }
            if (!created) {
                result.setFailedCount(result.getFailedCount() + 1);
                result.getFailedMessages().add("第 " + (index + 1) + " 个账号创建失败：" + StringUtils.defaultIfBlank(lastMessage, "未知错误"));
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateManagedAccount(TicketManagedAccountUpdateBo bo) {
        TicketManagedAccount account = accountMapper.selectById(bo.getAccountId());
        if (account == null) {
            throw new ServiceException("账号不存在");
        }
        TicketPlatformConfig platform = requirePlatform(account.getPlatformId());
        boolean supportsPhoneIdentity = platformSupportsPhoneIdentity(platform);

        String email = StringUtils.trim(bo.getEmail());
        if (StringUtils.isBlank(email)) {
            throw new ServiceException("邮箱不能为空");
        }

        long emailExists = accountMapper.selectCount(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, account.getPlatformId())
            .eq(TicketManagedAccount::getEmail, email)
            .ne(TicketManagedAccount::getAccountId, account.getAccountId()));
        if (emailExists > 0) {
            throw new ServiceException("同平台下邮箱已存在");
        }

        String accountStatus = StringUtils.defaultIfBlank(bo.getAccountStatus(), account.getAccountStatus());
        String loginStatus = StringUtils.defaultIfBlank(bo.getLoginStatus(), account.getLoginStatus());
        if (!ACCOUNT_STATUSES.contains(accountStatus)) {
            throw new ServiceException("账号状态不合法");
        }
        if (!LOGIN_STATUSES.contains(loginStatus)) {
            throw new ServiceException("登录状态不合法");
        }

        Long originalPhoneId = account.getPhoneId();
        Long targetPhoneId = supportsPhoneIdentity ? bo.getPhoneId() : null;
        TicketPhonePlatformRelation targetRelation = null;
        String accountInfo = normalizeJsonText(bo.getAccountInfo());
        boolean externalPasswordAccount = supportsPhoneIdentity && ObjectUtil.isNull(targetPhoneId) && hasAccountPlatformPassword(accountInfo);
        if (supportsPhoneIdentity) {
            if (ObjectUtil.isNull(targetPhoneId)) {
                if (!externalPasswordAccount) {
                    throw new ServiceException("来源号码不能为空；外部邮箱账号请填写平台密码");
                }
            } else {
                TicketPhoneNumber phone = phoneMapper.selectById(targetPhoneId);
                if (phone == null) {
                    throw new ServiceException("号码不存在");
                }
                if (!Objects.equals(originalPhoneId, targetPhoneId) && !"available".equals(phone.getStatus())) {
                    throw new ServiceException("号码不可用");
                }
                targetRelation = getRelation(platform.getPlatformId(), targetPhoneId);
                if (
                    targetRelation != null &&
                        ACTIVE_RELATION_STATUSES.contains(targetRelation.getStatus()) &&
                        !Objects.equals(targetRelation.getAccountId(), account.getAccountId())
                ) {
                    throw new ServiceException("该号码在当前平台已存在有效关系");
                }
            }
        }

        boolean newlyLoggedIn = "logged_in".equals(loginStatus) && !"logged_in".equals(account.getLoginStatus());
        String reqData = normalizeJsonText(bo.getReqData());
        String loginReqData = normalizeJsonText(bo.getLoginReqData());
        String lastError = normalizeLastError(bo.getLastError());
        account.setEmail(email);
        account.setPhoneId(targetPhoneId);
        account.setAccountInfo(accountInfo);
        account.setReqData(reqData);
        account.setLoginReqData(loginReqData);
        account.setAccountStatus(accountStatus);
        account.setLoginStatus(loginStatus);
        account.setLastError(lastError);
        if (newlyLoggedIn) {
            account.setLastLoginTime(new Date());
        }
        LambdaUpdateWrapper<TicketManagedAccount> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(TicketManagedAccount::getAccountId, account.getAccountId())
            .set(TicketManagedAccount::getEmail, email)
            .set(TicketManagedAccount::getPhoneId, targetPhoneId)
            .set(TicketManagedAccount::getAccountInfo, accountInfo)
            .set(TicketManagedAccount::getReqData, reqData)
            .set(TicketManagedAccount::getLoginReqData, loginReqData)
            .set(TicketManagedAccount::getAccountStatus, accountStatus)
            .set(TicketManagedAccount::getLoginStatus, loginStatus)
            .set(TicketManagedAccount::getLastError, lastError);
        if (newlyLoggedIn) {
            updateWrapper.set(TicketManagedAccount::getLastLoginTime, account.getLastLoginTime());
        }
        accountMapper.update(null, updateWrapper);

        Date relationOperateTime = new Date();
        if (originalPhoneId != null && !Objects.equals(originalPhoneId, targetPhoneId)) {
            TicketPhonePlatformRelation originalRelation = getRelation(account.getPlatformId(), originalPhoneId);
            if (originalRelation != null && Objects.equals(originalRelation.getAccountId(), account.getAccountId())) {
                originalRelation.setAccountId(null);
                originalRelation.setStatus("available");
                originalRelation.setLastError(null);
                originalRelation.setLastOperateTime(relationOperateTime);
                saveRelation(originalRelation);
            }
        }
        if (supportsPhoneIdentity && targetPhoneId != null) {
            TicketPhonePlatformRelation relation = targetRelation == null ? new TicketPhonePlatformRelation() : targetRelation;
            relation.setPlatformId(account.getPlatformId());
            relation.setPhoneId(targetPhoneId);
            relation.setAccountId(account.getAccountId());
            relation.setStatus(resolveRelationStatus(accountStatus, loginStatus));
            relation.setLastError(account.getLastError());
            relation.setLastOperateTime(relationOperateTime);
            saveRelation(relation);
        }

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("platformId", account.getPlatformId());
        auditPayload.put("phoneId", account.getPhoneId());
        auditPayload.put("accountId", account.getAccountId());
        auditPayload.put("email", account.getEmail());
        auditPayload.put("accountStatus", account.getAccountStatus());
        auditPayload.put("loginStatus", account.getLoginStatus());
        recordAudit("account", "update", "account", String.valueOf(account.getAccountId()), "success", "账号已更新", auditPayload);
        return 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateManagedAccountLastName(Long accountId, TicketManagedAccountLastNameBo bo) {
        if (accountId == null) {
            throw new ServiceException("账号ID不能为空");
        }
        String lastName = normalizeLivePocketLastNameInput(bo.getLastName());
        if (lastName.isEmpty()) {
            throw new ServiceException("姓不能为空");
        }
        TicketManagedAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new ServiceException("账号不存在");
        }
        if (StringUtils.isBlank(account.getLoginReqData())) {
            updateAccountLastError(accountId, LIVEPOCKET_LAST_NAME_LOGIN_CONTEXT_REQUIRED_MESSAGE);
            throw new ServiceException(LIVEPOCKET_LAST_NAME_LOGIN_CONTEXT_REQUIRED_MESSAGE);
        }

        String pendingMessage = "LivePocket 姓氏修改中: " + lastName;
        updateAccountLastError(accountId, pendingMessage);
        registerLastNameUpdateAfterCommit(accountId, account.getTenantId(), lastName);
        recordAudit("account", "updateLastName", "account", String.valueOf(accountId), "running", "LivePocket 姓氏修改已提交", buildAccountLastNameAuditPayload(account, lastName, pendingMessage));
        return 1;
    }

    private void executeManagedAccountLastNameUpdate(Long accountId, String lastName) {
        TicketManagedAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            log.warn("async update livepocket last name skipped, account not found, accountId={}", accountId);
            return;
        }
        if (StringUtils.isBlank(account.getLoginReqData())) {
            updateAccountLastError(accountId, LIVEPOCKET_LAST_NAME_LOGIN_CONTEXT_REQUIRED_MESSAGE);
            recordAudit("account", "updateLastName", "account", String.valueOf(accountId), "failed", "LivePocket 姓氏更新失败", buildAccountLastNameAuditPayload(account, lastName, LIVEPOCKET_LAST_NAME_LOGIN_CONTEXT_REQUIRED_MESSAGE));
            return;
        }
        String password = resolveAccountLoginPassword(account);
        try {
            Map<String, Object> result = ticketPythonExecutorClient.updateLivePocketProfileLastName(account.getEmail(), password, lastName, account.getLoginReqData());
            String refreshedLoginReqData = Convert.toStr(result.get("loginReqData"));
            if (StringUtils.isNotBlank(refreshedLoginReqData)) {
                account.setLoginReqData(refreshedLoginReqData);
            }
        } catch (ServiceException ex) {
            updateAccountLastError(accountId, ex.getMessage());
            recordAudit("account", "updateLastName", "account", String.valueOf(accountId), "failed", "LivePocket 姓氏更新失败", buildAccountLastNameAuditPayload(account, lastName, ex.getMessage()));
            return;
        } catch (Exception ex) {
            String message = "LivePocket 姓氏更新异常: " + ex.getMessage();
            updateAccountLastError(accountId, message);
            recordAudit("account", "updateLastName", "account", String.valueOf(accountId), "failed", "LivePocket 姓氏更新失败", buildAccountLastNameAuditPayload(account, lastName, message));
            log.error("async update livepocket last name failed, accountId={}", accountId, ex);
            return;
        }

        String accountInfo = mergeAccountLastName(account.getAccountInfo(), lastName);
        accountMapper.update(null, Wrappers.lambdaUpdate(TicketManagedAccount.class)
            .eq(TicketManagedAccount::getAccountId, accountId)
            .set(TicketManagedAccount::getAccountInfo, accountInfo)
            .set(StringUtils.isNotBlank(account.getLoginReqData()), TicketManagedAccount::getLoginReqData, account.getLoginReqData())
            .set(StringUtils.isNotBlank(account.getLoginReqData()), TicketManagedAccount::getLoginStatus, "logged_in")
            .set(StringUtils.isNotBlank(account.getLoginReqData()), TicketManagedAccount::getLastLoginTime, new Date())
            .set(TicketManagedAccount::getLastError, null));
        recordAudit("account", "updateLastName", "account", String.valueOf(accountId), "success", "LivePocket 姓氏已更新", buildAccountLastNameAuditPayload(account, lastName, null));
    }

    static String normalizeLivePocketLastNameInput(String rawLastName) {
        if (rawLastName == null) {
            return "";
        }
        String decoded = decodeUnicodeCodePointTokens(rawLastName);
        return trimRegularInputSpaces(decoded);
    }

    private static String decodeUnicodeCodePointTokens(String value) {
        java.util.regex.Matcher matcher = UNICODE_CODE_POINT_TOKEN.matcher(value);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            int codePoint;
            try {
                codePoint = Integer.parseInt(matcher.group(1), 16);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (!Character.isValidCodePoint(codePoint)) {
                continue;
            }
            matcher.appendReplacement(builder, java.util.regex.Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private static String trimRegularInputSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) <= ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    private void registerLastNameUpdateAfterCommit(Long accountId, String tenantId, String lastName) {
        Runnable updateAction = () -> scheduledExecutorService.execute(() -> {
            try {
                TenantHelper.dynamic(tenantId, () -> transactionTemplate.executeWithoutResult(
                    status -> executeManagedAccountLastNameUpdate(accountId, lastName)
                ));
            } catch (Exception ex) {
                log.error("schedule livepocket last name update failed, accountId={}", accountId, ex);
                TenantHelper.dynamic(tenantId, () -> updateAccountLastError(accountId, "LivePocket 姓氏更新异常: " + ex.getMessage()));
            }
        });
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            updateAction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                updateAction.run();
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeManagedAccounts(Long[] accountIds) {
        List<Long> ids = Arrays.stream(Optional.ofNullable(accountIds).orElse(new Long[0]))
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return 0;
        }

        Date now = new Date();
        relationMapper.update(null, Wrappers.<TicketPhonePlatformRelation>lambdaUpdate()
            .in(TicketPhonePlatformRelation::getAccountId, ids)
            .set(TicketPhonePlatformRelation::getStatus, "register_failed")
            .set(TicketPhonePlatformRelation::getLastError, "账号已删除")
            .set(TicketPhonePlatformRelation::getLastOperateTime, now));

        mailboxAccountMapper.update(null, Wrappers.<TicketMailboxAccount>lambdaUpdate()
            .in(TicketMailboxAccount::getUsedAccountId, ids)
            .eq(TicketMailboxAccount::getStatus, "used")
            .set(TicketMailboxAccount::getStatus, "available")
            .set(TicketMailboxAccount::getUsedAccountId, null)
            .set(TicketMailboxAccount::getUsedTime, null)
            .set(TicketMailboxAccount::getLastError, null));

        saleTaskAccountMapper.delete(Wrappers.<TicketSaleTaskAccount>lambdaQuery()
            .in(TicketSaleTaskAccount::getAccountId, ids));

        int rows = 0;
        for (Long accountId : ids) {
            rows += accountMapper.update(null, Wrappers.<TicketManagedAccount>lambdaUpdate()
                .eq(TicketManagedAccount::getAccountId, accountId)
                .eq(TicketManagedAccount::getDelFlag, 0L)
                .set(TicketManagedAccount::getDelFlag, accountId));
        }
        recordAudit("account", "remove", "account", ids.toString(), "success", "账号已删除", ids);
        return rows;
    }

    @Override
    public TableDataInfo<TicketManagedAccountVo> selectLoginableAccountPage(Long platformId, TicketManagedAccountBo bo, PageQuery pageQuery) {
        TicketPlatformConfig platform = requirePlatform(platformId);
        boolean allowRegistered = isJumpShopPlatform(platform);
        LambdaQueryWrapper<TicketManagedAccount> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TicketManagedAccount::getPlatformId, platformId)
            .eq(ObjectUtil.isNotNull(bo.getAccountId()), TicketManagedAccount::getAccountId, bo.getAccountId())
            .like(StringUtils.isNotBlank(bo.getEmail()), TicketManagedAccount::getEmail, bo.getEmail())
            .eq(StringUtils.isNotBlank(bo.getLoginStatus()), TicketManagedAccount::getLoginStatus, bo.getLoginStatus())
            .in(TicketManagedAccount::getAccountStatus, allowRegistered ? List.of("activated", "registered") : List.of("activated"))
            .orderByDesc(TicketManagedAccount::getAccountId);
        Page<TicketManagedAccountVo> page = accountMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichAccounts(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Long> loginAccounts(Long platformId, TicketBatchLoginBo bo) {
        TicketPlatformConfig platform = requirePlatform(platformId);
        assertPlatformSupportsBatchLogin(platform);
        String loginMode = normalizeLoginMode(bo.getLoginMode());
        List<TicketManagedAccount> accounts = loadAccountsForLogin(platformId, bo);
        if (CollUtil.isEmpty(accounts)) {
            return R.warn("没有可登录的账号");
        }
        accounts = sortAccountsForLogin(accounts);

        TicketLoginBatch batch = new TicketLoginBatch();
        batch.setPlatformId(platformId);
        batch.setBatchNo("LOGIN-" + System.currentTimeMillis() + RandomUtil.randomNumbers(4));
        batch.setBatchStatus("executing");
        batch.setTotalCount(accounts.size());
        batch.setSuccessCount(0);
        batch.setFailedCount(0);
        batch.setResultSummary("[]");
        batch.setExecutedAt(null);
        loginBatchMapper.insert(batch);

        List<Map<String, Object>> accountPayloads = new ArrayList<>();
        for (TicketManagedAccount account : accounts) {
            upsertLoginDetail(
                batch.getBatchId(),
                account.getAccountId(),
                platformId,
                "processing",
                "已提交 Python 登录队列",
                buildLoginDetailReqData(account.getLoginReqData(), loginMode)
            );
            accountPayloads.add(buildPythonLoginAccountPayload(account, loginMode));
        }

        try {
            if (isJumpShopPlatform(platform)) {
                ticketPythonExecutorClient.startJumpShopLoginBatch(batch.getBatchId(), platform.getPlatformCode(), accountPayloads);
            } else {
                ticketPythonExecutorClient.startLivePocketLoginBatch(batch.getBatchId(), platform.getPlatformCode(), accountPayloads);
            }
        } catch (Exception ex) {
            String message = "批量登录提交 Python 失败: " + StringUtils.defaultString(ex.getMessage(), "未知错误");
            loginBatchMapper.update(null, Wrappers.lambdaUpdate(TicketLoginBatch.class)
                .eq(TicketLoginBatch::getBatchId, batch.getBatchId())
                .set(TicketLoginBatch::getBatchStatus, "blocked")
                .set(TicketLoginBatch::getFailedCount, accounts.size())
                .set(TicketLoginBatch::getResultSummary, JSONUtil.toJsonStr(List.of(buildDetail(null, "failed", message))))
                .set(TicketLoginBatch::getExecutedAt, new Date()));
            for (TicketManagedAccount account : accounts) {
                upsertLoginDetail(batch.getBatchId(), account.getAccountId(), platformId, "failed", message, account.getLoginReqData());
            }
            recordAudit("login", "startBatch", "loginBatch", String.valueOf(batch.getBatchId()), "failed", message, batch);
            throw new ServiceException(message);
        }

        recordAudit("login", "startBatch", "loginBatch", String.valueOf(batch.getBatchId()), "success", "Python 批量登录已提交", batch);
        return R.ok("批量登录已提交", batch.getBatchId());
    }

    private void assertNoRunningRegistrationBatch(Long platformId) {
        TicketRegistrationBatch runningBatch = registrationBatchMapper.selectOne(new LambdaQueryWrapper<TicketRegistrationBatch>()
            .select(TicketRegistrationBatch::getBatchId, TicketRegistrationBatch::getBatchNo)
            .eq(TicketRegistrationBatch::getPlatformId, platformId)
            .eq(TicketRegistrationBatch::getBatchStatus, "executing")
            .orderByDesc(TicketRegistrationBatch::getBatchId)
            .last("limit 1"));
        if (runningBatch != null) {
            throw new ServiceException("已有批量注册任务正在执行，请等待完成后再创建，批次号: " + runningBatch.getBatchNo());
        }
    }

    @Override
    public TableDataInfo<TicketRegistrationBatchVo> selectRegistrationBatchPage(TicketRegistrationBatchBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketRegistrationBatch> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketRegistrationBatch::getPlatformId, bo.getPlatformId())
            .like(StringUtils.isNotBlank(bo.getBatchNo()), TicketRegistrationBatch::getBatchNo, bo.getBatchNo())
            .eq(StringUtils.isNotBlank(bo.getBatchStatus()), TicketRegistrationBatch::getBatchStatus, bo.getBatchStatus())
            .orderByDesc(TicketRegistrationBatch::getBatchId);
        Page<TicketRegistrationBatchVo> page = registrationBatchMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichRegistrationBatches(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public TicketRegistrationBatchVo selectRegistrationBatchById(Long batchId) {
        TicketRegistrationBatchVo vo = registrationBatchMapper.selectVoById(batchId);
        if (vo != null) {
            enrichRegistrationBatches(List.of(vo));
        }
        return vo;
    }

    @Override
    public List<TicketRegistrationBatchDetailVo> selectRegistrationBatchDetails(Long batchId) {
        List<TicketRegistrationBatchDetailVo> rows = registrationBatchDetailMapper.selectVoList(new LambdaQueryWrapper<TicketRegistrationBatchDetail>()
            .eq(TicketRegistrationBatchDetail::getBatchId, batchId)
            .orderByAsc(TicketRegistrationBatchDetail::getDetailId));
        enrichRegistrationBatchDetails(rows);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateRegistrationBatchStatus(Long batchId, TicketBatchStatusUpdateBo bo) {
        TicketRegistrationBatch batch = registrationBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new ServiceException("注册批次不存在");
        }
        String status = normalizeBatchStatus(bo.getBatchStatus());
        List<TicketRegistrationBatchDetail> details = registrationBatchDetailMapper.selectList(new LambdaQueryWrapper<TicketRegistrationBatchDetail>()
            .eq(TicketRegistrationBatchDetail::getBatchId, batchId));
        long successCount = countDetailsByStatus(details, "success");
        long skippedCount = countDetailsByStatus(details, "skipped");
        long failedCount = details.stream()
            .filter(item -> StringUtils.isNotBlank(item.getExecuteStatus()))
            .filter(item -> !"success".equals(item.getExecuteStatus()) && !"skipped".equals(item.getExecuteStatus()))
            .count();
        int totalCount = ObjectUtil.defaultIfNull(batch.getTotalCount(), details.size());
        Map<String, Object> summary = buildManualBatchSummary(batch.getBatchStatus(), status, bo.getRemark(), totalCount, successCount, failedCount, skippedCount);
        LambdaUpdateWrapper<TicketRegistrationBatch> wrapper = Wrappers.lambdaUpdate(TicketRegistrationBatch.class)
            .eq(TicketRegistrationBatch::getBatchId, batchId)
            .set(TicketRegistrationBatch::getBatchStatus, status)
            .set(TicketRegistrationBatch::getTotalCount, totalCount)
            .set(TicketRegistrationBatch::getSuccessCount, (int) successCount)
            .set(TicketRegistrationBatch::getFailedCount, (int) failedCount)
            .set(TicketRegistrationBatch::getSkippedCount, (int) skippedCount)
            .set(TicketRegistrationBatch::getResultSummary, JSONUtil.toJsonStr(List.of(summary)))
            .set(TicketRegistrationBatch::getExecutedAt, BATCH_TERMINAL_STATUSES.contains(status) ? new Date() : null);
        int rows = registrationBatchMapper.update(null, wrapper);
        recordAudit("registration", "manualUpdateStatus", "registrationBatch", String.valueOf(batchId), "success", "手动修改注册批次状态为 " + status, summary);
        return rows;
    }

    @Override
    public TableDataInfo<TicketLoginBatchVo> selectLoginBatchPage(TicketLoginBatchBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketLoginBatch> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketLoginBatch::getPlatformId, bo.getPlatformId())
            .like(StringUtils.isNotBlank(bo.getBatchNo()), TicketLoginBatch::getBatchNo, bo.getBatchNo())
            .eq(StringUtils.isNotBlank(bo.getBatchStatus()), TicketLoginBatch::getBatchStatus, bo.getBatchStatus())
            .orderByDesc(TicketLoginBatch::getBatchId);
        Page<TicketLoginBatchVo> page = loginBatchMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichLoginBatches(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public TicketLoginBatchVo selectLoginBatchById(Long batchId) {
        TicketLoginBatchVo vo = loginBatchMapper.selectVoById(batchId);
        if (vo != null) {
            enrichLoginBatches(List.of(vo));
        }
        return vo;
    }

    @Override
    public List<TicketLoginBatchDetailVo> selectLoginBatchDetails(Long batchId) {
        List<TicketLoginBatchDetailVo> rows = loginBatchDetailMapper.selectVoList(new LambdaQueryWrapper<TicketLoginBatchDetail>()
            .eq(TicketLoginBatchDetail::getBatchId, batchId)
            .orderByAsc(TicketLoginBatchDetail::getDetailId));
        enrichLoginBatchDetails(rows);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Void> submitLoginEmailCode(Long batchId, Long detailId, TicketLoginEmailCodeBo bo) {
        TicketLoginBatchDetail detail = requireLoginDetail(batchId, detailId);
        JSONObject reqData = parseLoginDetailReqData(detail.getReqData());
        String requestId = reqData.getStr("verifyCodeRequestId");
        if (StringUtils.isBlank(requestId)) {
            throw new ServiceException("当前登录明细没有等待中的验证码请求");
        }
        Long expiresAtMillis = reqData.getLong("verifyCodeExpiresAt");
        if (expiresAtMillis != null && expiresAtMillis < System.currentTimeMillis()) {
            throw new ServiceException("验证码请求已超时，请重新发起登录");
        }
        int attemptCount = ObjectUtil.defaultIfNull(reqData.getInt("verifyCodeAttemptCount"), 0);
        if (attemptCount >= MANUAL_EMAIL_CODE_MAX_ATTEMPTS) {
            throw new ServiceException("验证码提交次数已达上限，请重新发起登录");
        }

        String verifyCode = StringUtils.trim(bo.getVerifyCode());
        if (StringUtils.isBlank(verifyCode)) {
            throw new ServiceException("验证码不能为空");
        }
        long ttlSeconds = computeManualCodeTtlSeconds(expiresAtMillis);
        ticketPythonStringRedisTemplate.opsForValue().set(manualLoginEmailCodeKey(requestId), verifyCode, Duration.ofSeconds(ttlSeconds));

        reqData.set("verifyCodeAttemptCount", attemptCount + 1);
        reqData.set("verifyCodeStatus", "submitted");
        reqData.set("verifyCodeMessage", "验证码已提交，等待校验");
        detail.setExecuteStatus("processing");
        detail.setResultMessage(fitResultMessage("验证码已提交，等待 Python 校验"));
        detail.setReqData(reqData.toString());
        detail.setExecutedAt(new Date());
        loginBatchDetailMapper.updateById(detail);
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Void> requestExternalLoginEmailCode(TicketExternalLoginCodeRequestBo bo) {
        TicketPlatformConfig platform = findPlatformByCode(bo.getPlatformCode());
        if (platform == null) {
            return R.fail("平台不存在: " + bo.getPlatformCode());
        }
        TicketLoginBatch batch = loginBatchMapper.selectById(bo.getBatchId());
        if (batch == null || !Objects.equals(batch.getPlatformId(), platform.getPlatformId())) {
            return R.fail("登录批次不存在");
        }
        TicketLoginBatchDetail detail = findLoginDetail(bo.getBatchId(), bo.getAccountId());
        if (detail == null) {
            return R.fail("登录明细不存在");
        }
        TicketManagedAccount account = accountMapper.selectById(bo.getAccountId());
        if (account == null || !Objects.equals(account.getPlatformId(), platform.getPlatformId())) {
            return R.fail("账号不存在: " + bo.getEmail());
        }
        if (!Objects.equals(normalizeEmailKey(account.getEmail()), normalizeEmailKey(bo.getEmail()))) {
            return R.fail("验证码请求邮箱与账号不一致");
        }

        int timeoutSeconds = Math.max(ObjectUtil.defaultIfNull(bo.getTimeoutSeconds(), MANUAL_EMAIL_CODE_TIMEOUT_SECONDS), 30);
        long expiresAtMillis = System.currentTimeMillis() + timeoutSeconds * 1000L;
        JSONObject reqData = parseLoginDetailReqData(detail.getReqData());
        reqData.set("loginMode", LOGIN_MODE_MANUAL_EMAIL_CODE);
        reqData.set("verifyCodeRequestId", bo.getRequestId());
        reqData.set("verifyCodeExpiresAt", expiresAtMillis);
        reqData.set("verifyCodeStatus", "waiting");
        reqData.set("verifyCodeMessage", "等待输入邮箱验证码");
        reqData.set("verifyCodeAttemptCount", ObjectUtil.defaultIfNull(reqData.getInt("verifyCodeAttemptCount"), 0));

        detail.setExecuteStatus(LOGIN_DETAIL_STATUS_WAITING_CODE);
        detail.setResultMessage(fitResultMessage("等待输入邮箱验证码"));
        detail.setReqData(reqData.toString());
        detail.setExecutedAt(new Date());
        loginBatchDetailMapper.updateById(detail);
        return R.ok();
    }

    @Override
    public R<TicketExternalManualLoginCodeVo> fetchExternalLoginEmailCode(String requestId) {
        String normalizedRequestId = StringUtils.trim(requestId);
        if (StringUtils.isBlank(normalizedRequestId)) {
            return R.fail("requestId不能为空");
        }
        String verifyCode = ticketPythonStringRedisTemplate.opsForValue().get(manualLoginEmailCodeKey(normalizedRequestId));
        TicketExternalManualLoginCodeVo vo = new TicketExternalManualLoginCodeVo();
        vo.setRequestId(normalizedRequestId);
        if (StringUtils.isBlank(verifyCode)) {
            vo.setStatus("waiting");
            vo.setMessage("等待用户输入验证码");
        } else {
            vo.setStatus("submitted");
            vo.setVerifyCode(verifyCode);
            vo.setMessage("验证码已提交");
        }
        return R.ok(vo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Void> markExternalLoginEmailCodeInvalid(TicketExternalLoginCodeInvalidBo bo) {
        TicketLoginBatchDetail detail = findLoginDetail(bo.getBatchId(), bo.getAccountId());
        if (detail == null) {
            return R.fail("登录明细不存在");
        }
        JSONObject reqData = parseLoginDetailReqData(detail.getReqData());
        String requestId = reqData.getStr("verifyCodeRequestId");
        if (!Objects.equals(requestId, bo.getRequestId())) {
            return R.fail("验证码请求不匹配");
        }
        ticketPythonStringRedisTemplate.delete(manualLoginEmailCodeKey(bo.getRequestId()));

        int attemptCount = ObjectUtil.defaultIfNull(reqData.getInt("verifyCodeAttemptCount"), 0);
        boolean exhausted = attemptCount >= MANUAL_EMAIL_CODE_MAX_ATTEMPTS;
        String message = StringUtils.defaultIfBlank(bo.getMessage(), "验证码错误，请重新输入");
        reqData.set("verifyCodeStatus", exhausted ? "failed" : "waiting");
        reqData.set("verifyCodeMessage", message);
        detail.setExecuteStatus(exhausted ? "failed" : LOGIN_DETAIL_STATUS_WAITING_CODE);
        detail.setResultMessage(fitResultMessage(exhausted ? "验证码错误次数已达上限" : message));
        detail.setReqData(reqData.toString());
        detail.setExecutedAt(new Date());
        loginBatchDetailMapper.updateById(detail);
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateLoginBatchStatus(Long batchId, TicketBatchStatusUpdateBo bo) {
        TicketLoginBatch batch = loginBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new ServiceException("登录批次不存在");
        }
        String status = normalizeBatchStatus(bo.getBatchStatus());
        List<TicketLoginBatchDetail> details = loginBatchDetailMapper.selectList(new LambdaQueryWrapper<TicketLoginBatchDetail>()
            .eq(TicketLoginBatchDetail::getBatchId, batchId));
        long successCount = countDetailsByStatus(details, "success");
        long failedCount = details.stream()
            .filter(item -> StringUtils.isNotBlank(item.getExecuteStatus()))
            .filter(item -> !"success".equals(item.getExecuteStatus()))
            .count();
        int totalCount = ObjectUtil.defaultIfNull(batch.getTotalCount(), details.size());
        Map<String, Object> summary = buildManualBatchSummary(batch.getBatchStatus(), status, bo.getRemark(), totalCount, successCount, failedCount, 0);
        LambdaUpdateWrapper<TicketLoginBatch> wrapper = Wrappers.lambdaUpdate(TicketLoginBatch.class)
            .eq(TicketLoginBatch::getBatchId, batchId)
            .set(TicketLoginBatch::getBatchStatus, status)
            .set(TicketLoginBatch::getTotalCount, totalCount)
            .set(TicketLoginBatch::getSuccessCount, (int) successCount)
            .set(TicketLoginBatch::getFailedCount, (int) failedCount)
            .set(TicketLoginBatch::getResultSummary, JSONUtil.toJsonStr(List.of(summary)))
            .set(TicketLoginBatch::getExecutedAt, BATCH_TERMINAL_STATUSES.contains(status) ? new Date() : null);
        int rows = loginBatchMapper.update(null, wrapper);
        recordAudit("login", "manualUpdateStatus", "loginBatch", String.valueOf(batchId), "success", "手动修改登录批次状态为 " + status, summary);
        return rows;
    }

    private String normalizeBatchStatus(String status) {
        String normalized = StringUtils.trim(status);
        if (!BATCH_STATUSES.contains(normalized)) {
            throw new ServiceException("不支持的批次状态: " + StringUtils.defaultString(status, "-"));
        }
        return normalized;
    }

    private long countDetailsByStatus(Collection<?> details, String status) {
        if (CollUtil.isEmpty(details)) {
            return 0L;
        }
        return details.stream()
            .map(item -> {
                if (item instanceof TicketRegistrationBatchDetail detail) {
                    return detail.getExecuteStatus();
                }
                if (item instanceof TicketLoginBatchDetail detail) {
                    return detail.getExecuteStatus();
                }
                return null;
            })
            .filter(status::equals)
            .count();
    }

    private Map<String, Object> buildManualBatchSummary(
        String oldStatus,
        String newStatus,
        String remark,
        int totalCount,
        long successCount,
        long failedCount,
        long skippedCount
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("eventType", "manual_status_update");
        summary.put("oldStatus", oldStatus);
        summary.put("status", newStatus);
        summary.put("message", StringUtils.defaultIfBlank(StringUtils.trim(remark), "人工修改批次状态"));
        summary.put("totalCount", totalCount);
        summary.put("successCount", successCount);
        summary.put("failedCount", failedCount);
        summary.put("skippedCount", skippedCount);
        summary.put("operator", LoginHelper.getUsername());
        summary.put("updatedAt", DateUtil.now());
        return summary;
    }

    @Override
    public TableDataInfo<TicketEventConfigVo> selectEventPage(TicketEventConfigBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketEventConfig> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketEventConfig::getPlatformId, bo.getPlatformId())
            .like(StringUtils.isNotBlank(bo.getEventCode()), TicketEventConfig::getEventCode, bo.getEventCode())
            .like(StringUtils.isNotBlank(bo.getEventName()), TicketEventConfig::getEventName, bo.getEventName())
            .eq(StringUtils.isNotBlank(bo.getEventStatus()), TicketEventConfig::getEventStatus, bo.getEventStatus())
            .orderByDesc(TicketEventConfig::getEventId);
        Page<TicketEventConfigVo> page = eventMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichEventPage(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public TicketEventConfigVo selectEventById(Long eventId) {
        TicketEventConfigVo vo = eventMapper.selectVoById(eventId);
        if (vo != null) {
            enrichEventPage(List.of(vo));
        }
        return vo;
    }


    @Override
    public int saveEvent(TicketEventConfigBo bo) {
        TicketEventConfig entity = MapstructUtils.convert(bo, TicketEventConfig.class);
        int rows = eventMapper.insert(entity);
        recordAudit("event", "create", "event", String.valueOf(entity.getEventId()), "success", "Event created", bo);
        return rows;
    }

    @Override
    public int updateEvent(TicketEventConfigBo bo) {
        TicketEventConfig entity = MapstructUtils.convert(bo, TicketEventConfig.class);
        int rows = eventMapper.updateById(entity);
        recordAudit("event", "update", "event", String.valueOf(entity.getEventId()), "success", "Event updated", bo);
        return rows;
    }

    @Override
    public int removeEvents(Long[] eventIds) {
        int rows = eventMapper.deleteByIds(Arrays.asList(eventIds));
        recordAudit("event", "remove", "event", Arrays.toString(eventIds), "success", "Events removed", eventIds);
        return rows;
    }

    @Override
    public TableDataInfo<TicketSaleTaskVo> selectSaleTaskPage(TicketSaleTaskBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketSaleTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ObjectUtil.isNotNull(bo.getTaskId()), TicketSaleTask::getTaskId, bo.getTaskId())
            .eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketSaleTask::getPlatformId, bo.getPlatformId())
            .eq(StringUtils.isNotBlank(bo.getPurchaseType()), TicketSaleTask::getPurchaseType, bo.getPurchaseType())
            .like(StringUtils.isNotBlank(bo.getTaskName()), TicketSaleTask::getTaskName, bo.getTaskName())
            .eq(StringUtils.isNotBlank(bo.getTaskStatus()), TicketSaleTask::getTaskStatus, bo.getTaskStatus())
            .orderByDesc(TicketSaleTask::getTaskId);
        Page<TicketSaleTaskVo> page = saleTaskMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichSaleTasks(page.getRecords(), false);
        return TableDataInfo.build(page);
    }

    @Override
    public TicketSaleTaskVo selectSaleTaskById(Long taskId) {
        refreshSaleTaskStatus(taskId);
        TicketSaleTaskVo vo = saleTaskMapper.selectVoById(taskId);
        if (vo != null) {
            enrichSaleTasks(List.of(vo), true);
        }
        return vo;
    }

    @Override
    public TicketSaleTaskProcessVo selectSaleTaskProcess(Long taskId) {
        TicketSaleTaskVo task = selectSaleTaskById(taskId);
        if (task == null) {
            throw new ServiceException("任务不存在");
        }
        if (TicketOrderFlowSupport.isLottery(task.getPurchaseType())) {
            List<TicketSaleTaskSchedule> rawSchedules = saleTaskScheduleMapper.selectList(new LambdaQueryWrapper<TicketSaleTaskSchedule>()
                .select(TicketSaleTaskSchedule::getScheduleId)
                .eq(TicketSaleTaskSchedule::getTaskId, taskId));
            for (TicketSaleTaskSchedule schedule : rawSchedules) {
                if (schedule.getScheduleId() != null) {
                    refreshLotteryScheduleStatus(schedule.getScheduleId());
                }
            }
            refreshSaleTaskStatus(taskId);
            task = selectSaleTaskById(taskId);
        }

        List<TicketSaleTaskScheduleVo> schedules = saleTaskScheduleMapper.selectVoList(new LambdaQueryWrapper<TicketSaleTaskSchedule>()
            .eq(TicketSaleTaskSchedule::getTaskId, taskId)
            .orderByAsc(TicketSaleTaskSchedule::getScheduledTime)
            .orderByAsc(TicketSaleTaskSchedule::getScheduleId));
        TicketSaleTaskProcessVo vo = new TicketSaleTaskProcessVo();
        vo.setTask(task);
        vo.setSchedules(schedules);
        vo.setExecutions(List.of());
        vo.setExecutionSummary(buildSaleTaskExecutionSummary(taskId, task.getPurchaseType(), task.getScheduleVersion()));
        vo.setCreateSteps(buildSaleTaskProcessSteps(task, schedules, vo.getExecutionSummary()));
        return vo;
    }

    @Override
    public TableDataInfo<TicketOrderExecutionVo> selectSaleTaskProcessExecutions(Long taskId, String status, Long scheduleId, PageQuery pageQuery) {
        TicketSaleTaskVo task = selectSaleTaskById(taskId);
        if (task == null) {
            throw new ServiceException("任务不存在");
        }
        int pageNum = pageQuery == null ? PageQuery.DEFAULT_PAGE_NUM : ObjectUtil.defaultIfNull(pageQuery.getPageNum(), PageQuery.DEFAULT_PAGE_NUM);
        int pageSize = pageQuery == null ? 30 : ObjectUtil.defaultIfNull(pageQuery.getPageSize(), 30);
        if (pageNum <= 0) {
            pageNum = PageQuery.DEFAULT_PAGE_NUM;
        }
        if (pageSize <= 0) {
            pageSize = 30;
        }
        pageSize = Math.min(pageSize, 100);

        List<TicketOrderExecution> allExecutions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .select(TicketOrderExecution::getExecutionId, TicketOrderExecution::getTaskId, TicketOrderExecution::getAccountId,
                TicketOrderExecution::getLotteryScheduleId, TicketOrderExecution::getScheduleVersion, TicketOrderExecution::getExecutionStatus)
            .eq(TicketOrderExecution::getTaskId, taskId)
            .eq(TicketOrderExecution::getScheduleVersion, defaultScheduleVersion(task.getScheduleVersion()))
            .eq(scheduleId != null, TicketOrderExecution::getLotteryScheduleId, scheduleId)
            .orderByAsc(TicketOrderExecution::getExecutionId));
        List<TicketOrderExecution> effectiveExecutions = effectiveSaleTaskExecutions(task.getPurchaseType(), allExecutions).stream()
            .filter(execution -> matchProcessExecutionStatus(status, execution.getExecutionStatus()))
            .sorted(Comparator.comparing(TicketOrderExecution::getExecutionId, Comparator.nullsLast(Long::compareTo)).reversed())
            .toList();
        int total = effectiveExecutions.size();
        int fromIndex = Math.min((pageNum - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        if (fromIndex >= toIndex) {
            return new TableDataInfo<>(List.of(), total);
        }

        List<Long> pageExecutionIds = effectiveExecutions.subList(fromIndex, toIndex).stream()
            .map(TicketOrderExecution::getExecutionId)
            .filter(Objects::nonNull)
            .toList();
        if (CollUtil.isEmpty(pageExecutionIds)) {
            return new TableDataInfo<>(List.of(), total);
        }

        List<TicketOrderExecutionVo> rows = orderExecutionMapper.selectVoList(new LambdaQueryWrapper<TicketOrderExecution>()
            .select(TicketOrderExecution::getExecutionId, TicketOrderExecution::getTaskId, TicketOrderExecution::getPlatformId,
                TicketOrderExecution::getAccountId, TicketOrderExecution::getLotteryScheduleId, TicketOrderExecution::getPurchaseType,
                TicketOrderExecution::getPurchaseQuantity, TicketOrderExecution::getScheduleVersion, TicketOrderExecution::getCurrentStep,
                TicketOrderExecution::getStepStatus, TicketOrderExecution::getPaymentStatus, TicketOrderExecution::getOrderNo,
                TicketOrderExecution::getExecutionStatus, TicketOrderExecution::getResultMessage, TicketOrderExecution::getLotteryResultStatus,
                TicketOrderExecution::getLotteryResultMailRecordId, TicketOrderExecution::getLotteryResultAt, TicketOrderExecution::getWorkerId,
                TicketOrderExecution::getAttemptCount, TicketOrderExecution::getHeartbeatAt, TicketOrderExecution::getStartedAt,
                TicketOrderExecution::getExecutedAt)
            .in(TicketOrderExecution::getExecutionId, pageExecutionIds));
        Map<Long, Integer> sortIndex = new HashMap<>();
        for (int i = 0; i < pageExecutionIds.size(); i++) {
            sortIndex.put(pageExecutionIds.get(i), i);
        }
        rows.sort(Comparator.comparing(row -> sortIndex.getOrDefault(row.getExecutionId(), Integer.MAX_VALUE)));
        enrichProcessOrderExecutions(rows, task);
        return new TableDataInfo<>(rows, total);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveSaleTask(TicketSaleTaskBo bo) {
        TicketPlatformConfig platform = requirePlatform(bo.getPlatformId());
        TicketSaleTask entity = MapstructUtils.convert(bo, TicketSaleTask.class);
        normalizeSaleTask(entity);
        applyLotteryEventSnapshot(platform, bo, entity);
        applyLivePocketFlashSaleSnapshot(platform, bo, entity);
        applyJumpShopFlashSaleSnapshot(platform, bo, entity);
        normalizePlatformTaskOptions(platform, entity);
        validateSaleTaskAccounts(entity, bo.getAccountIds(), null);
        entity.setScheduleVersion(1L);
        entity.setTaskStatus("draft");
        entity.setLastExecutedTime(null);
        int rows = saleTaskMapper.insert(entity);
        saveSaleTaskAccounts(entity.getTaskId(), bo.getAccountIds());
        saveSaleTaskSchedules(entity, bo.getLotterySchedules());
        planSaleTaskSchedule(entity.getTaskId(), LoginHelper.getUserId(), "save", false);
        recordAudit("saleTask", "create", "saleTask", String.valueOf(entity.getTaskId()), "success", "任务已创建", bo);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateSaleTask(TicketSaleTaskBo bo) {
        TicketPlatformConfig platform = requirePlatform(bo.getPlatformId());
        TicketSaleTask existing = saleTaskMapper.selectById(bo.getTaskId());
        if (existing == null) {
            throw new ServiceException("任务不存在");
        }
        assertSaleTaskEditable(existing);
        TicketSaleTask entity = MapstructUtils.convert(bo, TicketSaleTask.class);
        normalizeSaleTask(entity);
        applyLotteryEventSnapshot(platform, bo, entity);
        applyLivePocketFlashSaleSnapshot(platform, bo, entity);
        applyJumpShopFlashSaleSnapshot(platform, bo, entity);
        normalizePlatformTaskOptions(platform, entity);
        validateSaleTaskAccounts(entity, bo.getAccountIds(), bo.getTaskId());
        entity.setScheduleVersion(nextScheduleVersion(existing.getScheduleVersion()));
        entity.setTaskStatus("draft");
        entity.setLastExecutedTime(null);
        int rows = saleTaskMapper.updateById(entity);
        saveSaleTaskAccounts(entity.getTaskId(), bo.getAccountIds());
        saveSaleTaskSchedules(entity, bo.getLotterySchedules());
        planSaleTaskSchedule(entity.getTaskId(), LoginHelper.getUserId(), "update", false);
        recordAudit("saleTask", "update", "saleTask", String.valueOf(entity.getTaskId()), "success", "任务已更新", bo);
        return rows;
    }

    private void assertSaleTaskEditable(TicketSaleTask task) {
        if (!SALE_TASK_EDITABLE_STATUSES.contains(StringUtils.defaultString(task.getTaskStatus(), "draft"))) {
            throw new ServiceException("任务已开始，不能再编辑，请取消后重新创建任务");
        }
        Date nextExecutionTime = resolveSaleTaskNextExecutionTime(task);
        if (nextExecutionTime != null && nextExecutionTime.getTime() - System.currentTimeMillis() <= SALE_TASK_EDIT_LOCK_LEAD_MILLIS) {
            throw new ServiceException("距离执行时间不足5分钟，不能再编辑，请取消后重新创建任务");
        }
    }

    private Date resolveSaleTaskNextExecutionTime(TicketSaleTask task) {
        if (task == null) {
            return null;
        }
        if (!TicketOrderFlowSupport.isLottery(task.getPurchaseType())) {
            return task.getScheduledTime() == null ? new Date() : task.getScheduledTime();
        }
        return saleTaskScheduleMapper.selectList(new LambdaQueryWrapper<TicketSaleTaskSchedule>()
                .select(TicketSaleTaskSchedule::getScheduledTime)
                .eq(TicketSaleTaskSchedule::getTaskId, task.getTaskId())
                .isNotNull(TicketSaleTaskSchedule::getScheduledTime)
                .orderByAsc(TicketSaleTaskSchedule::getScheduledTime)
                .orderByAsc(TicketSaleTaskSchedule::getScheduleId))
            .stream()
            .map(TicketSaleTaskSchedule::getScheduledTime)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancelSaleTask(Long taskId) {
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("任务不存在");
        }
        String status = StringUtils.defaultString(task.getTaskStatus(), "draft");
        if (!SALE_TASK_CANCELABLE_STATUSES.contains(status)) {
            throw new ServiceException("当前任务状态不允许取消");
        }

        Date now = new Date();
        String message = "任务已取消，未开始的执行计划已停止";
        cleanupLotteryScheduleQueue(taskId);
        cancelPendingSaleTaskSchedules(taskId, message, now);
        cancelQueuedExecutions(taskId, message, now);

        task.setTaskStatus("cancelled");
        if (task.getLastExecutedTime() == null) {
            task.setLastExecutedTime(now);
        }
        int rows = saleTaskMapper.updateById(task);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("taskId", taskId);
        auditPayload.put("previousStatus", status);
        recordAudit("saleTask", "cancel", "saleTask", String.valueOf(taskId), "success", message, auditPayload);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeSaleTasks(Long[] taskIds) {
        List<TicketSaleTask> tasks = saleTaskMapper.selectBatchIds(Arrays.asList(taskIds));
        boolean hasRunningTask = tasks.stream()
            .map(TicketSaleTask::getTaskStatus)
            .map(status -> StringUtils.defaultString(status, "draft"))
            .anyMatch(status -> !SALE_TASK_DELETABLE_STATUSES.contains(status));
        if (hasRunningTask) {
            throw new ServiceException("任务已开始，不能直接删除，请先取消任务");
        }
        cleanupPendingSaleTaskSchedules(Arrays.asList(taskIds), "任务已删除，执行计划已取消");
        saleTaskScheduleMapper.deleteByTaskIdsPhysical(Arrays.asList(taskIds));
        saleTaskAccountMapper.deleteByTaskIdsPhysical(Arrays.asList(taskIds));
        int rows = saleTaskMapper.deleteByIds(Arrays.asList(taskIds));
        recordAudit("saleTask", "remove", "saleTask", Arrays.toString(taskIds), "success", "任务已删除", taskIds);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Long> executeSaleTask(Long taskId) {
        Long executionId = executeSaleTaskInternal(taskId, LoginHelper.getUserId(), "manual");
        return R.ok("任务已重新排队，系统将立即执行", executionId);
    }

    @Override
    public R<Long> executeSaleTaskNow(Long taskId) {
        refreshSaleTaskStatus(taskId);
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("任务不存在");
        }
        String status = StringUtils.defaultString(task.getTaskStatus(), "draft");
        if (!SALE_TASK_EXECUTE_NOW_STATUSES.contains(status)) {
            throw new ServiceException("只有待执行任务可以立即执行");
        }
        Long operatorUserId = LoginHelper.getUserId();
        String tenantId = task.getTenantId();
        scheduledExecutorService.execute(() -> runExecuteSaleTaskNowAsync(taskId, operatorUserId, tenantId));
        return R.ok("立即执行已受理，后台正在入队", taskId);
    }

    private void runExecuteSaleTaskNowAsync(Long taskId, Long operatorUserId, String tenantId) {
        try {
            TenantHelper.dynamic(tenantId, () -> transactionTemplate.executeWithoutResult(
                status -> executeSaleTaskInternal(taskId, operatorUserId, "manual-now", true)
            ));
        } catch (Exception ex) {
            log.error("execute sale task now async failed, taskId={}", taskId, ex);
            TenantHelper.dynamic(tenantId, () -> {
                String message = "立即执行入队异常: " + StringUtils.defaultString(ex.getMessage(), "unknown");
                saleTaskMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTask.class)
                    .eq(TicketSaleTask::getTaskId, taskId)
                    .set(TicketSaleTask::getTaskStatus, "blocked")
                    .set(TicketSaleTask::getRemark, message));
                recordAudit("saleTask", "executeNow", "saleTask", String.valueOf(taskId), "failed", message, Map.of("taskId", taskId));
            });
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Long> retryFailedLotteryExecutions(Long taskId) {
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("任务不存在");
        }
        if (!TicketOrderFlowSupport.isLottery(task.getPurchaseType())) {
            throw new ServiceException("只有抽票任务支持重新提交未成功账号");
        }
        if ("cancelled".equals(StringUtils.defaultString(task.getTaskStatus()))) {
            throw new ServiceException("任务已取消，不能重新提交");
        }
        Long scheduleVersion = defaultScheduleVersion(task.getScheduleVersion());
        List<TicketOrderExecution> retryableExecutions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getTaskId, taskId)
            .eq(TicketOrderExecution::getPurchaseType, "lottery")
            .eq(TicketOrderExecution::getScheduleVersion, scheduleVersion)
            .in(TicketOrderExecution::getExecutionStatus, LOTTERY_RETRYABLE_STATUSES)
            .orderByDesc(TicketOrderExecution::getExecutionId));
        if (CollUtil.isEmpty(retryableExecutions)) {
            recordAudit("saleTask", "retryFailedLottery", "saleTask", String.valueOf(taskId), "warn", "没有可重新提交的未成功账号", Map.of(
                "taskId", taskId,
                "scheduleVersion", scheduleVersion,
                "retryableStatuses", LOTTERY_RETRYABLE_STATUSES
            ));
            return R.ok("没有可重新提交的未成功账号", 0L);
        }

        List<TicketOrderExecution> currentExecutions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getTaskId, taskId)
            .eq(TicketOrderExecution::getScheduleVersion, scheduleVersion));
        Set<String> pendingKeys = currentExecutions.stream()
            .filter(item -> LOTTERY_RETRY_PENDING_STATUSES.contains(item.getExecutionStatus()))
            .map(this::lotteryExecutionRetryKey)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        Set<String> successKeys = currentExecutions.stream()
            .filter(item -> LOTTERY_SUCCESS_STATUSES.contains(item.getExecutionStatus()))
            .map(this::lotteryExecutionRetryKey)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        Map<String, Integer> maxAttempts = currentExecutions.stream()
            .map(item -> Map.entry(lotteryExecutionRetryKey(item), ObjectUtil.defaultIfNull(item.getAttemptCount(), 0)))
            .filter(entry -> StringUtils.isNotBlank(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Math::max));

        Map<String, TicketOrderExecution> targetExecutions = new LinkedHashMap<>();
        for (TicketOrderExecution execution : retryableExecutions) {
            String key = lotteryExecutionRetryKey(execution);
            if (StringUtils.isBlank(key) || pendingKeys.contains(key) || successKeys.contains(key)) {
                continue;
            }
            targetExecutions.putIfAbsent(key, execution);
        }

        List<Long> newExecutionIds = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        Date now = new Date();
        for (Map.Entry<String, TicketOrderExecution> entry : targetExecutions.entrySet()) {
            TicketOrderExecution source = entry.getValue();
            TicketManagedAccount account = accountMapper.selectById(source.getAccountId());
            if (account == null) {
                skipped.add(lotteryRetrySkip(source, "账号不存在"));
                continue;
            }
            if (!Objects.equals(account.getPlatformId(), task.getPlatformId())) {
                skipped.add(lotteryRetrySkip(source, "账号不属于当前平台"));
                continue;
            }
            if (!"activated".equals(account.getAccountStatus())) {
                skipped.add(lotteryRetrySkip(source, "账号不是已激活状态"));
                continue;
            }
            TicketSaleTaskSchedule schedule = saleTaskScheduleMapper.selectById(source.getLotteryScheduleId());
            if (schedule == null || !Objects.equals(schedule.getTaskId(), taskId)) {
                skipped.add(lotteryRetrySkip(source, "抽票时段不存在"));
                continue;
            }

            TicketOrderExecution retryExecution = new TicketOrderExecution();
            retryExecution.setTaskId(taskId);
            retryExecution.setPlatformId(task.getPlatformId());
            retryExecution.setAccountId(account.getAccountId());
            retryExecution.setLotteryScheduleId(schedule.getScheduleId());
            retryExecution.setPurchaseType(task.getPurchaseType());
            retryExecution.setPurchaseQuantity(task.getPurchaseQuantity());
            String retryConfigSnapshot = StringUtils.defaultIfBlank(source.getConfigSnapshot(), task.getTaskOptions());
            retryExecution.setConfigSnapshot(retryConfigSnapshot);
            applyLotteryEventUrl(retryExecution, task.getPurchaseType(), retryConfigSnapshot);
            retryExecution.setScheduleVersion(scheduleVersion);
            retryExecution.setCurrentStep("queued");
            retryExecution.setStepStatus("queued");
            retryExecution.setStepTrace("[]");
            retryExecution.setPaymentStatus(TicketOrderFlowSupport.queuedPaymentStatus(task.getPurchaseType()));
            retryExecution.setExecutionStatus("queued");
            retryExecution.setResultMessage("重新提交等待调度");
            retryExecution.setRawResult(null);
            retryExecution.setWorkerId(null);
            retryExecution.setAttemptCount(maxAttempts.getOrDefault(entry.getKey(), ObjectUtil.defaultIfNull(source.getAttemptCount(), 0)) + 1);
            retryExecution.setHeartbeatAt(null);
            retryExecution.setStartedAt(null);
            retryExecution.setExecutedAt(null);
            orderExecutionMapper.insert(retryExecution);
            newExecutionIds.add(retryExecution.getExecutionId());
        }

        if (CollUtil.isNotEmpty(newExecutionIds)) {
            saleTaskMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTask.class)
                .eq(TicketSaleTask::getTaskId, taskId)
                .set(TicketSaleTask::getTaskStatus, "executing")
                .set(TicketSaleTask::getLastExecutedTime, now));
            registerRetryLotteryDispatchAfterCommit(taskId, newExecutionIds);
        }

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("taskId", taskId);
        auditPayload.put("scheduleVersion", scheduleVersion);
        auditPayload.put("retryCount", newExecutionIds.size());
        auditPayload.put("skippedCount", skipped.size());
        auditPayload.put("skipped", skipped);
        recordAudit("saleTask", "retryFailedLottery", "saleTask", String.valueOf(taskId), newExecutionIds.isEmpty() ? "warn" : "success",
            newExecutionIds.isEmpty() ? "没有可重新提交的未成功账号" : "抽票未成功账号已重新提交", auditPayload);

        if (newExecutionIds.isEmpty()) {
            return R.ok("没有可重新提交的未成功账号", 0L);
        }
        return R.ok("已重新提交 " + newExecutionIds.size() + " 个未成功账号", (long) newExecutionIds.size());
    }

    @Override
    public TableDataInfo<TicketLotteryBatchTaskVo> selectLotteryBatchTaskPage(TicketLotteryBatchTaskBo bo, PageQuery pageQuery) {
        return ticketLotteryBatchTaskService.selectPage(bo, pageQuery);
    }

    @Override
    public TicketLotteryBatchTaskVo selectLotteryBatchTaskById(Long batchTaskId) {
        return ticketLotteryBatchTaskService.selectById(batchTaskId);
    }

    @Override
    public TicketLotteryBatchTaskProcessVo selectLotteryBatchTaskProcess(Long batchTaskId) {
        return ticketLotteryBatchTaskService.selectProcess(batchTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveLotteryBatchTask(TicketLotteryBatchTaskBo bo) {
        return ticketLotteryBatchTaskService.save(bo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateLotteryBatchTask(TicketLotteryBatchTaskBo bo) {
        return ticketLotteryBatchTaskService.update(bo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateLotteryBatchTaskStatus(Long batchTaskId, TicketBatchStatusUpdateBo bo) {
        return ticketLotteryBatchTaskService.updateStatus(batchTaskId, bo);
    }

    @Override
    public R<Long> executeLotteryBatchTaskNow(Long batchTaskId) {
        return ticketLotteryBatchTaskService.executeNow(batchTaskId);
    }

    private String lotteryExecutionRetryKey(TicketOrderExecution execution) {
        if (execution == null || execution.getLotteryScheduleId() == null || execution.getAccountId() == null) {
            return "";
        }
        return execution.getLotteryScheduleId() + ":" + execution.getAccountId();
    }

    private Map<String, Object> lotteryRetrySkip(TicketOrderExecution execution, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("executionId", execution == null ? null : execution.getExecutionId());
        item.put("accountId", execution == null ? null : execution.getAccountId());
        item.put("scheduleId", execution == null ? null : execution.getLotteryScheduleId());
        item.put("reason", reason);
        return item;
    }

    private Long executeSaleTaskInternal(Long taskId, Long operatorUserId, String triggerSource) {
        return executeSaleTaskInternal(taskId, operatorUserId, triggerSource, false);
    }

    private Long executeSaleTaskInternal(Long taskId, Long operatorUserId, String triggerSource, boolean executeNow) {
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("任务不存在");
        }
        task.setScheduleVersion(nextScheduleVersion(task.getScheduleVersion()));
        task.setTaskStatus("draft");
        task.setLastExecutedTime(null);
        saleTaskMapper.updateById(task);
        return planSaleTaskSchedule(taskId, operatorUserId, triggerSource, true, executeNow);
    }

    private Long planSaleTaskSchedule(Long taskId, Long operatorUserId, String triggerSource, boolean forceImmediate) {
        return planSaleTaskSchedule(taskId, operatorUserId, triggerSource, forceImmediate, false);
    }

    private Long planSaleTaskSchedule(Long taskId, Long operatorUserId, String triggerSource, boolean forceImmediate, boolean executeNow) {
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("任务不存在");
        }
        TicketPlatformConfig platform = requirePlatform(task.getPlatformId());
        List<TicketManagedAccount> accounts = loadSaleTaskAccounts(task);
        invalidateQueuedExecutions(taskId, "任务计划已重排，请以最新调度为准");
        if (TicketOrderFlowSupport.isLottery(task.getPurchaseType())) {
            return planLotteryTaskSchedule(task, accounts, triggerSource, executeNow);
        }
        boolean usePythonFlashSaleQueue = isPythonFlashSaleTask(platform, task.getPurchaseType());
        if (CollUtil.isEmpty(accounts)) {
            TicketOrderExecution execution = new TicketOrderExecution();
            execution.setTaskId(taskId);
            execution.setPlatformId(task.getPlatformId());
            execution.setPurchaseType(task.getPurchaseType());
            execution.setPurchaseQuantity(task.getPurchaseQuantity());
            execution.setConfigSnapshot(task.getTaskOptions());
            applyLotteryEventUrl(execution, task.getPurchaseType(), task.getTaskOptions());
            execution.setScheduleVersion(defaultScheduleVersion(task.getScheduleVersion()));
            execution.setCurrentStep("completed");
            execution.setStepStatus("failed");
            execution.setPaymentStatus(TicketOrderFlowSupport.queuedPaymentStatus(task.getPurchaseType()));
            execution.setExecutionStatus("blocked");
            execution.setStepTrace("[]");
            execution.setResultMessage(usePythonFlashSaleQueue ? "没有可执行的可用账号" : "没有可执行的已登录账号");
            execution.setExecutedAt(new Date());
            orderExecutionMapper.insert(execution);
            task.setTaskStatus("blocked");
            task.setLastExecutedTime(new Date());
            saleTaskMapper.updateById(task);
            Map<String, Object> auditPayload = new LinkedHashMap<>();
            auditPayload.put("taskId", taskId);
            auditPayload.put("platformId", task.getPlatformId());
            auditPayload.put("purchaseType", task.getPurchaseType());
            auditPayload.put("triggerSource", triggerSource);
            recordAudit("saleTask", "schedule", "saleTask", String.valueOf(taskId), "warn", "任务无可用账号", auditPayload);
            return execution.getExecutionId();
        }

        Date now = new Date();
        Date dispatchTime = resolveTaskDispatchTime(task, forceImmediate);
        List<TicketOrderExecution> executions = new ArrayList<>();
        for (TicketManagedAccount account : accounts) {
            if (executeNow && hasPendingExecution(taskId, null, account.getAccountId())) {
                continue;
            }
            TicketOrderExecution execution = new TicketOrderExecution();
            execution.setTaskId(taskId);
            execution.setPlatformId(task.getPlatformId());
            execution.setAccountId(account.getAccountId());
            execution.setPurchaseType(task.getPurchaseType());
            execution.setPurchaseQuantity(task.getPurchaseQuantity());
            execution.setConfigSnapshot(task.getTaskOptions());
            execution.setScheduleVersion(defaultScheduleVersion(task.getScheduleVersion()));
            execution.setCurrentStep("queued");
            execution.setStepStatus("queued");
            execution.setStepTrace("[]");
            execution.setPaymentStatus(TicketOrderFlowSupport.queuedPaymentStatus(task.getPurchaseType()));
            execution.setExecutionStatus("queued");
            execution.setResultMessage("等待调度");
            execution.setRawResult(null);
            execution.setWorkerId(null);
            execution.setAttemptCount(0);
            execution.setHeartbeatAt(null);
            execution.setStartedAt(null);
            execution.setExecutedAt(null);
            orderExecutionMapper.insert(execution);
            executions.add(execution);
        }
        if (CollUtil.isEmpty(executions)) {
            task.setTaskStatus("executing");
            task.setLastExecutedTime(now);
            saleTaskMapper.updateById(task);
            recordAudit("saleTask", "schedule", "saleTask", String.valueOf(taskId), "warn", "立即执行跳过，账号已有排队或运行记录", Map.of(
                "taskId", taskId,
                "triggerSource", triggerSource,
                "executeMode", executeNow ? "manual-now" : triggerSource
            ));
            return 0L;
        }
        task.setTaskStatus(dispatchTime.after(now) ? "draft" : "executing");
        task.setLastExecutedTime(dispatchTime.after(now) ? null : now);
        saleTaskMapper.updateById(task);

        if (usePythonFlashSaleQueue) {
            registerFlashSaleDispatchAfterCommit(task, platform, executions, forceImmediate);
        } else {
            registerDispatchAfterCommit(task, platform, accounts, executions, operatorUserId, triggerSource, forceImmediate);
        }

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("taskId", taskId);
        auditPayload.put("platformId", task.getPlatformId());
        auditPayload.put("purchaseType", task.getPurchaseType());
        auditPayload.put("accountCount", accounts.size());
        auditPayload.put("triggerSource", triggerSource);
        auditPayload.put("dispatchAt", dispatchTime);
        auditPayload.put("executeMode", executeNow ? "manual-now" : triggerSource);
        recordAudit("saleTask", "schedule", "saleTask", String.valueOf(taskId), "success", "任务已排队", auditPayload);
        return executions.get(0).getExecutionId();
    }

    private Long planLotteryTaskSchedule(TicketSaleTask task, List<TicketManagedAccount> accounts, String triggerSource) {
        return planLotteryTaskSchedule(task, accounts, triggerSource, false);
    }

    private Long planLotteryTaskSchedule(TicketSaleTask task, List<TicketManagedAccount> accounts, String triggerSource, boolean executeNow) {
        List<TicketSaleTaskSchedule> schedules = saleTaskScheduleMapper.selectList(new LambdaQueryWrapper<TicketSaleTaskSchedule>()
            .eq(TicketSaleTaskSchedule::getTaskId, task.getTaskId())
            .orderByAsc(TicketSaleTaskSchedule::getScheduledTime)
            .orderByAsc(TicketSaleTaskSchedule::getScheduleId));
        if (CollUtil.isEmpty(schedules)) {
            throw new ServiceException("抽票任务请至少配置一个执行时段");
        }
        int requiredAccounts = schedules.stream()
            .map(TicketSaleTaskSchedule::getAccountCount)
            .filter(Objects::nonNull)
            .mapToInt(Integer::intValue)
            .sum();
        if (CollUtil.isEmpty(accounts) || accounts.size() < requiredAccounts) {
            throw new ServiceException("抽票时段需要 " + requiredAccounts + " 个已激活账号，当前可用 " + accounts.size() + " 个");
        }

        Date now = new Date();
        List<TicketOrderExecution> executions = new ArrayList<>();
        int accountIndex = 0;
        for (TicketSaleTaskSchedule schedule : schedules) {
            schedule.setScheduleStatus("pending");
            schedule.setDispatchedTime(null);
            schedule.setFinishedTime(null);
            schedule.setResultMessage("等待执行");
            saleTaskScheduleMapper.updateById(schedule);

            for (int i = 0; i < schedule.getAccountCount(); i++) {
                TicketManagedAccount account = accounts.get(accountIndex++);
                if (executeNow && hasPendingExecution(task.getTaskId(), schedule.getScheduleId(), account.getAccountId())) {
                    continue;
                }
                TicketOrderExecution execution = new TicketOrderExecution();
                execution.setTaskId(task.getTaskId());
                execution.setPlatformId(task.getPlatformId());
                execution.setAccountId(account.getAccountId());
                execution.setLotteryScheduleId(schedule.getScheduleId());
                execution.setPurchaseType(task.getPurchaseType());
                execution.setPurchaseQuantity(task.getPurchaseQuantity());
                execution.setConfigSnapshot(task.getTaskOptions());
                applyLotteryEventUrl(execution, task.getPurchaseType(), task.getTaskOptions());
                execution.setScheduleVersion(defaultScheduleVersion(task.getScheduleVersion()));
                execution.setCurrentStep("queued");
                execution.setStepStatus("queued");
                execution.setStepTrace("[]");
                execution.setPaymentStatus(TicketOrderFlowSupport.queuedPaymentStatus(task.getPurchaseType()));
                execution.setExecutionStatus("queued");
                execution.setResultMessage("等待 Redis 抽票队列调度");
                execution.setAttemptCount(0);
                orderExecutionMapper.insert(execution);
                executions.add(execution);
            }
        }
        if (CollUtil.isEmpty(executions)) {
            task.setTaskStatus("executing");
            task.setLastExecutedTime(now);
            saleTaskMapper.updateById(task);
            recordAudit("saleTask", "schedule", "saleTask", String.valueOf(task.getTaskId()), "warn", "立即执行跳过，账号已有排队或运行记录", Map.of(
                "taskId", task.getTaskId(),
                "triggerSource", triggerSource,
                "executeMode", executeNow ? "manual-now" : triggerSource
            ));
            return 0L;
        }
        task.setTaskStatus(executeNow ? "executing" : "draft");
        task.setLastExecutedTime(executeNow ? now : null);
        saleTaskMapper.updateById(task);

        registerLotteryScheduleDispatchAfterCommit(task, schedules, executeNow);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("taskId", task.getTaskId());
        auditPayload.put("purchaseType", task.getPurchaseType());
        auditPayload.put("scheduleCount", schedules.size());
        auditPayload.put("accountCount", requiredAccounts);
        auditPayload.put("triggerSource", triggerSource);
        auditPayload.put("executeMode", executeNow ? "manual-now" : triggerSource);
        auditPayload.put("plannedAt", now);
        recordAudit("saleTask", "schedule", "saleTask", String.valueOf(task.getTaskId()), "success", "抽票任务已按时段排队", auditPayload);
        return CollUtil.isEmpty(executions) ? 0L : executions.get(0).getExecutionId();
    }

    private boolean hasPendingExecution(Long taskId, Long lotteryScheduleId, Long accountId) {
        if (taskId == null || accountId == null) {
            return false;
        }
        LambdaQueryWrapper<TicketOrderExecution> wrapper = new LambdaQueryWrapper<TicketOrderExecution>()
            .select(TicketOrderExecution::getExecutionId)
            .eq(TicketOrderExecution::getTaskId, taskId)
            .eq(TicketOrderExecution::getAccountId, accountId)
            .in(TicketOrderExecution::getExecutionStatus, LOTTERY_RETRY_PENDING_STATUSES)
            .last("limit 1");
        if (lotteryScheduleId != null) {
            wrapper.eq(TicketOrderExecution::getLotteryScheduleId, lotteryScheduleId);
        }
        return orderExecutionMapper.selectOne(wrapper, false) != null;
    }

    @Override
    public TableDataInfo<TicketOrderExecutionVo> selectOrderExecutionPage(TicketOrderExecutionBo bo, PageQuery pageQuery) {
        List<Long> emailAccountIds = List.of();
        if (StringUtils.isNotBlank(bo.getEmail())) {
            emailAccountIds = accountMapper.selectList(new LambdaQueryWrapper<TicketManagedAccount>()
                    .select(TicketManagedAccount::getAccountId)
                    .like(TicketManagedAccount::getEmail, bo.getEmail().trim()))
                .stream()
                .map(TicketManagedAccount::getAccountId)
                .filter(Objects::nonNull)
                .toList();
            if (CollUtil.isEmpty(emailAccountIds)) {
                return TableDataInfo.build(pageQuery.build());
            }
        }
        LambdaQueryWrapper<TicketOrderExecution> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ObjectUtil.isNotNull(bo.getTaskId()), TicketOrderExecution::getTaskId, bo.getTaskId())
            .eq(ObjectUtil.isNotNull(bo.getBatchTaskId()), TicketOrderExecution::getBatchTaskId, bo.getBatchTaskId())
            .eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketOrderExecution::getPlatformId, bo.getPlatformId())
            .eq(ObjectUtil.isNotNull(bo.getAccountId()), TicketOrderExecution::getAccountId, bo.getAccountId())
            .in(CollUtil.isNotEmpty(emailAccountIds), TicketOrderExecution::getAccountId, emailAccountIds)
            .eq(StringUtils.isNotBlank(bo.getPurchaseType()), TicketOrderExecution::getPurchaseType, bo.getPurchaseType())
            .like(StringUtils.isNotBlank(bo.getOrderNo()), TicketOrderExecution::getOrderNo, bo.getOrderNo())
            .eq(StringUtils.isNotBlank(bo.getExecutionStatus()), TicketOrderExecution::getExecutionStatus, bo.getExecutionStatus())
            .eq(StringUtils.isNotBlank(bo.getPaymentStatus()), TicketOrderExecution::getPaymentStatus, bo.getPaymentStatus())
            .eq(StringUtils.isNotBlank(bo.getLotteryResultStatus()), TicketOrderExecution::getLotteryResultStatus, bo.getLotteryResultStatus())
            .ne(Boolean.TRUE.equals(bo.getExcludeCancelled()), TicketOrderExecution::getExecutionStatus, "cancelled")
            .orderByDesc(TicketOrderExecution::getExecutionId);
        Page<TicketOrderExecutionVo> page = orderExecutionMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichOrderExecutions(page.getRecords());
        trimOrderExecutionListPayload(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public TicketOrderExecutionVo selectOrderExecutionDetail(Long executionId) {
        TicketOrderExecutionVo row = orderExecutionMapper.selectVoById(executionId);
        if (row == null) {
            throw new ServiceException("订单记录不存在");
        }
        enrichOrderExecutions(List.of(row));
        populateOrderExecutionPayloadFlags(row);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markOrderExecutionPaid(Long executionId, TicketOrderExecutionPaymentBo bo) {
        TicketOrderExecution execution = orderExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new ServiceException("订单记录不存在");
        }
        if (!EXECUTION_PAYMENT_PENDING_STATUSES.contains(execution.getExecutionStatus())) {
            throw new ServiceException("当前执行状态不允许标记已支付");
        }
        if (TicketOrderFlowSupport.isLottery(execution.getPurchaseType())) {
            if (!"selected".equals(execution.getLotteryResultStatus())) {
                throw new ServiceException("抽票订单未当选，不能标记已支付");
            }
            if (!LOTTERY_PAYABLE_PAYMENT_STATUSES.contains(execution.getPaymentStatus())) {
                throw new ServiceException("当前抽票支付状态不允许标记已支付");
            }
        } else if ("not_required".equals(execution.getPaymentStatus())) {
            throw new ServiceException("当前订单不需要支付");
        }
        execution.setExecutionStatus("paid");
        execution.setPaymentStatus("paid");
        execution.setCurrentStep("completed");
        execution.setStepStatus("success");
        execution.setResultMessage(bo.getResultMessage());
        execution.setExecutedAt(new Date());
        int rows = orderExecutionMapper.updateById(execution);
        refreshSaleTaskStatus(execution.getTaskId());
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("executionId", executionId);
        auditPayload.put("taskId", execution.getTaskId());
        auditPayload.put("orderNo", execution.getOrderNo());
        recordAudit("orderExecution", "markPaid", "orderExecution", String.valueOf(executionId), "success", "订单已标记为已支付", auditPayload);
        return rows;
    }

    @Override
    public TableDataInfo<TicketAuditEventVo> selectAuditPage(TicketAuditEventBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketAuditEvent> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StringUtils.isNotBlank(bo.getModuleName()), TicketAuditEvent::getModuleName, bo.getModuleName())
            .like(StringUtils.isNotBlank(bo.getActionType()), TicketAuditEvent::getActionType, bo.getActionType())
            .eq(StringUtils.isNotBlank(bo.getBusinessType()), TicketAuditEvent::getBusinessType, bo.getBusinessType())
            .eq(StringUtils.isNotBlank(bo.getAuditStatus()), TicketAuditEvent::getAuditStatus, bo.getAuditStatus())
            .orderByDesc(TicketAuditEvent::getAuditId);
        Page<TicketAuditEventVo> page = auditEventMapper.selectVoPage(pageQuery.build(), wrapper);
        return TableDataInfo.build(page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<String> handleCallback(String platformCode, Map<String, Object> payload) {
        TicketPlatformConfig platform = findPlatformByCode(platformCode);
        if (platform == null) {
            return R.fail("平台不存在: " + platformCode);
        }
        TicketPlatformAdapter adapter = adapterRegistry.getAdapter(platform.getAdapterType());
        String message = adapter.handleCallback(platform, payload);
        recordAudit("callback", "receive", "platform", platformCode, "success", message, payload);
        return R.ok(message);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Void> reportExternalLoginSuccess(TicketExternalLoginReportBo bo) {
        TicketPlatformConfig platform = findPlatformByCode(bo.getPlatformCode());
        if (platform == null) {
            return R.fail("平台不存在: " + bo.getPlatformCode());
        }

        List<TicketManagedAccount> accounts = accountMapper.selectList(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platform.getPlatformId())
            .eq(TicketManagedAccount::getEmail, bo.getEmail())
            .orderByAsc(TicketManagedAccount::getAccountId)
            .last("limit 2"));
        if (CollUtil.isEmpty(accounts)) {
            return R.fail("账号不存在: " + bo.getEmail());
        }
        if (accounts.size() > 1) {
            return R.fail("账号数据异常，存在重复邮箱: " + bo.getEmail());
        }

        Date now = new Date();
        TicketManagedAccount account = accounts.get(0);
        String loginReqData = normalizeJsonText(StringUtils.defaultIfBlank(bo.getLoginReqData(), bo.getReqData()));
        if (StringUtils.isBlank(loginReqData)) {
            return R.fail("loginReqData不能为空");
        }
        account.setLoginReqData(loginReqData);
        account.setLoginStatus("logged_in");
        account.setLastLoginTime(now);
        account.setLastError(null);
        accountMapper.updateById(account);

        relationMapper.update(null, new LambdaUpdateWrapper<TicketPhonePlatformRelation>()
            .set(TicketPhonePlatformRelation::getStatus, "logged_in")
            .set(TicketPhonePlatformRelation::getLastError, null)
            .set(TicketPhonePlatformRelation::getLastOperateTime, now)
            .eq(TicketPhonePlatformRelation::getPlatformId, platform.getPlatformId())
            .eq(TicketPhonePlatformRelation::getAccountId, account.getAccountId()));

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("platformCode", bo.getPlatformCode());
        auditPayload.put("email", bo.getEmail());
        auditPayload.put("accountId", account.getAccountId());
        auditPayload.put("loginReqData", loginReqData);
        recordAudit("external_account", "loginSuccess", "account", String.valueOf(account.getAccountId()), "success", "external login reported", auditPayload);
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Void> submitExternalLoginReqData(TicketExternalLoginReqDataBo bo) {
        TicketPlatformConfig platform = findPlatformByCode(bo.getPlatformCode());
        if (platform == null) {
            return R.fail("平台不存在: " + bo.getPlatformCode());
        }

        List<TicketManagedAccount> accounts = accountMapper.selectList(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platform.getPlatformId())
            .eq(TicketManagedAccount::getEmail, bo.getEmail())
            .orderByAsc(TicketManagedAccount::getAccountId)
            .last("limit 2"));
        if (CollUtil.isEmpty(accounts)) {
            return R.fail("账号不存在: " + bo.getEmail());
        }
        if (accounts.size() > 1) {
            return R.fail("账号数据异常，存在重复邮箱: " + bo.getEmail());
        }

        String loginReqData = normalizeJsonText(bo.getLoginReqData());
        if (StringUtils.isBlank(loginReqData)) {
            return R.fail("loginReqData不能为空");
        }
        TicketManagedAccount account = accounts.get(0);
        accountMapper.update(null, Wrappers.lambdaUpdate(TicketManagedAccount.class)
            .eq(TicketManagedAccount::getAccountId, account.getAccountId())
            .set(TicketManagedAccount::getLoginReqData, loginReqData));

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("platformCode", bo.getPlatformCode());
        auditPayload.put("email", bo.getEmail());
        auditPayload.put("accountId", account.getAccountId());
        auditPayload.put("loginReqData", loginReqData);
        recordAudit("external_account", "loginReqData", "account", String.valueOf(account.getAccountId()), "success", "external login req data submitted", auditPayload);
        return R.ok();
    }

    @Override
    public R<TicketExternalOfflineAccountVo> fetchNextOfflineAccount(String platformCode) {
        TicketPlatformConfig platform = findPlatformByCode(platformCode);
        if (platform == null) {
            return R.fail("平台不存在: " + platformCode);
        }

        List<TicketManagedAccount> accounts = accountMapper.selectList(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platform.getPlatformId())
            .in(TicketManagedAccount::getAccountStatus, List.of("registered", "activated"))
            .eq(TicketManagedAccount::getLoginStatus, "offline")
            .orderByAsc(TicketManagedAccount::getAccountId)
            .last("limit 50"));
        TicketManagedAccount account = null;
        String password = null;
        for (TicketManagedAccount candidate : accounts) {
            password = resolveAccountLoginPassword(candidate);
            if (StringUtils.isNotBlank(password)) {
                account = candidate;
                break;
            }
        }
        if (account == null) {
            return R.fail("没有需要登录的账号");
        }

        accountMapper.update(null, Wrappers.lambdaUpdate(TicketManagedAccount.class)
            .eq(TicketManagedAccount::getAccountId, account.getAccountId())
            .set(TicketManagedAccount::getUpdateTime, new Date()));

        TicketExternalOfflineAccountVo vo = new TicketExternalOfflineAccountVo();
        vo.setEmail(account.getEmail());
        vo.setPassword(password);
        return R.ok(vo);
    }

    private String resolveAccountLoginPassword(TicketManagedAccount account) {
        if (StringUtils.isBlank(account.getAccountInfo())) {
            return resolveMailboxPlatformPassword(account.getEmail());
        }
        try {
            String password = JSONUtil.parseObj(account.getAccountInfo()).getStr("platformPassword");
            if (StringUtils.isNotBlank(password)) {
                return password;
            }
        } catch (Exception ex) {
            log.warn("parse account platform password failed, accountId={}", account.getAccountId(), ex);
        }
        return resolveMailboxPlatformPassword(account.getEmail());
    }

    private boolean hasAccountPlatformPassword(String accountInfo) {
        if (StringUtils.isBlank(accountInfo)) {
            return false;
        }
        try {
            return StringUtils.isNotBlank(JSONUtil.parseObj(accountInfo).getStr("platformPassword"));
        } catch (Exception ex) {
            return false;
        }
    }

    private Map<String, Object> buildPythonLoginAccountPayload(TicketManagedAccount account, String loginMode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        boolean manualEmailCode = LOGIN_MODE_MANUAL_EMAIL_CODE.equals(loginMode);
        payload.put("accountId", account.getAccountId());
        payload.put("email", account.getEmail());
        payload.put("password", resolveAccountLoginPassword(account));
        payload.put("platformPassword", resolveAccountLoginPassword(account));
        payload.put("accountInfo", account.getAccountInfo());
        payload.put("loginReqData", account.getLoginReqData());
        payload.put("loginMode", loginMode);
        payload.put("emailCodeMode", manualEmailCode ? EMAIL_CODE_MODE_MANUAL : EMAIL_CODE_MODE_AUTO);
        payload.put("manualEmailCodeTimeoutSeconds", MANUAL_EMAIL_CODE_TIMEOUT_SECONDS);
        payload.put("manualEmailCodePollIntervalSeconds", MANUAL_EMAIL_CODE_POLL_INTERVAL_SECONDS);
        payload.put("manualEmailCodeMaxAttempts", MANUAL_EMAIL_CODE_MAX_ATTEMPTS);
        return payload;
    }

    private String resolveMailboxPlatformPassword(String email) {
        if (StringUtils.isBlank(email)) {
            return null;
        }
        TicketMailboxAccount mailbox = mailboxAccountMapper.selectOne(new LambdaQueryWrapper<TicketMailboxAccount>()
            .eq(TicketMailboxAccount::getEmail, email)
            .last("limit 1"), false);
        if (mailbox == null || StringUtils.isBlank(mailbox.getUsername())) {
            return null;
        }
        return mailbox.getUsername() + "@ABC";
    }

    private Map<String, TicketMailboxAccount> loadMailboxByEmail(Collection<String> emails) {
        List<String> queryEmails = Optional.ofNullable(emails).orElse(List.of()).stream()
            .map(StringUtils::trim)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
        if (CollUtil.isEmpty(queryEmails)) {
            return Map.of();
        }
        return mailboxAccountMapper.selectList(new LambdaQueryWrapper<TicketMailboxAccount>()
                .in(TicketMailboxAccount::getEmail, queryEmails))
            .stream()
            .collect(Collectors.toMap(
                item -> normalizeEmailKey(item.getEmail()),
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private String normalizeEmailKey(String email) {
        return StringUtils.defaultIfBlank(StringUtils.trim(email), "").toLowerCase(Locale.ROOT);
    }

    private boolean isInternalMailbox(TicketManagedAccount account, Map<String, TicketMailboxAccount> mailboxMap) {
        if (account == null) {
            return false;
        }
        return CollUtil.isNotEmpty(mailboxMap) && mailboxMap.containsKey(normalizeEmailKey(account.getEmail()))
            || isInternalMailboxDomain(account.getEmail());
    }

    private boolean isInternalMailboxDomain(String email) {
        String normalizedEmail = normalizeEmailKey(email);
        if (StringUtils.isBlank(normalizedEmail) || !normalizedEmail.contains("@")) {
            return false;
        }
        String domain = StringUtils.defaultIfBlank(StringUtils.trim(ticketStalwartProperties.getDomain()), "gjcytech.com").toLowerCase(Locale.ROOT);
        return normalizedEmail.endsWith("@" + domain);
    }

    private TicketMailboxAccount buildInternalDomainMailbox(String email) {
        String normalizedEmail = normalizeEmailKey(email);
        if (!isInternalMailboxDomain(normalizedEmail)) {
            return null;
        }
        TicketMailboxAccount mailbox = new TicketMailboxAccount();
        mailbox.setEmail(normalizedEmail);
        mailbox.setUsername(StrUtil.subBefore(normalizedEmail, "@", false));
        mailbox.setPassword(normalizedEmail);
        mailbox.setDomain(StringUtils.defaultIfBlank(StringUtils.trim(ticketStalwartProperties.getDomain()), "gjcytech.com"));
        mailbox.setProvider("stalwart");
        mailbox.setStatus("internal_domain");
        return mailbox;
    }

    private boolean isPersistedMailbox(TicketMailboxAccount mailbox) {
        return mailbox != null && mailbox.getMailboxId() != null;
    }

    private String buildLoginDetailReqData(String loginReqData, String loginMode) {
        JSONObject reqData = JSONUtil.createObj();
        reqData.set("loginMode", normalizeLoginMode(loginMode));
        if (StringUtils.isNotBlank(loginReqData)) {
            reqData.set("loginReqData", tryParseJson(loginReqData));
        }
        return reqData.toString();
    }

    private Object tryParseJson(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return JSONUtil.parse(text);
        } catch (Exception ex) {
            return text;
        }
    }

    @Override
    public R<TicketExternalVerifyCodeVo> verifyCode(String platformCode, String email) {
        return readEmailResult(platformCode, email, null);
    }

    @Override
    public R<TicketExternalVerifyCodeVo> emailVerifyCode(String platformCode, String email) {
        return readEmailResult(platformCode, email, "verify_code");
    }

    @Override
    public R<TicketExternalVerifyCodeVo> emailActivationLink(String platformCode, String email) {
        return readEmailResult(platformCode, email, "activation_url");
    }

    private R<TicketExternalVerifyCodeVo> readEmailResult(String platformCode, String email, String expectedParseType) {
        TicketPlatformConfig platform = findPlatformByCode(platformCode);
        if (platform == null) {
            return R.fail("平台不存在: " + platformCode);
        }

        List<TicketManagedAccount> accounts = accountMapper.selectList(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platform.getPlatformId())
            .eq(TicketManagedAccount::getEmail, email)
            .orderByAsc(TicketManagedAccount::getAccountId)
            .last("limit 2"));
        if (CollUtil.isEmpty(accounts)) {
            return R.fail("账号不存在: " + email);
        }
        if (accounts.size() > 1) {
            return R.fail("账号数据异常，存在重复邮箱: " + email);
        }

        TicketManagedAccount account = accounts.get(0);
        TicketMailboxAccount mailbox = findMailboxAccount(account.getEmail());
        if (mailbox == null) {
            return R.fail("邮箱账号池不存在该邮箱: " + account.getEmail());
        }
        boolean persistedMailbox = isPersistedMailbox(mailbox);
        Date minReceivedAt = "verify_code".equals(expectedParseType) ? resolveEmailVerifyMinReceivedAt(account) : null;

        TicketMailReaderService.MailReadResult mail;
        if (mailbox != null) {
            try {
                mail = switch (StringUtils.blankToDefault(expectedParseType, "")) {
                    case "verify_code" -> ticketMailReaderService.readLatestVerifyCodeForMailbox(mailbox.getUsername(), mailbox.getPassword(), minReceivedAt);
                    case "activation_url" -> ticketMailReaderService.readLatestActivationUrlForMailbox(mailbox.getUsername(), mailbox.getPassword());
                    default -> ticketMailReaderService.readLatestForMailbox(mailbox.getUsername(), mailbox.getPassword());
                };
            } catch (ServiceException e) {
                mail = persistedMailbox ? readCachedMailboxEmailResult(mailbox, expectedParseType, minReceivedAt) : null;
                if (mail == null) {
                    return R.fail(e.getMessage());
                }
            }
        } else {
            return R.fail("邮箱账号池不存在该邮箱: " + account.getEmail());
        }

        if (!mail.isParsed() && persistedMailbox) {
            TicketMailReaderService.MailReadResult cachedMail = readCachedMailboxEmailResult(mailbox, expectedParseType, minReceivedAt);
            if (cachedMail != null) {
                mail = cachedMail;
            }
        }

        updateAccountLatestMail(account.getAccountId(), mail);
        if (persistedMailbox) {
            saveMailRecord(mailbox, account.getAccountId(), mail, StringUtils.defaultIfBlank(expectedParseType, "latest"));
        }

        if (!mail.isParsed()) {
            return R.fail(mail.getMessage());
        }
        if ("verify_code".equals(expectedParseType) && StringUtils.isBlank(mail.getVerifyCode())) {
            return R.fail("未解析到邮箱验证码");
        }
        if ("activation_url".equals(expectedParseType) && StringUtils.isBlank(mail.getActivationUrl())) {
            return R.fail("未解析到邮箱激活链接");
        }

        TicketExternalVerifyCodeVo vo = new TicketExternalVerifyCodeVo();
        vo.setVerifyCode(mail.getVerifyCode());
        vo.setActivationUrl(mail.getActivationUrl());
        vo.setSubject(mail.getSubject());
        vo.setReceivedAt(mail.getReceivedAt());
        return R.ok(vo);
    }

    private TicketMailboxAccount findMailboxAccount(String email) {
        TicketMailboxAccount mailbox = mailboxAccountMapper.selectOne(new LambdaQueryWrapper<TicketMailboxAccount>()
            .eq(TicketMailboxAccount::getEmail, email)
            .orderByDesc(TicketMailboxAccount::getLastMailSyncTime)
            .orderByDesc(TicketMailboxAccount::getMailboxId)
            .last("limit 1"));
        return mailbox != null ? mailbox : buildInternalDomainMailbox(email);
    }

    private TicketMailReaderService.MailReadResult readCachedMailboxEmailResult(TicketMailboxAccount mailbox, String expectedParseType, Date minReceivedAt) {
        if (mailbox == null) {
            return null;
        }

        String activationUrl = mailbox.getLatestActivationUrl();
        String verifyCode = mailbox.getLatestVerifyCode();
        if ("verify_code".equals(expectedParseType) && !isPlausibleCachedVerifyCode(verifyCode)) {
            return null;
        }
        if ("activation_url".equals(expectedParseType) && StringUtils.isBlank(activationUrl)) {
            return null;
        }
        if ("verify_code".equals(expectedParseType) && StringUtils.isBlank(verifyCode)) {
            return null;
        }
        if (StringUtils.isBlank(expectedParseType) && StringUtils.isBlank(activationUrl) && StringUtils.isBlank(verifyCode)) {
            return null;
        }
        if (minReceivedAt != null && (mailbox.getLatestMailReceivedAt() == null || mailbox.getLatestMailReceivedAt().before(minReceivedAt))) {
            return null;
        }

        TicketMailReaderService.MailReadResult result = new TicketMailReaderService.MailReadResult();
        result.setParsed(true);
        result.setSubject(mailbox.getLatestMailSubject());
        result.setFromAddress(mailbox.getLatestMailFrom());
        result.setReceivedAt(mailbox.getLatestMailReceivedAt());
        result.setMessageId(mailbox.getLatestMailMessageId());
        result.setBodyExcerpt(mailbox.getLatestMailExcerpt());
        if (StringUtils.isNotBlank(activationUrl) && !"verify_code".equals(expectedParseType)) {
            result.setParseType("activation_url");
            result.setActivationUrl(activationUrl);
            result.setVerifyCode(null);
            result.setMessage("使用邮箱账号池最新激活链接");
        } else {
            result.setParseType("verify_code");
            result.setVerifyCode(verifyCode);
            result.setActivationUrl(null);
            result.setMessage("使用邮箱账号池最新验证码");
        }
        return result;
    }

    private boolean isPlausibleCachedVerifyCode(String verifyCode) {
        if (StringUtils.isBlank(verifyCode)) {
            return false;
        }
        String code = StringUtils.trim(verifyCode);
        return code.matches("\\d{4,8}") && !YEAR_VERIFY_CODE_PATTERN.matcher(code).matches();
    }

    private Date resolveEmailVerifyMinReceivedAt(TicketManagedAccount account) {
        Date baseTime = null;
        if (account != null) {
            baseTime = latestDate(account.getLastLoginTime(), account.getUpdateTime());
        }
        if (baseTime == null) {
            return null;
        }
        return new Date(Math.max(0L, baseTime.getTime() - EMAIL_VERIFY_TIME_DRIFT_MILLIS));
    }

    private Date latestDate(Date left, Date right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return right.after(left) ? right : left;
    }

    private void updateAccountLatestMail(Long accountId, TicketMailReaderService.MailReadResult mail) {
        accountMapper.update(null, new LambdaUpdateWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getAccountId, accountId)
            .set(TicketManagedAccount::getLatestVerifyCode, mail.getVerifyCode())
            .set(TicketManagedAccount::getLatestActivationUrl, mail.getActivationUrl())
            .set(TicketManagedAccount::getLatestMailSubject, mail.getSubject())
            .set(TicketManagedAccount::getLatestMailReceivedAt, mail.getReceivedAt())
            .set(TicketManagedAccount::getLatestMailMessageId, mail.getMessageId()));
    }

    private void saveMailRecord(TicketMailboxAccount mailbox, Long accountId, TicketMailReaderService.MailReadResult result, String readSource) {
        if (!isPersistedMailbox(mailbox) || result == null) {
            return;
        }
        TicketMailRecord record = findExistingMailRecord(mailbox, result);
        if (record == null) {
            record = new TicketMailRecord();
            record.setMailboxId(mailbox.getMailboxId());
            record.setEmail(mailbox.getEmail());
            record.setUsername(mailbox.getUsername());
        }
        record.setAccountId(accountId);
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
    }

    private TicketMailRecord findExistingMailRecord(TicketMailboxAccount mailbox, TicketMailReaderService.MailReadResult result) {
        LambdaQueryWrapper<TicketMailRecord> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TicketMailRecord::getMailboxId, mailbox.getMailboxId());
        if (StringUtils.isNotBlank(result.getMessageId())) {
            wrapper.eq(TicketMailRecord::getMessageId, result.getMessageId());
        } else {
            wrapper.eq(result.getReceivedAt() != null, TicketMailRecord::getReceivedAt, result.getReceivedAt())
                .eq(StringUtils.isNotBlank(result.getSubject()), TicketMailRecord::getSubject, StrUtil.maxLength(result.getSubject(), 1000));
        }
        return mailRecordMapper.selectOne(wrapper.last("limit 1"), false);
    }

    private void processRegistrationBatch(Long batchId, TicketPlatformConfig platform, List<Long> phoneIds, Long userId) {
        TicketPlatformAdapter adapter = adapterRegistry.getAdapter(platform.getAdapterType());
        List<Map<String, Object>> summary = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        int processedCount = 0;

        try {
            for (Long phoneId : phoneIds) {
                RegistrationProgress progress = prepareRegistration(batchId, platform, phoneId);
                if ("processing".equals(progress.getStepStatus())) {
                    publishRegisterProgress(progress, userId, successCount, failedCount, skippedCount, processedCount, phoneIds.size());
                    TicketRegisterResult result;
                    try {
                        result = adapter.batchRegister(platform, List.of(progress.getPhone())).stream().findFirst().orElse(null);
                    } catch (Exception ex) {
                        result = new TicketRegisterResult();
                        result.setPhoneId(phoneId);
                        result.setSuccess(false);
                        result.setMessage(adapter.normalizeError(ex.getMessage()));
                    }
                    progress = completeRegistration(batchId, platform, progress.getPhone(), result, adapter);
                }

                if ("success".equals(progress.getStepStatus())) {
                    successCount++;
                } else if ("failed".equals(progress.getStepStatus())) {
                    failedCount++;
                } else if ("skipped".equals(progress.getStepStatus())) {
                    skippedCount++;
                }

                processedCount++;
                summary.add(buildDetail(phoneId, progress.getStepStatus(), progress.getMessage()));
                updateRegistrationBatch(batchId, "executing", successCount, failedCount, skippedCount, summary, null);
                publishRegisterProgress(progress, userId, successCount, failedCount, skippedCount, processedCount, phoneIds.size());
            }

            String finalStatus = failedCount > 0 ? "partial" : "completed";
            updateRegistrationBatch(batchId, finalStatus, successCount, failedCount, skippedCount, summary, new Date());
            publishBatchCompleted(batchId, platform, userId, successCount, failedCount, skippedCount, phoneIds.size());
            recordAudit("registration", "finishBatch", "registrationBatch", String.valueOf(batchId), "success", "平台注册任务完成", Map.of(
                "batchId", batchId,
                "successCount", successCount,
                "failedCount", failedCount,
                "skippedCount", skippedCount
            ));
        } catch (Exception ex) {
            updateRegistrationBatch(batchId, "blocked", successCount, failedCount, skippedCount, summary, new Date());
            publishBatchFailed(batchId, platform, userId, ex.getMessage(), successCount, failedCount, skippedCount, processedCount, phoneIds.size());
            recordAudit("registration", "finishBatch", "registrationBatch", String.valueOf(batchId), "failed", "平台注册任务异常结束", Map.of(
                "batchId", batchId,
                "message", StringUtils.defaultString(ex.getMessage(), "unknown")
            ));
        }
    }

    private RegistrationProgress prepareRegistration(Long batchId, TicketPlatformConfig platform, Long phoneId) {
        return transactionTemplate.execute(status -> {
            TicketPhoneNumber phone = phoneMapper.selectById(phoneId);
            if (phone == null) {
                upsertRegistrationDetail(batchId, phoneId, platform.getPlatformId(), "failed", "Phone not found", null, null);
                return RegistrationProgress.failed(batchId, platform, null, "Phone not found", null, null);
            }

            if (!"available".equals(phone.getStatus())) {
                String note = "Phone is not available for registration";
                phone.setNote(note);
                phoneMapper.updateById(phone);
                upsertRegistrationDetail(batchId, phoneId, platform.getPlatformId(), "failed", note, null, null);
                return RegistrationProgress.failed(batchId, platform, phone, note, note, null);
            }

            TicketPhonePlatformRelation relation = getRelation(platform.getPlatformId(), phoneId);
            if (relation != null && ACTIVE_RELATION_STATUSES.contains(relation.getStatus())) {
                String note = "已跳过：该平台已存在有效注册关系";
                phone.setNote(note);
                phoneMapper.updateById(phone);
                TicketManagedAccount account = relation.getAccountId() == null ? null : accountMapper.selectById(relation.getAccountId());
                upsertRegistrationDetail(batchId, phoneId, platform.getPlatformId(), "skipped", note, relation.getAccountId(), account == null ? null : account.getEmail());
                return RegistrationProgress.skipped(batchId, platform, phone, note, note, account);
            }
            phone.setNote(String.format("正在注册 %s", platform.getPlatformName()));
            phoneMapper.updateById(phone);

            TicketPhonePlatformRelation pendingRelation = relation == null ? new TicketPhonePlatformRelation() : relation;
            pendingRelation.setPhoneId(phoneId);
            pendingRelation.setPlatformId(platform.getPlatformId());
            pendingRelation.setStatus("registering");
            pendingRelation.setLastError(null);
            pendingRelation.setLastOperateTime(new Date());
            saveRelation(pendingRelation);

            TicketManagedAccount account = pendingRelation.getAccountId() == null ? null : accountMapper.selectById(pendingRelation.getAccountId());
            upsertRegistrationDetail(batchId, phoneId, platform.getPlatformId(), "processing", "正在注册", pendingRelation.getAccountId(), account == null ? null : account.getEmail());
            return RegistrationProgress.processing(batchId, platform, phone, phone.getNote());
        });
    }

    private RegistrationProgress completeRegistration(Long batchId, TicketPlatformConfig platform, TicketPhoneNumber phone, TicketRegisterResult result, TicketPlatformAdapter adapter) {
        return transactionTemplate.execute(status -> {
            TicketPhoneNumber currentPhone = phoneMapper.selectById(phone.getPhoneId());
            TicketPhonePlatformRelation relation = getRelation(platform.getPlatformId(), phone.getPhoneId());
            if (relation == null) {
                relation = new TicketPhonePlatformRelation();
                relation.setPhoneId(phone.getPhoneId());
                relation.setPlatformId(platform.getPlatformId());
            }

            if (result != null && result.isSuccess()) {
                TicketManagedAccount account = getOrCreateAccount(platform.getPlatformId(), phone.getPhoneId());
                account.setPlatformId(platform.getPlatformId());
                account.setPhoneId(phone.getPhoneId());
                account.setEmail(result.getEmail());
                account.setAccountInfo(result.getAccountInfo());
                account.setReqData(result.getReqData());
                account.setAccountStatus("registered");
                account.setLoginStatus("offline");
                account.setLastError(null);
                saveAccount(account);

                relation.setAccountId(account.getAccountId());
                relation.setStatus("registered");
                relation.setLastError(null);
                relation.setLastOperateTime(new Date());
                saveRelation(relation);

                String note = String.format("已注册 %s%s", platform.getPlatformName(), StringUtils.isNotBlank(account.getEmail()) ? " / 邮箱: " + account.getEmail() : "");
                currentPhone.setNote(note);
                phoneMapper.updateById(currentPhone);

                upsertRegistrationDetail(batchId, phone.getPhoneId(), platform.getPlatformId(), "success", StringUtils.defaultIfBlank(result.getMessage(), "注册成功"), account.getAccountId(), account.getEmail());
                return RegistrationProgress.success(batchId, platform, currentPhone, StringUtils.defaultIfBlank(result.getMessage(), "注册成功"), note, account);
            }

            String error = adapter.normalizeError(result == null ? "register_result_missing" : result.getMessage());
            relation.setStatus("register_failed");
            relation.setLastError(error);
            relation.setLastOperateTime(new Date());
            saveRelation(relation);
            currentPhone.setNote("注册失败: " + error);
            phoneMapper.updateById(currentPhone);

            TicketManagedAccount account = relation.getAccountId() == null ? null : accountMapper.selectById(relation.getAccountId());
            upsertRegistrationDetail(batchId, phone.getPhoneId(), platform.getPlatformId(), "failed", error, relation.getAccountId(), account == null ? null : account.getEmail());
            return RegistrationProgress.failed(batchId, platform, currentPhone, error, currentPhone.getNote(), account);
        });
    }

    private void processLoginBatch(Long batchId, TicketPlatformConfig platform, List<Long> accountIds, Long userId) {
        TicketPlatformAdapter adapter = adapterRegistry.getAdapter(platform.getAdapterType());
        List<Map<String, Object>> summary = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;
        int processedCount = 0;

        try {
            for (Long accountId : accountIds) {
                LoginProgress progress = prepareLogin(batchId, platform, accountId);
                if ("processing".equals(progress.getStepStatus())) {
                    publishLoginProgress(progress, userId, successCount, failedCount, processedCount, accountIds.size());
                    TicketLoginResult result;
                    try {
                        result = adapter.batchLogin(platform, List.of(progress.getAccount())).stream().findFirst().orElse(null);
                    } catch (Exception ex) {
                        result = new TicketLoginResult();
                        result.setAccountId(progress.getAccountId());
                        result.setSuccess(false);
                        result.setMessage(StringUtils.defaultString(ex.getMessage(), "login_exception"));
                    }
                    progress = completeLogin(batchId, platform, progress.getAccount(), result, adapter);
                }

                processedCount++;
                if ("success".equals(progress.getStepStatus())) {
                    successCount++;
                } else {
                    failedCount++;
                }

                summary.add(buildDetail(progress.getAccountId(), progress.getStepStatus(), progress.getMessage()));
                boolean finished = processedCount >= accountIds.size();
                updateLoginBatch(batchId, finished ? (failedCount > 0 ? "partial" : "completed") : "executing", successCount, failedCount, summary, finished ? new Date() : null);
                publishLoginProgress(progress, userId, successCount, failedCount, processedCount, accountIds.size());
            }

            publishLoginBatchCompleted(batchId, platform, userId, successCount, failedCount, accountIds.size());
            Map<String, Object> auditPayload = new LinkedHashMap<>();
            auditPayload.put("batchId", batchId);
            auditPayload.put("successCount", successCount);
            auditPayload.put("failedCount", failedCount);
            auditPayload.put("totalCount", accountIds.size());
            recordAudit("login", "batchLogin", "loginBatch", String.valueOf(batchId), failedCount > 0 ? "warn" : "success", "平台登录任务完成", auditPayload);
        } catch (Exception ex) {
            updateLoginBatch(batchId, "blocked", successCount, failedCount, summary, new Date());
            publishLoginBatchFailed(batchId, platform, userId, ex.getMessage(), successCount, failedCount, processedCount, accountIds.size());
            Map<String, Object> auditPayload = new LinkedHashMap<>();
            auditPayload.put("batchId", batchId);
            auditPayload.put("message", StringUtils.defaultString(ex.getMessage(), "unknown"));
            recordAudit("login", "batchLogin", "loginBatch", String.valueOf(batchId), "failed", "平台登录任务异常结束", auditPayload);
        }
    }

    private LoginProgress prepareLogin(Long batchId, TicketPlatformConfig platform, Long accountId) {
        return transactionTemplate.execute(status -> {
            TicketManagedAccount account = accountMapper.selectById(accountId);
            if (account == null) {
                upsertLoginDetail(batchId, accountId, platform.getPlatformId(), "failed", "账号不存在", null);
                return LoginProgress.failed(batchId, platform, null, null, "账号不存在");
            }

            TicketPhoneNumber phone = account.getPhoneId() == null ? null : phoneMapper.selectById(account.getPhoneId());
            if (!List.of("registered", "activated").contains(account.getAccountStatus())) {
                String message = "账号未注册，不允许登录";
                upsertLoginDetail(batchId, accountId, platform.getPlatformId(), "failed", message, account.getLoginReqData());
                return LoginProgress.failed(batchId, platform, account, phone, message);
            }

            if (phone == null || !"available".equals(phone.getStatus())) {
                String message = "号码不可用，不允许登录";
                upsertLoginDetail(batchId, accountId, platform.getPlatformId(), "failed", message, account.getLoginReqData());
                return LoginProgress.failed(batchId, platform, account, phone, message);
            }

            upsertLoginDetail(batchId, accountId, platform.getPlatformId(), "processing", "正在登录", account.getLoginReqData());
            return LoginProgress.processing(batchId, platform, account, phone);
        });
    }
    private LoginProgress completeLogin(Long batchId, TicketPlatformConfig platform, TicketManagedAccount account, TicketLoginResult result, TicketPlatformAdapter adapter) {
        return transactionTemplate.execute(status -> {
            TicketManagedAccount currentAccount = accountMapper.selectById(account.getAccountId());
            if (currentAccount == null) {
                currentAccount = account;
            }
            TicketPhoneNumber phone = currentAccount.getPhoneId() == null ? null : phoneMapper.selectById(currentAccount.getPhoneId());
            TicketPhonePlatformRelation relation = currentAccount.getPhoneId() == null ? null : getRelation(platform.getPlatformId(), currentAccount.getPhoneId());

            if (result != null && result.isSuccess()) {
                currentAccount.setLoginStatus("logged_in");
                currentAccount.setAccountInfo(result.getAccountInfo());
                currentAccount.setLoginReqData(result.getReqData());
                currentAccount.setLastLoginTime(new Date());
                currentAccount.setLastError(null);
                saveAccount(currentAccount);

                if (relation != null) {
                    relation.setStatus("logged_in");
                    relation.setLastError(null);
                    relation.setLastOperateTime(new Date());
                    saveRelation(relation);
                }

                String message = StringUtils.defaultIfBlank(result.getMessage(), "登录成功");
                upsertLoginDetail(batchId, currentAccount.getAccountId(), platform.getPlatformId(), "success", message, currentAccount.getLoginReqData());
                return LoginProgress.success(batchId, platform, currentAccount, phone, message);
            }

            String error = adapter.normalizeError(result == null ? "login_result_missing" : result.getMessage());
            currentAccount.setLoginStatus("login_failed");
            currentAccount.setLastError(error);
            saveAccount(currentAccount);

            if (relation != null) {
                relation.setStatus("login_failed");
                relation.setLastError(error);
                relation.setLastOperateTime(new Date());
                saveRelation(relation);
            }

            upsertLoginDetail(batchId, currentAccount.getAccountId(), platform.getPlatformId(), "failed", error, currentAccount.getLoginReqData());
            return LoginProgress.failed(batchId, platform, currentAccount, phone, error);
        });
    }

    private void updateLoginBatch(Long batchId, String batchStatus, int successCount, int failedCount, List<Map<String, Object>> summary, Date executedAt) {
        LambdaUpdateWrapper<TicketLoginBatch> wrapper = new LambdaUpdateWrapper<TicketLoginBatch>()
            .eq(TicketLoginBatch::getBatchId, batchId)
            .set(TicketLoginBatch::getBatchStatus, batchStatus)
            .set(TicketLoginBatch::getSuccessCount, successCount)
            .set(TicketLoginBatch::getFailedCount, failedCount)
            .set(TicketLoginBatch::getResultSummary, JSONUtil.toJsonStr(summary));
        if (executedAt != null) {
            wrapper.set(TicketLoginBatch::getExecutedAt, executedAt);
        }
        loginBatchMapper.update(null, wrapper);
    }

    private void upsertLoginDetail(Long batchId, Long accountId, Long platformId, String executeStatus, String resultMessage, String reqData) {
        TicketLoginBatchDetail detail = loginBatchDetailMapper.selectOne(new LambdaQueryWrapper<TicketLoginBatchDetail>()
            .eq(TicketLoginBatchDetail::getBatchId, batchId)
            .eq(TicketLoginBatchDetail::getAccountId, accountId), false);
        if (detail == null) {
            detail = new TicketLoginBatchDetail();
            detail.setBatchId(batchId);
            detail.setAccountId(accountId);
            detail.setPlatformId(platformId);
        }
        detail.setExecuteStatus(executeStatus);
        detail.setResultMessage(fitResultMessage(resultMessage));
        detail.setReqData(reqData);
        detail.setExecutedAt(new Date());
        if (detail.getDetailId() == null) {
            loginBatchDetailMapper.insert(detail);
        } else {
            loginBatchDetailMapper.updateById(detail);
        }
    }

    private TicketLoginBatchDetail requireLoginDetail(Long batchId, Long detailId) {
        TicketLoginBatchDetail detail = loginBatchDetailMapper.selectOne(new LambdaQueryWrapper<TicketLoginBatchDetail>()
            .eq(TicketLoginBatchDetail::getBatchId, batchId)
            .eq(TicketLoginBatchDetail::getDetailId, detailId), false);
        if (detail == null) {
            throw new ServiceException("登录明细不存在");
        }
        return detail;
    }

    private TicketLoginBatchDetail findLoginDetail(Long batchId, Long accountId) {
        if (batchId == null || accountId == null) {
            return null;
        }
        return loginBatchDetailMapper.selectOne(new LambdaQueryWrapper<TicketLoginBatchDetail>()
            .eq(TicketLoginBatchDetail::getBatchId, batchId)
            .eq(TicketLoginBatchDetail::getAccountId, accountId), false);
    }

    private JSONObject parseLoginDetailReqData(String reqData) {
        if (StringUtils.isBlank(reqData)) {
            return JSONUtil.createObj();
        }
        try {
            return JSONUtil.parseObj(reqData);
        } catch (Exception ex) {
            JSONObject object = JSONUtil.createObj();
            object.set("rawReqData", reqData);
            return object;
        }
    }

    private long computeManualCodeTtlSeconds(Long expiresAtMillis) {
        if (expiresAtMillis == null) {
            return MANUAL_EMAIL_CODE_TIMEOUT_SECONDS;
        }
        long millis = expiresAtMillis - System.currentTimeMillis();
        return Math.max(1L, millis / 1000L);
    }

    private String manualLoginEmailCodeKey(String requestId) {
        return MANUAL_EMAIL_CODE_REDIS_PREFIX + requestId;
    }


    private void publishLoginProgress(LoginProgress progress, Long userId, int successCount, int failedCount, int processedCount, int totalCount) {
        if (userId == null || progress == null) {
            return;
        }
        TicketLoginProgressMessage message = new TicketLoginProgressMessage();
        message.setBatchId(progress.getBatchId());
        message.setPlatformId(progress.getPlatformId());
        message.setPlatformName(progress.getPlatformName());
        message.setAccountId(progress.getAccountId());
        message.setPhoneId(progress.getPhoneId());
        message.setEmail(progress.getEmail());
        message.setAccountInfo(progress.getAccountInfo());
        message.setReqData(progress.getReqData());
        message.setPhoneNumber(progress.getPhoneNumber());
        message.setStepStatus(progress.getStepStatus());
        message.setLoginStatus(progress.getLoginStatus());
        message.setLastError(progress.getLastError());
        message.setLastLoginTime(progress.getLastLoginTime());
        message.setMessage(progress.getMessage());
        message.setSuccessCount(successCount);
        message.setFailedCount(failedCount);
        message.setProcessedCount(processedCount);
        message.setTotalCount(totalCount);
        sendSseMessage(userId, message);
    }

    private void publishLoginBatchCompleted(Long batchId, TicketPlatformConfig platform, Long userId, int successCount, int failedCount, int totalCount) {
        if (userId == null) {
            return;
        }
        TicketLoginProgressMessage message = new TicketLoginProgressMessage();
        message.setBatchId(batchId);
        message.setPlatformId(platform.getPlatformId());
        message.setPlatformName(platform.getPlatformName());
        message.setStepStatus("completed");
        message.setMessage("登录批次执行完成");
        message.setSuccessCount(successCount);
        message.setFailedCount(failedCount);
        message.setProcessedCount(totalCount);
        message.setTotalCount(totalCount);
        sendSseMessage(userId, message);
    }

    private void publishLoginBatchFailed(Long batchId, TicketPlatformConfig platform, Long userId, String rawMessage, int successCount, int failedCount, int processedCount, int totalCount) {
        if (userId == null) {
            return;
        }
        TicketLoginProgressMessage message = new TicketLoginProgressMessage();
        message.setBatchId(batchId);
        message.setPlatformId(platform.getPlatformId());
        message.setPlatformName(platform.getPlatformName());
        message.setStepStatus("completed");
        message.setMessage("登录批次异常结束: " + StringUtils.defaultString(rawMessage, "unknown"));
        message.setSuccessCount(successCount);
        message.setFailedCount(failedCount);
        message.setProcessedCount(processedCount);
        message.setTotalCount(totalCount);
        sendSseMessage(userId, message);
    }

    private void publishRegisterProgress(
        RegistrationProgress progress,
        Long userId,
        int successCount,
        int failedCount,
        int skippedCount,
        int processedCount,
        int totalCount
    ) {
        if (userId == null || progress == null) {
            return;
        }
        TicketRegisterProgressMessage message = new TicketRegisterProgressMessage();
        message.setBatchId(progress.getBatchId());
        message.setPlatformId(progress.getPlatformId());
        message.setPlatformName(progress.getPlatformName());
        message.setPhoneId(progress.getPhoneId());
        message.setPhoneNumber(progress.getPhoneNumber());
        message.setStepStatus(progress.getStepStatus());
        message.setPhoneStatus(progress.getPhoneStatus());
        message.setNote(progress.getNote());
        message.setAccountId(progress.getAccountId());
        message.setEmail(progress.getEmail());
        message.setAccountInfo(progress.getAccountInfo());
        message.setReqData(progress.getReqData());
        message.setMessage(progress.getMessage());
        message.setSuccessCount(successCount);
        message.setFailedCount(failedCount);
        message.setSkippedCount(skippedCount);
        message.setProcessedCount(processedCount);
        message.setTotalCount(totalCount);
        fillPhoneRelationCounts(message, progress.getPhoneId());
        sendSseMessage(userId, message);
    }

    private void publishBatchCompleted(Long batchId, TicketPlatformConfig platform, Long userId, int successCount, int failedCount, int skippedCount, int totalCount) {
        if (userId == null) {
            return;
        }
        TicketRegisterProgressMessage message = new TicketRegisterProgressMessage();
        message.setBatchId(batchId);
        message.setPlatformId(platform.getPlatformId());
        message.setPlatformName(platform.getPlatformName());
        message.setStepStatus("completed");
        message.setMessage("注册批次执行完成");
        message.setSuccessCount(successCount);
        message.setFailedCount(failedCount);
        message.setSkippedCount(skippedCount);
        message.setProcessedCount(totalCount);
        message.setTotalCount(totalCount);
        sendSseMessage(userId, message);
    }

    private void publishBatchFailed(Long batchId, TicketPlatformConfig platform, Long userId, String rawMessage, int successCount, int failedCount, int skippedCount, int processedCount, int totalCount) {
        if (userId == null) {
            return;
        }
        TicketRegisterProgressMessage message = new TicketRegisterProgressMessage();
        message.setBatchId(batchId);
        message.setPlatformId(platform.getPlatformId());
        message.setPlatformName(platform.getPlatformName());
        message.setStepStatus("completed");
        message.setMessage("注册批次异常结束: " + StringUtils.defaultString(rawMessage, "unknown"));
        message.setSuccessCount(successCount);
        message.setFailedCount(failedCount);
        message.setSkippedCount(skippedCount);
        message.setProcessedCount(processedCount);
        message.setTotalCount(totalCount);
        sendSseMessage(userId, message);
    }

    private void sendSseMessage(Long userId, Object message) {
        SseMessageDto dto = new SseMessageDto();
        dto.setUserIds(List.of(userId));
        dto.setMessage(JSONUtil.toJsonStr(message));
        SseMessageUtils.publishMessage(dto);
    }

    private void fillPhoneRelationCounts(TicketRegisterProgressMessage message, Long phoneId) {
        if (phoneId == null) {
            return;
        }
        List<TicketPhonePlatformRelation> relations = relationMapper.selectList(new LambdaQueryWrapper<TicketPhonePlatformRelation>()
            .eq(TicketPhonePlatformRelation::getPhoneId, phoneId));
        message.setRegisteredPlatformCount(relations.size());
        message.setLoggedInPlatformCount((int) relations.stream().filter(item -> "logged_in".equals(item.getStatus())).count());
    }

    private void updateRegistrationBatch(Long batchId, String batchStatus, int successCount, int failedCount, int skippedCount, List<Map<String, Object>> summary, Date executedAt) {
        LambdaUpdateWrapper<TicketRegistrationBatch> wrapper = new LambdaUpdateWrapper<TicketRegistrationBatch>()
            .eq(TicketRegistrationBatch::getBatchId, batchId)
            .set(TicketRegistrationBatch::getBatchStatus, batchStatus)
            .set(TicketRegistrationBatch::getSuccessCount, successCount)
            .set(TicketRegistrationBatch::getFailedCount, failedCount)
            .set(TicketRegistrationBatch::getSkippedCount, skippedCount)
            .set(TicketRegistrationBatch::getResultSummary, JSONUtil.toJsonStr(summary));
        if (executedAt != null) {
            wrapper.set(TicketRegistrationBatch::getExecutedAt, executedAt);
        }
        registrationBatchMapper.update(null, wrapper);
    }

    private void upsertRegistrationDetail(Long batchId, Long phoneId, Long platformId, String executeStatus, String resultMessage, Long accountId, String email) {
        TicketRegistrationBatchDetail detail = registrationBatchDetailMapper.selectOne(new LambdaQueryWrapper<TicketRegistrationBatchDetail>()
            .eq(TicketRegistrationBatchDetail::getBatchId, batchId)
            .eq(TicketRegistrationBatchDetail::getPhoneId, phoneId), false);
        if (detail == null) {
            detail = new TicketRegistrationBatchDetail();
            detail.setBatchId(batchId);
            detail.setPhoneId(phoneId);
            detail.setPlatformId(platformId);
        }
        detail.setExecuteStatus(executeStatus);
        detail.setResultMessage(fitResultMessage(resultMessage));
        detail.setAccountId(accountId);
        detail.setEmail(email);
        detail.setExecutedAt(new Date());
        if (detail.getDetailId() == null) {
            registrationBatchDetailMapper.insert(detail);
        } else {
            registrationBatchDetailMapper.updateById(detail);
        }
    }

    private void upsertRegistrationDetailByAccount(Long batchId, Long platformId, Long accountId, String email, String executeStatus, String resultMessage) {
        LambdaQueryWrapper<TicketRegistrationBatchDetail> query = new LambdaQueryWrapper<TicketRegistrationBatchDetail>()
            .eq(TicketRegistrationBatchDetail::getBatchId, batchId);
        if (accountId != null) {
            query.eq(TicketRegistrationBatchDetail::getAccountId, accountId);
        } else if (StringUtils.isNotBlank(email)) {
            query.eq(TicketRegistrationBatchDetail::getEmail, email);
        } else {
            query.isNull(TicketRegistrationBatchDetail::getAccountId)
                .isNull(TicketRegistrationBatchDetail::getEmail);
        }
        TicketRegistrationBatchDetail detail = registrationBatchDetailMapper.selectOne(query, false);
        TicketManagedAccount account = accountId == null ? null : accountMapper.selectById(accountId);
        if (detail == null) {
            detail = new TicketRegistrationBatchDetail();
            detail.setBatchId(batchId);
        }
        detail.setPlatformId(platformId);
        detail.setPhoneId(account == null ? detail.getPhoneId() : account.getPhoneId());
        detail.setAccountId(accountId);
        detail.setEmail(email);
        detail.setExecuteStatus(executeStatus);
        detail.setResultMessage(fitResultMessage(resultMessage));
        detail.setExecutedAt(new Date());
        if (detail.getDetailId() == null) {
            registrationBatchDetailMapper.insert(detail);
        } else {
            registrationBatchDetailMapper.updateById(detail);
        }
    }

    private TicketPlatformConfig requirePlatform(Long platformId) {
        TicketPlatformConfig platform = platformMapper.selectById(platformId);
        if (platform == null) {
            throw new ServiceException("Platform not found");
        }
        return platform;
    }

    private void applyPlatformCapabilityDefaults(TicketPlatformConfig platform) {
        if (platform == null) {
            return;
        }
        if (StringUtils.isBlank(platform.getAdapterType())) {
            platform.setAdapterType(resolvePlatformAdapterType(platform.getPlatformCode(), null));
        }
        if (isHandsFormPlatform(platform)) {
            platform.setSupportsBatchRegister(Boolean.FALSE);
            platform.setSupportsBatchLogin(Boolean.FALSE);
            platform.setSupportsSms(Boolean.FALSE);
            platform.setSupportsEmail(Boolean.TRUE);
            platform.setSupportsPhoneIdentity(Boolean.FALSE);
            return;
        }
        if (isJumpShopPlatform(platform)) {
            platform.setAdapterType(JUMP_SHOP_ADAPTER_TYPE);
            if (platform.getSupportsBatchRegister() == null) {
                platform.setSupportsBatchRegister(Boolean.TRUE);
            }
            if (platform.getSupportsBatchLogin() == null) {
                platform.setSupportsBatchLogin(Boolean.TRUE);
            }
            if (platform.getSupportsSms() == null) {
                platform.setSupportsSms(Boolean.FALSE);
            }
            if (platform.getSupportsEmail() == null) {
                platform.setSupportsEmail(Boolean.TRUE);
            }
            if (platform.getSupportsPhoneIdentity() == null) {
                platform.setSupportsPhoneIdentity(Boolean.FALSE);
            }
            return;
        }
        if (platform.getSupportsPhoneIdentity() == null) {
            platform.setSupportsPhoneIdentity(Boolean.TRUE);
        }
        if (platform.getSupportsEmail() == null) {
            platform.setSupportsEmail(Boolean.TRUE);
        }
    }

    private boolean isLivePocketPlatform(TicketPlatformConfig platform) {
        return matchesPlatformCode(platform, LIVEPOCKET_PLATFORM_CODE);
    }

    private boolean isLivePocketFlashSaleTask(TicketPlatformConfig platform, String purchaseType) {
        return isLivePocketPlatform(platform) && TicketOrderFlowSupport.isFlashSale(purchaseType);
    }

    private boolean isJumpShopPlatform(TicketPlatformConfig platform) {
        return matchesPlatformCode(platform, JUMP_SHOP_PLATFORM_CODE) || matchesPlatformCode(platform, JUMP_SHOP_ADAPTER_TYPE);
    }

    private boolean isJumpShopFlashSaleTask(TicketPlatformConfig platform, String purchaseType) {
        return isJumpShopPlatform(platform) && TicketOrderFlowSupport.isFlashSale(purchaseType);
    }

    private boolean isPythonFlashSaleTask(TicketPlatformConfig platform, String purchaseType) {
        return TicketOrderFlowSupport.isFlashSale(purchaseType)
            && (isLivePocketPlatform(platform) || isJumpShopPlatform(platform));
    }

    private boolean isHandsFormPlatform(TicketPlatformConfig platform) {
        return matchesPlatformCode(platform, HANDS_FORM_PLATFORM_CODE);
    }

    private String resolvePlatformAdapterType(String platformCode, String currentAdapterType) {
        String normalizedCode = StringUtils.defaultIfBlank(platformCode, "").trim();
        String normalizedAdapterType = StringUtils.defaultIfBlank(currentAdapterType, "").trim();
        if (JUMP_SHOP_PLATFORM_CODE.equalsIgnoreCase(normalizedCode) || JUMP_SHOP_ADAPTER_TYPE.equalsIgnoreCase(normalizedCode)) {
            return JUMP_SHOP_ADAPTER_TYPE;
        }
        return StringUtils.defaultIfBlank(normalizedAdapterType, normalizedCode);
    }

    private boolean matchesPlatformCode(TicketPlatformConfig platform, String platformCode) {
        if (platform == null || StringUtils.isBlank(platformCode)) {
            return false;
        }
        String currentCode = StringUtils.defaultIfBlank(platform.getAdapterType(), platform.getPlatformCode());
        return platformCode.equalsIgnoreCase(currentCode) || platformCode.equalsIgnoreCase(platform.getPlatformCode());
    }

    private boolean platformSupportsPhoneIdentity(TicketPlatformConfig platform) {
        if (platform == null) {
            return true;
        }
        if (isHandsFormPlatform(platform)) {
            return false;
        }
        return !Boolean.FALSE.equals(platform.getSupportsPhoneIdentity());
    }

    private void assertPlatformSupportsBatchRegister(TicketPlatformConfig platform) {
        if (isHandsFormPlatform(platform)) {
            throw new ServiceException("当前平台不支持批量注册");
        }
    }

    private void assertPlatformSupportsBatchLogin(TicketPlatformConfig platform) {
        if (isHandsFormPlatform(platform)) {
            throw new ServiceException("当前平台不支持批量登录");
        }
    }

    private TicketPlatformConfig findPlatformByCode(String platformCode) {
        List<TicketPlatformConfig> platforms = platformMapper.selectList(new LambdaQueryWrapper<TicketPlatformConfig>()
            .eq(TicketPlatformConfig::getPlatformCode, platformCode)
            .last("limit 2"));
        if (CollUtil.isEmpty(platforms)) {
            return null;
        }
        if (platforms.size() > 1) {
            throw new ServiceException("Platform code is duplicated: " + platformCode);
        }
        return platforms.get(0);
    }

    private List<TicketPhoneNumber> loadPhonesForRegister(TicketBatchRegisterBo bo) {
        if (CollUtil.isNotEmpty(bo.getPhoneIds())) {
            return phoneMapper.selectByIds(bo.getPhoneIds());
        }
        LambdaQueryWrapper<TicketPhoneNumber> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StringUtils.isNotBlank(bo.getSupplier()), TicketPhoneNumber::getSupplier, bo.getSupplier())
            .eq(StringUtils.isNotBlank(bo.getCountryCode()), TicketPhoneNumber::getCountryCode, bo.getCountryCode())
            .eq(StringUtils.isNotBlank(bo.getStatus()), TicketPhoneNumber::getStatus, bo.getStatus())
            .orderByDesc(TicketPhoneNumber::getPhoneId);
        return phoneMapper.selectList(wrapper);
    }

    private List<TicketManagedAccount> loadAccountsForLogin(Long platformId, TicketBatchLoginBo bo) {
        TicketPlatformConfig platform = requirePlatform(platformId);
        boolean allowRegistered = isJumpShopPlatform(platform);
        String loginMode = normalizeLoginMode(bo.getLoginMode());
        if (!isLivePocketPlatform(platform) && LOGIN_MODE_MANUAL_EMAIL_CODE.equals(loginMode)) {
            throw new ServiceException("当前平台不支持人工验证码登录");
        }

        List<TicketManagedAccount> accounts;
        boolean explicitAccounts = CollUtil.isNotEmpty(bo.getAccountIds());
        if (CollUtil.isNotEmpty(bo.getAccountIds())) {
            accounts = accountMapper.selectByIds(bo.getAccountIds());
            boolean hasInvalidAccount = accounts.stream().anyMatch(account ->
                !Objects.equals(account.getPlatformId(), platformId)
                    || !(allowRegistered ? List.of("activated", "registered").contains(account.getAccountStatus()) : "activated".equals(account.getAccountStatus()))
            );
            if (hasInvalidAccount || accounts.size() != CollUtil.distinct(bo.getAccountIds()).size()) {
                throw new ServiceException(allowRegistered ? "批量登录只能选择目标平台下已注册或已激活账号" : "批量登录只能选择目标平台下已激活账号");
            }
        } else {
            LambdaQueryWrapper<TicketManagedAccount> wrapper = Wrappers.lambdaQuery();
            wrapper.eq(TicketManagedAccount::getPlatformId, platformId)
                .in(TicketManagedAccount::getAccountStatus, allowRegistered ? List.of("activated", "registered") : List.of("activated"))
                .orderByDesc(TicketManagedAccount::getAccountId);
            accounts = accountMapper.selectList(wrapper);
        }
        if (isLivePocketPlatform(platform)) {
            accounts = filterAndValidateLivePocketLoginAccounts(accounts, loginMode, explicitAccounts);
        }
        return accounts;
    }

    private String normalizeLoginMode(String loginMode) {
        String normalized = StringUtils.defaultIfBlank(StringUtils.trim(loginMode), LOGIN_MODE_AUTO_MAILBOX);
        if (!LOGIN_MODE_AUTO_MAILBOX.equals(normalized) && !LOGIN_MODE_MANUAL_EMAIL_CODE.equals(normalized)) {
            throw new ServiceException("不支持的登录模式: " + loginMode);
        }
        return normalized;
    }

    private List<TicketManagedAccount> filterAndValidateLivePocketLoginAccounts(
        List<TicketManagedAccount> accounts,
        String loginMode,
        boolean explicitAccounts
    ) {
        if (CollUtil.isEmpty(accounts)) {
            return accounts;
        }
        Map<String, TicketMailboxAccount> mailboxMap = loadMailboxByEmail(accounts.stream().map(TicketManagedAccount::getEmail).toList());
        List<TicketManagedAccount> result = new ArrayList<>();
        for (TicketManagedAccount account : accounts) {
            boolean internalMailbox = isInternalMailbox(account, mailboxMap);
            if (LOGIN_MODE_MANUAL_EMAIL_CODE.equals(loginMode)) {
                if (internalMailbox) {
                    if (explicitAccounts) {
                        throw new ServiceException("人工验证码登录只能选择外部邮箱账号");
                    }
                    continue;
                }
                if (StringUtils.isBlank(resolveAccountLoginPassword(account))) {
                    if (explicitAccounts) {
                        throw new ServiceException("外部邮箱账号缺少平台密码，不能执行人工验证码登录");
                    }
                    continue;
                }
            } else {
                if (!internalMailbox) {
                    if (explicitAccounts) {
                        throw new ServiceException("自动登录只能选择内部邮箱账号");
                    }
                    continue;
                }
            }
            result.add(account);
        }
        return result;
    }

    private List<TicketManagedAccount> sortAccountsForLogin(List<TicketManagedAccount> accounts) {
        if (CollUtil.isEmpty(accounts)) {
            return accounts;
        }
        return accounts.stream()
            .sorted(Comparator
                .comparingInt(this::loginAccountPriority)
                .thenComparing(TicketManagedAccount::getAccountId, Comparator.nullsLast(Long::compareTo)))
            .toList();
    }

    private int loginAccountPriority(TicketManagedAccount account) {
        if (account == null) {
            return 3;
        }
        if (StringUtils.isNotBlank(account.getLoginReqData())) {
            return 0;
        }
        if (StringUtils.isNotBlank(resolveAccountLoginPassword(account))) {
            return 1;
        }
        return 2;
    }

    private TicketPhonePlatformRelation getRelation(Long platformId, Long phoneId) {
        return relationMapper.selectOne(new LambdaQueryWrapper<TicketPhonePlatformRelation>()
            .eq(TicketPhonePlatformRelation::getPlatformId, platformId)
            .eq(TicketPhonePlatformRelation::getPhoneId, phoneId), false);
    }

    private TicketManagedAccount quickCreateSingleHandsFormAccount(TicketPlatformConfig platform) {
        if (platform == null || !isHandsFormPlatform(platform)) {
            throw new ServiceException("当前平台不是 hands-form");
        }
        TicketMailboxAccount mailbox = selectAvailableHandsFormMailbox(platform.getPlatformId());
        if (mailbox == null || StringUtils.isBlank(mailbox.getEmail())) {
            throw new ServiceException("没有可用邮箱账号");
        }
        if (platformEmailExists(platform.getPlatformId(), mailbox.getEmail())) {
            throw new ServiceException("邮箱已被当前平台占用，请重试");
        }
        HandsFormProfile profile = generateHandsFormProfile();
        TicketManagedAccount account = new TicketManagedAccount();
        account.setPlatformId(platform.getPlatformId());
        account.setPhoneId(null);
        account.setEmail(mailbox.getEmail());
        account.setAccountInfo(buildHandsFormAccountInfo(profile));
        account.setReqData(null);
        account.setLoginReqData(null);
        account.setAccountStatus("activated");
        account.setLoginStatus("offline");
        account.setLastLoginTime(null);
        account.setLastError(null);
        saveAccount(account);
        claimMailboxForAccount(mailbox, account.getAccountId());

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("platformId", platform.getPlatformId());
        auditPayload.put("accountId", account.getAccountId());
        auditPayload.put("email", account.getEmail());
        auditPayload.put("mailboxId", mailbox.getMailboxId());
        auditPayload.put("source", "hands_form_quick_create");
        recordAudit("account", "quickCreateHandsForm", "account", String.valueOf(account.getAccountId()), "success", "Hands 账号已快速创建", auditPayload);
        return account;
    }

    private TicketMailboxAccount selectAvailableHandsFormMailbox(Long platformId) {
        List<TicketMailboxAccount> mailboxes = mailboxAccountMapper.selectList(new LambdaQueryWrapper<TicketMailboxAccount>()
            .eq(TicketMailboxAccount::getStatus, "available")
            .isNull(TicketMailboxAccount::getUsedAccountId)
            .orderByAsc(TicketMailboxAccount::getMailboxId)
            .last("limit 20"));
        for (TicketMailboxAccount mailbox : mailboxes) {
            if (!platformEmailExists(platformId, mailbox.getEmail())) {
                return mailbox;
            }
        }
        TicketMailboxAccount mailbox = mailboxAccountService.createAvailableMailbox();
        if (platformEmailExists(platformId, mailbox.getEmail())) {
            throw new ServiceException("自动创建的新邮箱已被当前平台占用，请重试");
        }
        return mailbox;
    }

    private boolean platformEmailExists(Long platformId, String email) {
        if (platformId == null || StringUtils.isBlank(email)) {
            return false;
        }
        return accountMapper.selectCount(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platformId)
            .eq(TicketManagedAccount::getEmail, email)) > 0;
    }

    private void claimMailboxForAccount(TicketMailboxAccount mailbox, Long accountId) {
        int updatedRows = mailboxAccountMapper.update(null, Wrappers.<TicketMailboxAccount>lambdaUpdate()
            .eq(TicketMailboxAccount::getMailboxId, mailbox.getMailboxId())
            .eq(TicketMailboxAccount::getStatus, "available")
            .isNull(TicketMailboxAccount::getUsedAccountId)
            .set(TicketMailboxAccount::getStatus, "used")
            .set(TicketMailboxAccount::getUsedAccountId, accountId)
            .set(TicketMailboxAccount::getUsedTime, new Date())
            .set(TicketMailboxAccount::getLastError, null));
        if (updatedRows <= 0) {
            throw new ServiceException("邮箱账号已被占用，请重试");
        }
    }

    private String buildHandsFormAccountInfo(HandsFormProfile profile) {
        JSONObject accountInfo = JSONUtil.createObj();
        accountInfo.set("identityType", "form_profile");
        accountInfo.set("source", "hands_quick_create");
        accountInfo.set("familyName", profile.familyName());
        accountInfo.set("givenName", profile.givenName());
        accountInfo.set("fullName", profile.fullName());
        accountInfo.set("furigana", profile.furigana());
        return accountInfo.toString();
    }

    private HandsFormProfile generateHandsFormProfile() {
        NamePair familyName = HANDS_FORM_FAMILY_NAMES.get(RandomUtil.randomInt(HANDS_FORM_FAMILY_NAMES.size()));
        NamePair givenName = HANDS_FORM_GIVEN_NAMES.get(RandomUtil.randomInt(HANDS_FORM_GIVEN_NAMES.size()));
        return new HandsFormProfile(
            familyName.text(),
            givenName.text(),
            familyName.text() + givenName.text(),
            familyName.kana() + givenName.kana()
        );
    }

    private String normalizeJsonText(String value) {
        String normalized = StringUtils.trim(value);
        return StringUtils.isBlank(normalized) ? null : normalized;
    }

    private String mergeAccountLastName(String accountInfoText, String lastName) {
        JSONObject accountInfo;
        if (StringUtils.isBlank(accountInfoText)) {
            accountInfo = JSONUtil.createObj();
        } else {
            try {
                accountInfo = JSONUtil.parseObj(accountInfoText);
            } catch (Exception ex) {
                log.warn("parse account info failed when updating last name, accountInfo={}", accountInfoText, ex);
                accountInfo = JSONUtil.createObj();
            }
        }
        accountInfo.set("familyName", lastName);
        return accountInfo.toString();
    }

    private void updateAccountLastError(Long accountId, String message) {
        accountMapper.update(null, Wrappers.lambdaUpdate(TicketManagedAccount.class)
            .eq(TicketManagedAccount::getAccountId, accountId)
            .set(TicketManagedAccount::getLastError, fitLastError(message)));
    }

    private Map<String, Object> buildAccountLastNameAuditPayload(TicketManagedAccount account, String lastName, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", account.getAccountId());
        payload.put("platformId", account.getPlatformId());
        payload.put("email", account.getEmail());
        payload.put("lastName", lastName);
        if (StringUtils.isNotBlank(message)) {
            payload.put("message", message);
        }
        return payload;
    }

    private String resolveRelationStatus(String accountStatus, String loginStatus) {
        if ("disabled".equals(accountStatus)) {
            return "blocked";
        }
        if ("pending_register".equals(accountStatus)) {
            return "registering";
        }
        if ("pending_activation".equals(accountStatus)) {
            return "verification_pending";
        }
        if ("logged_in".equals(loginStatus)) {
            return "logged_in";
        }
        if ("login_failed".equals(loginStatus)) {
            return "login_failed";
        }
        return "registered";
    }

    private TicketManagedAccount getOrCreateAccount(Long platformId, Long phoneId) {
        TicketManagedAccount account = accountMapper.selectOne(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platformId)
            .eq(TicketManagedAccount::getPhoneId, phoneId), false);
        return account == null ? new TicketManagedAccount() : account;
    }

    private void saveRelation(TicketPhonePlatformRelation relation) {
        relation.setLastError(fitLastError(relation.getLastError()));
        if (relation.getRelationId() == null) {
            relationMapper.insert(relation);
        } else {
            relationMapper.updateById(relation);
            if (relation.getAccountId() == null || relation.getLastError() == null) {
                LambdaUpdateWrapper<TicketPhonePlatformRelation> updateWrapper = Wrappers.lambdaUpdate();
                updateWrapper.eq(TicketPhonePlatformRelation::getRelationId, relation.getRelationId());
                if (relation.getAccountId() == null) {
                    updateWrapper.set(TicketPhonePlatformRelation::getAccountId, null);
                }
                if (relation.getLastError() == null) {
                    updateWrapper.set(TicketPhonePlatformRelation::getLastError, null);
                }
                relationMapper.update(null, updateWrapper);
            }
        }
    }

    private void saveAccount(TicketManagedAccount account) {
        account.setLastError(fitLastError(account.getLastError()));
        if (account.getAccountId() == null) {
            accountMapper.insert(account);
        } else {
            accountMapper.updateById(account);
            if (account.getLastError() == null) {
                accountMapper.update(
                    null,
                    Wrappers.<TicketManagedAccount>lambdaUpdate()
                        .eq(TicketManagedAccount::getAccountId, account.getAccountId())
                        .set(TicketManagedAccount::getLastError, null)
                );
            }
        }
    }

    private Map<String, Object> buildDetail(Long key, String status, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("key", key);
        detail.put("status", status);
        detail.put("message", message);
        return detail;
    }

    private void enrichPhonePage(List<TicketPhoneNumberVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        List<Long> phoneIds = rows.stream().map(TicketPhoneNumberVo::getPhoneId).toList();
        Map<Long, List<TicketPhonePlatformRelation>> relationMap = relationMapper.selectList(new LambdaQueryWrapper<TicketPhonePlatformRelation>()
                .in(TicketPhonePlatformRelation::getPhoneId, phoneIds))
            .stream()
            .collect(Collectors.groupingBy(TicketPhonePlatformRelation::getPhoneId));
        for (TicketPhoneNumberVo row : rows) {
            List<TicketPhonePlatformRelation> relations = relationMap.getOrDefault(row.getPhoneId(), List.of());
            row.setRegisteredPlatformCount(relations.size());
            row.setLoggedInPlatformCount((int) relations.stream().filter(item -> "logged_in".equals(item.getStatus())).count());
        }
    }

    private void enrichRelations(List<TicketPhonePlatformRelationVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketPhonePlatformRelationVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        Map<Long, TicketPhoneNumber> phoneMap = loadMap(rows.stream().map(TicketPhonePlatformRelationVo::getPhoneId).filter(Objects::nonNull).toList(), phoneMapper::selectByIds, TicketPhoneNumber::getPhoneId);
        Map<Long, TicketManagedAccount> accountMap = loadMap(rows.stream().map(TicketPhonePlatformRelationVo::getAccountId).filter(Objects::nonNull).toList(), accountMapper::selectByIds, TicketManagedAccount::getAccountId);
        for (TicketPhonePlatformRelationVo row : rows) {
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
            TicketPhoneNumber phone = phoneMap.get(row.getPhoneId());
            if (phone != null) {
                row.setPhoneNumber(phone.getPhoneNumber());
            }
            TicketManagedAccount account = accountMap.get(row.getAccountId());
            if (account != null) {
                row.setEmail(account.getEmail());
            }
        }
    }

    private void enrichAccounts(List<TicketManagedAccountVo> rows) {
        enrichAccounts(rows, null);
    }

    private void enrichAccounts(List<TicketManagedAccountVo> rows, TicketManagedAccountBo bo) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketManagedAccountVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        Map<Long, TicketPhoneNumber> phoneMap = loadMap(rows.stream().map(TicketManagedAccountVo::getPhoneId).filter(Objects::nonNull).toList(), phoneMapper::selectByIds, TicketPhoneNumber::getPhoneId);
        Map<String, TicketMailboxAccount> mailboxMap = loadMailboxByEmail(rows.stream().map(TicketManagedAccountVo::getEmail).toList());
        for (TicketManagedAccountVo row : rows) {
            row.setLotteryLinkOccupied(Boolean.FALSE);
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
                row.setPlatformCode(platform.getPlatformCode());
            }
            TicketPhoneNumber phone = phoneMap.get(row.getPhoneId());
            if (phone != null) {
                row.setPhoneNumber(phone.getPhoneNumber());
            }
            TicketMailboxAccount mailbox = mailboxMap.get(normalizeEmailKey(row.getEmail()));
            if (mailbox == null) {
                row.setMailboxBindingStatus(isInternalMailboxDomain(row.getEmail()) ? MAILBOX_BINDING_INTERNAL_DOMAIN : MAILBOX_BINDING_EXTERNAL);
            } else {
                row.setMailboxId(mailbox.getMailboxId());
                row.setMailboxBoundAccountId(mailbox.getUsedAccountId());
                if (Objects.equals(mailbox.getUsedAccountId(), row.getAccountId())) {
                    row.setMailboxBindingStatus(MAILBOX_BINDING_BOUND);
                } else if (mailbox.getUsedAccountId() == null) {
                    row.setMailboxBindingStatus(MAILBOX_BINDING_AVAILABLE);
                } else {
                    row.setMailboxBindingStatus(MAILBOX_BINDING_SHARED);
                }
            }
        }
        if (bo != null && TicketOrderFlowSupport.isLottery(bo.getPurchaseType()) && StringUtils.isNotBlank(bo.getLotteryEventUrl())) {
            enrichLotteryLinkOccupancy(rows, bo.getPlatformId(), bo.getLotteryEventUrl(), null);
        }
    }

    private void enrichRegistrationBatches(List<TicketRegistrationBatchVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketRegistrationBatchVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        for (TicketRegistrationBatchVo row : rows) {
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
        }
    }

    private void enrichLoginDetailManualCodeFields(TicketLoginBatchDetailVo row) {
        JSONObject reqData = parseLoginDetailReqData(row.getReqData());
        row.setLoginMode(reqData.getStr("loginMode"));
        row.setVerifyCodeRequestId(reqData.getStr("verifyCodeRequestId"));
        Long expiresAtMillis = reqData.getLong("verifyCodeExpiresAt");
        if (expiresAtMillis != null) {
            row.setVerifyCodeExpiresAt(new Date(expiresAtMillis));
        }
        row.setVerifyCodeAttemptCount(reqData.getInt("verifyCodeAttemptCount"));
    }

    private void enrichRegistrationBatchDetails(List<TicketRegistrationBatchDetailVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketPhoneNumber> phoneMap = loadMap(rows.stream().map(TicketRegistrationBatchDetailVo::getPhoneId).filter(Objects::nonNull).toList(), phoneMapper::selectByIds, TicketPhoneNumber::getPhoneId);
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketRegistrationBatchDetailVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        Map<Long, TicketManagedAccount> accountMap = loadMap(rows.stream().map(TicketRegistrationBatchDetailVo::getAccountId).filter(Objects::nonNull).toList(), accountMapper::selectByIds, TicketManagedAccount::getAccountId);
        for (TicketRegistrationBatchDetailVo row : rows) {
            TicketPhoneNumber phone = phoneMap.get(row.getPhoneId());
            if (phone != null) {
                row.setPhoneNumber(phone.getPhoneNumber());
            }
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
            TicketManagedAccount account = accountMap.get(row.getAccountId());
            if (account != null) {
                row.setEmail(account.getEmail());
                row.setAccountInfo(account.getAccountInfo());
                row.setReqData(account.getReqData());
            }
        }
    }

    private void enrichLoginBatches(List<TicketLoginBatchVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketLoginBatchVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        for (TicketLoginBatchVo row : rows) {
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
        }
    }

    private void enrichLoginBatchDetails(List<TicketLoginBatchDetailVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketManagedAccount> accountMap = loadMap(rows.stream().map(TicketLoginBatchDetailVo::getAccountId).filter(Objects::nonNull).toList(), accountMapper::selectByIds, TicketManagedAccount::getAccountId);
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketLoginBatchDetailVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        Map<Long, TicketPhoneNumber> phoneMap = loadMap(accountMap.values().stream().map(TicketManagedAccount::getPhoneId).filter(Objects::nonNull).toList(), phoneMapper::selectByIds, TicketPhoneNumber::getPhoneId);
        for (TicketLoginBatchDetailVo row : rows) {
            TicketManagedAccount account = accountMap.get(row.getAccountId());
            if (account != null) {
                row.setEmail(account.getEmail());
                row.setAccountInfo(account.getAccountInfo());
                TicketPhoneNumber phone = phoneMap.get(account.getPhoneId());
                if (phone != null) {
                    row.setPhoneNumber(phone.getPhoneNumber());
                }
            }
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
            enrichLoginDetailManualCodeFields(row);
        }
    }

    private void enrichEventPage(List<TicketEventConfigVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketEventConfigVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        for (TicketEventConfigVo row : rows) {
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
        }
    }

    private Map<String, Integer> buildSaleTaskExecutionSummary(Long taskId, String purchaseType, Long scheduleVersion) {
        List<String> orderedStatuses = List.of("queued", "running", "submitted", "pending_payment", "paid", "blocked", "failed", "cancelled");
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("total", 0);
        orderedStatuses.forEach(status -> summary.put(status, 0));
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
                .select(TicketOrderExecution::getExecutionId, TicketOrderExecution::getAccountId, TicketOrderExecution::getLotteryScheduleId,
                    TicketOrderExecution::getExecutionStatus)
                .eq(TicketOrderExecution::getTaskId, taskId)
                .eq(TicketOrderExecution::getScheduleVersion, defaultScheduleVersion(scheduleVersion))
                .orderByAsc(TicketOrderExecution::getExecutionId));
        effectiveSaleTaskExecutions(purchaseType, executions).forEach(execution -> countExecutionStatus(summary, execution));
        return summary;
    }

    private Map<Long, Map<String, Integer>> buildSaleTaskExecutionSummaryMap(List<TicketSaleTaskVo> tasks) {
        if (CollUtil.isEmpty(tasks)) {
            return Map.of();
        }
        Map<Long, Long> versionMap = tasks.stream()
            .filter(item -> item.getTaskId() != null)
            .collect(Collectors.toMap(TicketSaleTaskVo::getTaskId, item -> defaultScheduleVersion(item.getScheduleVersion()), (left, right) -> left, LinkedHashMap::new));
        List<Long> taskIds = new ArrayList<>(versionMap.keySet());
        Map<Long, Map<String, Integer>> summaryMap = new HashMap<>();
        Map<Long, String> purchaseTypeMap = tasks.stream()
            .filter(item -> item.getTaskId() != null)
            .collect(Collectors.toMap(TicketSaleTaskVo::getTaskId, TicketSaleTaskVo::getPurchaseType, (left, right) -> left, LinkedHashMap::new));
        Map<Long, List<Long>> taskIdsByVersion = versionMap.entrySet().stream()
            .collect(Collectors.groupingBy(Map.Entry::getValue, LinkedHashMap::new, Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        taskIdsByVersion.forEach((scheduleVersion, groupedTaskIds) -> {
            Map<Long, List<TicketOrderExecution>> executionMap = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
                    .select(TicketOrderExecution::getExecutionId, TicketOrderExecution::getTaskId, TicketOrderExecution::getAccountId,
                        TicketOrderExecution::getLotteryScheduleId, TicketOrderExecution::getExecutionStatus)
                    .eq(TicketOrderExecution::getScheduleVersion, scheduleVersion)
                    .in(TicketOrderExecution::getTaskId, groupedTaskIds)
                    .orderByAsc(TicketOrderExecution::getExecutionId))
                .stream()
                .collect(Collectors.groupingBy(TicketOrderExecution::getTaskId, LinkedHashMap::new, Collectors.toList()));
            executionMap.forEach((taskId, executions) -> {
                Map<String, Integer> summary = summaryMap.computeIfAbsent(taskId, key -> emptyExecutionSummary());
                effectiveSaleTaskExecutions(purchaseTypeMap.get(taskId), executions)
                    .forEach(execution -> countExecutionStatus(summary, execution));
            });
        });
        taskIds.forEach(taskId -> summaryMap.computeIfAbsent(taskId, key -> emptyExecutionSummary()));
        return summaryMap;
    }

    private Map<String, Integer> emptyExecutionSummary() {
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("total", 0);
        return summary;
    }

    private void countExecutionStatus(Map<String, Integer> summary, TicketOrderExecution execution) {
        String status = StringUtils.defaultIfBlank(execution.getExecutionStatus(), "unknown");
        if ("timeout".equals(status)) {
            status = "failed";
        }
        summary.put("total", summary.getOrDefault("total", 0) + 1);
        summary.put(status, summary.getOrDefault(status, 0) + 1);
    }

    private List<TicketOrderExecution> effectiveSaleTaskExecutions(String purchaseType, List<TicketOrderExecution> executions) {
        if (CollUtil.isEmpty(executions) || !TicketOrderFlowSupport.isLottery(purchaseType)) {
            return executions;
        }
        Map<String, TicketOrderExecution> latest = new LinkedHashMap<>();
        List<TicketOrderExecution> fallback = new ArrayList<>();
        for (TicketOrderExecution execution : executions) {
            String key = lotteryExecutionRetryKey(execution);
            if (StringUtils.isBlank(key)) {
                fallback.add(execution);
                continue;
            }
            latest.put(key, execution);
        }
        List<TicketOrderExecution> result = new ArrayList<>(latest.values());
        result.addAll(fallback);
        return result;
    }

    private List<TicketSaleTaskProcessStepVo> buildSaleTaskProcessSteps(TicketSaleTaskVo task, List<TicketSaleTaskScheduleVo> schedules, Map<String, Integer> executionSummary) {
        List<TicketSaleTaskProcessStepVo> steps = new ArrayList<>();
        boolean isLottery = TicketOrderFlowSupport.isLottery(task.getPurchaseType());
        Map<String, Object> taskOptions = TicketOrderFlowSupport.parseTaskOptions(task.getTaskOptions());
        int accountCount = ObjectUtil.defaultIfNull(task.getBoundAccountCount(), 0);
        int scheduleCount = CollUtil.size(schedules);
        int executionCount = executionSummary.getOrDefault("total", 0);
        if (isLottery) {
            addProcessStep(steps, "lottery_link", "输入抽票链接", StringUtils.defaultIfBlank(task.getLotteryEventUrl(), Convert.toStr(taskOptions.get("eventUrl"))), StringUtils.isNotBlank(task.getLotteryEventUrl()) || taskOptions.containsKey("eventUrl"), task.getCreateTime());
            addProcessStep(steps, "lottery_parse", "Python 解析活动", buildLotteryParseDescription(task, taskOptions), CollUtil.isNotEmpty(task.getSelectedSessions()) || taskOptions.containsKey("eventTitle"), task.getCreateTime());
            addProcessStep(steps, "accounts", "选择账号", accountCount > 0 ? "已选择 " + accountCount + " 个账号" : "尚未绑定账号", accountCount > 0, task.getCreateTime());
            addProcessStep(steps, "sessions", "选择场次", CollUtil.isNotEmpty(task.getSelectedSessions()) ? "已选择 " + task.getSelectedSessions().size() + " 个场次" : "尚未选择场次", CollUtil.isNotEmpty(task.getSelectedSessions()), task.getCreateTime());
            addProcessStep(steps, "schedules", "保存分时段计划", scheduleCount > 0 ? "已配置 " + scheduleCount + " 个执行时段" : "尚未配置时段", scheduleCount > 0, task.getCreateTime());
        } else {
            addProcessStep(steps, "platform", "选择平台", StringUtils.defaultIfBlank(task.getPlatformName(), "平台信息待补全"), ObjectUtil.isNotNull(task.getPlatformId()), task.getCreateTime());
            addProcessStep(steps, "template", "加载抢票配置", StringUtils.defaultIfBlank(task.getConfigSchemaKey(), "使用平台默认配置"), StringUtils.isNotBlank(task.getTaskOptions()), task.getCreateTime());
            addProcessStep(steps, "accounts", "选择账号", accountCount > 0 ? "已选择 " + accountCount + " 个账号" : "尚未绑定账号", accountCount > 0, task.getCreateTime());
            addProcessStep(steps, "save", "保存任务", "保存后自动排队或按计划时间触发", ObjectUtil.isNotNull(task.getTaskId()), task.getCreateTime());
        }
        addProcessStep(steps, "executions", "生成执行记录", executionCount > 0 ? "已生成 " + executionCount + " 条执行记录" : "等待触发后生成执行记录", executionCount > 0, task.getLastExecutedTime());
        return steps;
    }

    private void addProcessStep(List<TicketSaleTaskProcessStepVo> steps, String stepKey, String title, String description, boolean finished, Date time) {
        TicketSaleTaskProcessStepVo step = new TicketSaleTaskProcessStepVo();
        step.setStepKey(stepKey);
        step.setTitle(title);
        step.setDescription(StringUtils.defaultIfBlank(description, "-"));
        step.setStatus(finished ? "finish" : "wait");
        step.setTime(time);
        steps.add(step);
    }

    private String buildLotteryParseDescription(TicketSaleTaskVo task, Map<String, Object> taskOptions) {
        String title = Convert.toStr(taskOptions.get("eventTitle"));
        int sessionCount = CollUtil.size(task.getSelectedSessions());
        if (StringUtils.isBlank(title) && sessionCount <= 0) {
            return "等待解析活动信息";
        }
        return StringUtils.defaultIfBlank(title, "活动信息已解析") + (sessionCount > 0 ? "，" + sessionCount + " 个场次" : "");
    }

    private void enrichSaleTasks(List<TicketSaleTaskVo> rows, boolean includeDetailFields) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        rows.forEach(row -> normalizeSaleTaskView(row, includeDetailFields));
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketSaleTaskVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        List<Long> taskIds = rows.stream().map(TicketSaleTaskVo::getTaskId).filter(Objects::nonNull).toList();
        Map<Long, Map<String, Integer>> executionSummaryMap = buildSaleTaskExecutionSummaryMap(rows);
        Map<Long, List<TicketSaleTaskAccount>> bindingMap = saleTaskAccountMapper.selectList(new LambdaQueryWrapper<TicketSaleTaskAccount>()
                .in(CollUtil.isNotEmpty(taskIds), TicketSaleTaskAccount::getTaskId, taskIds)
                .orderByAsc(TicketSaleTaskAccount::getBindingId))
            .stream()
            .collect(Collectors.groupingBy(TicketSaleTaskAccount::getTaskId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<TicketSaleTaskScheduleVo>> scheduleMap = saleTaskScheduleMapper.selectVoList(new LambdaQueryWrapper<TicketSaleTaskSchedule>()
                .in(CollUtil.isNotEmpty(taskIds), TicketSaleTaskSchedule::getTaskId, taskIds)
                .orderByAsc(TicketSaleTaskSchedule::getScheduledTime)
                .orderByAsc(TicketSaleTaskSchedule::getScheduleId))
            .stream()
            .collect(Collectors.groupingBy(TicketSaleTaskScheduleVo::getTaskId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, TicketManagedAccount> accountMap = includeDetailFields
            ? loadMap(bindingMap.values().stream()
                .flatMap(Collection::stream)
                .map(TicketSaleTaskAccount::getAccountId)
                .filter(Objects::nonNull)
                .distinct()
                .toList(), accountMapper::selectByIds, TicketManagedAccount::getAccountId)
            : Map.of();
        for (TicketSaleTaskVo row : rows) {
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
            List<TicketSaleTaskAccount> bindings = bindingMap.getOrDefault(row.getTaskId(), List.of());
            List<Long> accountIds = bindings.stream().map(TicketSaleTaskAccount::getAccountId).filter(Objects::nonNull).toList();
            row.setBoundAccountCount(accountIds.size());
            List<TicketSaleTaskScheduleVo> schedules = scheduleMap.getOrDefault(row.getTaskId(), List.of());
            row.setLotteryScheduleCount(schedules.size());
            row.setExecutionSummary(executionSummaryMap.getOrDefault(row.getTaskId(), Map.of("total", 0)));
            if (includeDetailFields) {
                List<String> emails = accountIds.stream()
                    .map(accountMap::get)
                    .filter(Objects::nonNull)
                    .map(TicketManagedAccount::getEmail)
                    .filter(StringUtils::isNotBlank)
                    .toList();
                row.setAccountIds(accountIds);
                row.setAccountEmails(CollUtil.isEmpty(emails) ? null : String.join(" / ", emails));
                row.setLotterySchedules(schedules);
            } else {
                row.setAccountIds(null);
                row.setAccountEmails(null);
                row.setLotterySchedules(schedules.stream().limit(2).toList());
                row.setTaskOptions(null);
                row.setSelectedSessions(null);
            }
        }
    }

    private void enrichOrderExecutions(List<TicketOrderExecutionVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketOrderExecutionVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        Map<Long, TicketManagedAccount> accountMap = loadMap(rows.stream().map(TicketOrderExecutionVo::getAccountId).filter(Objects::nonNull).toList(), accountMapper::selectByIds, TicketManagedAccount::getAccountId);
        Map<Long, TicketSaleTask> taskMap = loadMap(rows.stream().map(TicketOrderExecutionVo::getTaskId).filter(Objects::nonNull).toList(), saleTaskMapper::selectByIds, TicketSaleTask::getTaskId);
        Map<Long, TicketSaleTaskSchedule> scheduleMap = loadMap(rows.stream().map(TicketOrderExecutionVo::getLotteryScheduleId).filter(Objects::nonNull).toList(), saleTaskScheduleMapper::selectByIds, TicketSaleTaskSchedule::getScheduleId);
        for (TicketOrderExecutionVo row : rows) {
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
            TicketManagedAccount account = accountMap.get(row.getAccountId());
            if (account != null) {
                row.setEmail(account.getEmail());
                row.setAccountInfo(account.getAccountInfo());
                row.setReqData(account.getLoginReqData());
            }
            TicketSaleTask task = taskMap.get(row.getTaskId());
            if (task != null) {
                row.setTaskName(task.getTaskName());
                if (StringUtils.isBlank(row.getPurchaseType())) {
                    row.setPurchaseType(TicketOrderFlowSupport.defaultPurchaseType(task.getPurchaseType()));
                }
                if (row.getPurchaseQuantity() == null) {
                    row.setPurchaseQuantity(task.getPurchaseQuantity());
                }
                if (StringUtils.isBlank(row.getConfigSnapshot())) {
                    row.setConfigSnapshot(task.getTaskOptions());
                }
            }
            enrichLotteryExecutionDisplay(row, task == null ? null : task.getTaskOptions(), scheduleMap.get(row.getLotteryScheduleId()), null, null);
        }
        ticketLotteryBatchTaskService.enrichBatchOrderExecutions(rows);
    }

    private void trimOrderExecutionListPayload(List<TicketOrderExecutionVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        for (TicketOrderExecutionVo row : rows) {
            populateOrderExecutionPayloadFlags(row);
            row.setStepTrace(null);
            row.setRawResult(null);
            row.setAccountInfo(null);
            row.setReqData(null);
        }
    }

    private void populateOrderExecutionPayloadFlags(TicketOrderExecutionVo row) {
        if (row == null) {
            return;
        }
        row.setHasStepTrace(StringUtils.isNotBlank(row.getStepTrace()));
        row.setHasRawResult(StringUtils.isNotBlank(row.getRawResult()));
    }

    private boolean matchProcessExecutionStatus(String filterStatus, String executionStatus) {
        String normalizedFilter = StringUtils.defaultIfBlank(filterStatus, "total");
        if ("total".equals(normalizedFilter)) {
            return true;
        }
        String normalizedStatus = StringUtils.defaultIfBlank(executionStatus, "unknown");
        if ("failed".equals(normalizedFilter)) {
            return "failed".equals(normalizedStatus) || "timeout".equals(normalizedStatus);
        }
        return normalizedFilter.equals(normalizedStatus);
    }

    private void enrichProcessOrderExecutions(List<TicketOrderExecutionVo> rows, TicketSaleTaskVo task) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketManagedAccount> accountMap = loadMap(rows.stream().map(TicketOrderExecutionVo::getAccountId).filter(Objects::nonNull).toList(), accountMapper::selectByIds, TicketManagedAccount::getAccountId);
        Map<Long, TicketSaleTaskSchedule> scheduleMap = loadMap(rows.stream().map(TicketOrderExecutionVo::getLotteryScheduleId).filter(Objects::nonNull).toList(), saleTaskScheduleMapper::selectByIds, TicketSaleTaskSchedule::getScheduleId);
        for (TicketOrderExecutionVo row : rows) {
            if (task != null) {
                row.setPlatformName(task.getPlatformName());
                row.setTaskName(task.getTaskName());
                if (StringUtils.isBlank(row.getPurchaseType())) {
                    row.setPurchaseType(TicketOrderFlowSupport.defaultPurchaseType(task.getPurchaseType()));
                }
                if (row.getPurchaseQuantity() == null) {
                    row.setPurchaseQuantity(task.getPurchaseQuantity());
                }
            }
            TicketManagedAccount account = accountMap.get(row.getAccountId());
            if (account != null) {
                row.setEmail(account.getEmail());
            }
            enrichLotteryExecutionDisplay(row, task == null ? null : task.getTaskOptions(), scheduleMap.get(row.getLotteryScheduleId()), null, null);
        }
    }

    private void enrichLotteryExecutionDisplay(TicketOrderExecutionVo row,
                                               String fallbackTaskOptions,
                                               TicketSaleTaskSchedule schedule,
                                               TicketLotteryBatchTaskItem batchItem,
                                               TicketLotteryBatchTaskItemSchedule batchSchedule) {
        if (row == null || !TicketOrderFlowSupport.isLottery(row.getPurchaseType())) {
            return;
        }
        String snapshot = StringUtils.defaultIfBlank(row.getConfigSnapshot(), fallbackTaskOptions);
        Map<String, Object> options = TicketOrderFlowSupport.parseTaskOptions(snapshot);
        row.setEventUrl(Convert.toStr(options.get("eventUrl")));
        row.setTicketEntryUrl(firstLivePocketLotteryTicketsUrl(options));
        row.setEventTitle(Convert.toStr(options.get("eventTitle")));
        if (schedule != null) {
            row.setLotterySessionLabel(StringUtils.defaultIfBlank(schedule.getSessionLabel(), schedule.getSessionId()));
            row.setLotteryScheduledTime(schedule.getScheduledTime());
            return;
        }
        if (batchItem != null && StringUtils.isBlank(row.getEventTitle())) {
            row.setEventTitle(batchItem.getEventTitle());
        }
        if (batchSchedule != null) {
            row.setLotterySessionLabel(StringUtils.defaultIfBlank(batchSchedule.getSessionLabel(), batchSchedule.getSessionId()));
            row.setLotteryScheduledTime(batchSchedule.getScheduledTime());
        }
    }

    private void validateSaleTaskAccounts(TicketSaleTask task, List<Long> accountIds, Long excludeTaskId) {
        Long platformId = task == null ? null : task.getPlatformId();
        String purchaseType = task == null ? null : task.getPurchaseType();
        if (ObjectUtil.isNull(platformId)) {
            throw new ServiceException("请选择目标平台");
        }
        TicketPlatformConfig platform = requirePlatform(platformId);
        boolean isLivePocketFlashSale = isLivePocketFlashSaleTask(platform, purchaseType);
        if (CollUtil.isEmpty(accountIds)) {
            if (TicketOrderFlowSupport.isLottery(purchaseType)) {
                throw new ServiceException("请至少绑定一个已激活账号");
            }
            throw new ServiceException(isLivePocketFlashSale ? "请至少绑定一个可用账号" : "请至少绑定一个已登录账号");
        }
        List<Long> distinctAccountIds = CollUtil.distinct(accountIds);
        List<TicketManagedAccount> accounts = accountMapper.selectByIds(distinctAccountIds);
        if (accounts.size() != distinctAccountIds.size()) {
            throw new ServiceException("绑定账号中包含不存在的账号");
        }
        boolean isLottery = TicketOrderFlowSupport.isLottery(purchaseType);
        boolean hasInvalidAccount = accounts.stream().anyMatch(account -> {
            if (!Objects.equals(account.getPlatformId(), platformId)) {
                return true;
            }
            if (isLottery) {
                return !"activated".equals(account.getAccountStatus());
            }
            if (isLivePocketFlashSale) {
                return !"activated".equals(account.getAccountStatus());
            }
            return !List.of("registered", "activated").contains(account.getAccountStatus())
                || !"logged_in".equals(account.getLoginStatus())
                || StringUtils.isBlank(account.getLoginReqData());
        });
        if (hasInvalidAccount) {
            if (isLottery) {
                throw new ServiceException("抽票只能绑定目标平台下已激活账号");
            }
            if (isLivePocketFlashSale) {
                throw new ServiceException("普通抢票只能绑定目标平台下已激活账号");
            }
            throw new ServiceException("只能绑定目标平台下已登录且带会话上下文的账号");
        }
        if (isLottery) {
            validateLotteryOccupiedAccounts(task, accounts, excludeTaskId);
        }
    }

    private void validateLotteryOccupiedAccounts(TicketSaleTask task, List<TicketManagedAccount> accounts, Long excludeTaskId) {
        if (task == null || CollUtil.isEmpty(accounts)) {
            return;
        }
        Map<Long, TicketLotteryLinkOccupancyService.OccupancyInfo> occupiedMap = ticketLotteryLinkOccupancyService.query(
            accounts.stream().map(TicketManagedAccount::getAccountId).filter(Objects::nonNull).toList(),
            List.of(task.getPlatformId()),
            resolveLotteryEventUrlFromTaskOptions(task.getTaskOptions()),
            excludeTaskId
        );
        if (occupiedMap.isEmpty()) {
            return;
        }
        List<String> conflicts = accounts.stream()
            .map(account -> {
                TicketLotteryLinkOccupancyService.OccupancyInfo info = occupiedMap.get(account.getAccountId());
                if (info == null) {
                    return null;
                }
                String taskLabel = StringUtils.defaultIfBlank(info.taskName(), "ID " + info.taskId());
                return StringUtils.defaultIfBlank(account.getEmail(), "账号ID " + account.getAccountId()) + "（同链接已被任务" + taskLabel + "提交）";
            })
            .filter(StringUtils::isNotBlank)
            .toList();
        if (CollUtil.isNotEmpty(conflicts)) {
            throw new ServiceException("所选账号中包含同链接已提交账号：" + String.join("，", conflicts));
        }
    }

    private void saveSaleTaskAccounts(Long taskId, List<Long> accountIds) {
        saleTaskAccountMapper.deleteByTaskIdsPhysical(List.of(taskId));
        List<Long> distinctAccountIds = CollUtil.distinct(accountIds);
        if (CollUtil.isEmpty(distinctAccountIds)) {
            return;
        }
        List<TicketSaleTaskAccount> bindings = distinctAccountIds.stream().map(accountId -> {
            TicketSaleTaskAccount binding = new TicketSaleTaskAccount();
            binding.setTaskId(taskId);
            binding.setAccountId(accountId);
            return binding;
        }).toList();
        saleTaskAccountMapper.insertBatch(bindings);
    }

    private void saveSaleTaskSchedules(TicketSaleTask task, List<TicketSaleTaskScheduleBo> schedules) {
        cleanupLotteryScheduleQueue(task.getTaskId());
        saleTaskScheduleMapper.deleteByTaskIdsPhysical(List.of(task.getTaskId()));
        if (!TicketOrderFlowSupport.isLottery(task.getPurchaseType())) {
            return;
        }
        if (CollUtil.isEmpty(schedules)) {
            throw new ServiceException("抽票任务请至少配置一个执行时段");
        }
        Map<String, Object> taskOptions = TicketOrderFlowSupport.parseTaskOptions(task.getTaskOptions());
        Date entryStartTime = parseLotteryEntryTime(taskOptions.get("entryStartTime"));
        Date entryEndTime = parseLotteryEntryTime(taskOptions.get("entryEndTime"));
        List<TicketLotteryEventSessionVo> selectedSessions = parseLotterySessionVos(taskOptions.get("selectedSessions"));
        if (CollUtil.isEmpty(selectedSessions)) {
            throw new ServiceException("抽票任务必须选择抽選场次");
        }
        if (selectedSessions.stream().anyMatch(item -> !isLotteryDrawSalesType(item.getSalesType()))) {
            throw new ServiceException("抽票任务只能选择抽選受付，不能选择先着票卡");
        }
        requireSingleLotteryReception(selectedSessions);
        Set<String> selectedSessionIds = selectedSessions.stream()
            .map(TicketLotteryEventSessionVo::getSessionId)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        int totalAccountCount = 0;
        List<TicketSaleTaskSchedule> entities = new ArrayList<>();
        for (TicketSaleTaskScheduleBo item : schedules) {
            if (item == null || item.getScheduledTime() == null) {
                throw new ServiceException("抽票执行时段不能为空");
            }
            if (entryStartTime != null && item.getScheduledTime().before(entryStartTime)) {
                throw new ServiceException("抽票触发时间不能早于受付开始时间");
            }
            if (entryEndTime != null && item.getScheduledTime().after(entryEndTime)) {
                throw new ServiceException("抽票触发时间不能晚于受付结束时间");
            }
            if (StringUtils.isBlank(item.getSessionId())) {
                throw new ServiceException("抽票时段必须选择场次");
            }
            if (CollUtil.isNotEmpty(selectedSessionIds) && !selectedSessionIds.contains(item.getSessionId())) {
                throw new ServiceException("抽票时段选择的场次不在当前活动场次范围内");
            }
            int accountCount = ObjectUtil.defaultIfNull(item.getAccountCount(), 0);
            if (accountCount <= 0) {
                throw new ServiceException("抽票每个时段的账号数量必须大于0");
            }
            totalAccountCount += accountCount;
            TicketSaleTaskSchedule entity = new TicketSaleTaskSchedule();
            entity.setTaskId(task.getTaskId());
            entity.setScheduledTime(item.getScheduledTime());
            entity.setSessionId(item.getSessionId());
            entity.setSessionLabel(StringUtils.defaultIfBlank(item.getSessionLabel(), item.getSessionId()));
            entity.setAccountCount(accountCount);
            entity.setScheduleStatus("pending");
            entity.setResultMessage("等待执行");
            entities.add(entity);
        }
        if (totalAccountCount <= 0) {
            throw new ServiceException("抽票执行账号总数必须大于0");
        }
        saleTaskScheduleMapper.insertBatch(entities);
    }

    private Date parseLotteryEntryTime(Object value) {
        String text = Convert.toStr(value);
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return DateUtil.parse(text);
        } catch (Exception ex) {
            throw new ServiceException("抽票受付时间格式不正确: " + text);
        }
    }

    private List<TicketManagedAccount> loadSaleTaskAccounts(TicketSaleTask task) {
        List<Long> accountIds = saleTaskAccountMapper.selectList(new LambdaQueryWrapper<TicketSaleTaskAccount>()
                .select(TicketSaleTaskAccount::getAccountId)
                .eq(TicketSaleTaskAccount::getTaskId, task.getTaskId())
                .orderByAsc(TicketSaleTaskAccount::getBindingId))
            .stream()
            .map(TicketSaleTaskAccount::getAccountId)
            .filter(Objects::nonNull)
            .toList();
        if (CollUtil.isEmpty(accountIds)) {
            return List.of();
        }
        boolean isLottery = TicketOrderFlowSupport.isLottery(task.getPurchaseType());
        TicketPlatformConfig platform = task.getPlatformId() == null ? null : platformMapper.selectById(task.getPlatformId());
        boolean isLivePocketFlashSale = isLivePocketFlashSaleTask(platform, task.getPurchaseType());
        return accountMapper.selectByIds(accountIds).stream()
            .filter(account -> Objects.equals(account.getPlatformId(), task.getPlatformId()))
            .filter(account -> isLottery || isLivePocketFlashSale ? "activated".equals(account.getAccountStatus()) : List.of("registered", "activated").contains(account.getAccountStatus()))
            .filter(account -> isLottery || isLivePocketFlashSale || "logged_in".equals(account.getLoginStatus()))
            .filter(account -> {
                if (isLottery) {
                    return true;
                }
                if (isLivePocketFlashSale) {
                    return "activated".equals(account.getAccountStatus());
                }
                return StringUtils.isNotBlank(account.getLoginReqData());
            })
            .sorted(isLivePocketFlashSale
                ? Comparator.comparingInt(this::livePocketFlashSaleAccountPriority).thenComparing(TicketManagedAccount::getAccountId)
                : Comparator.comparing(TicketManagedAccount::getAccountId))
            .toList();
    }

    private int livePocketFlashSaleAccountPriority(TicketManagedAccount account) {
        if (account == null) {
            return 99;
        }
        if ("logged_in".equals(account.getLoginStatus()) && StringUtils.isNotBlank(account.getLoginReqData())) {
            return 0;
        }
        if (StringUtils.isNotBlank(account.getLoginReqData())) {
            return 1;
        }
        if (StringUtils.isNotBlank(resolveAccountPassword(account))) {
            return 2;
        }
        return 3;
    }

    private void dispatchSaleTask(TicketSaleTask task, TicketPlatformConfig platform, List<TicketManagedAccount> accounts,
                                  List<TicketOrderExecution> executions, Long userId, String triggerSource, boolean forceImmediate) {
        normalizeSaleTask(task);
        Map<Long, TicketOrderExecution> executionMap = executions.stream()
            .collect(Collectors.toMap(TicketOrderExecution::getAccountId, Function.identity(), (left, right) -> right));
        TicketPlatformAdapter adapter = adapterRegistry.getAdapter(platform.getAdapterType());

        for (TicketManagedAccount account : accounts) {
            TicketOrderExecution execution = executionMap.get(account.getAccountId());
            if (execution == null) {
                continue;
            }
            try {
                TicketOrderFlowDefinition flowDefinition = adapter.buildOrderFlow(platform, task, account);
                TicketOrderDispatchRequest request = new TicketOrderDispatchRequest();
                request.setExecutionId(execution.getExecutionId());
                request.setTaskId(task.getTaskId());
                request.setPlatformId(platform.getPlatformId());
                request.setPlatformCode(platform.getPlatformCode());
                request.setPlatformName(platform.getPlatformName());
                request.setAdapterType(platform.getAdapterType());
                request.setOrderSubmitUrl(platform.getOrderSubmitUrl());
                request.setAccountId(account.getAccountId());
                request.setEmail(account.getEmail());
                request.setAccountInfo(account.getAccountInfo());
                request.setReqData(account.getLoginReqData());
                request.setPurchaseType(task.getPurchaseType());
                request.setPurchaseQuantity(task.getPurchaseQuantity());
                request.setScheduleVersion(defaultScheduleVersion(task.getScheduleVersion()));
                request.setConfigSchemaKey(flowDefinition.getConfigSchemaKey());
                request.setConfigSnapshot(task.getTaskOptions());
                request.setTaskOptions(task.getTaskOptions());
                request.setFlowSteps(flowDefinition.getSteps());
                request.setScheduledTime(forceImmediate ? null : task.getScheduledTime());
                request.setWarmupTime(forceImmediate ? null : task.getWarmupTime());
                log.info("dispatch purchase execution to redis, taskId={}, executionId={}, accountId={}, scheduleVersion={}, scheduledTime={}, warmupTime={}",
                    task.getTaskId(), execution.getExecutionId(), account.getAccountId(), request.getScheduleVersion(), request.getScheduledTime(), request.getWarmupTime());
                ticketOrderExecutorClient.dispatchByRedis(request);
            } catch (Exception ex) {
                log.error("dispatch purchase execution failed, taskId={}, executionId={}, accountId={}",
                    task.getTaskId(), execution.getExecutionId(), account.getAccountId(), ex);
                TicketOrderExecution current = orderExecutionMapper.selectById(execution.getExecutionId());
                if (current != null && "queued".equals(current.getExecutionStatus())) {
                    current.setExecutionStatus("blocked");
                    current.setStepStatus("failed");
                    current.setResultMessage(StringUtils.defaultString(ex.getMessage(), "Go 执行器调度失败"));
                    current.setExecutedAt(new Date());
                    orderExecutionMapper.updateById(current);
                }
                Map<String, Object> failedAuditPayload = new LinkedHashMap<>();
                failedAuditPayload.put("taskId", task.getTaskId());
                failedAuditPayload.put("executionId", execution.getExecutionId());
                failedAuditPayload.put("accountId", account.getAccountId());
                failedAuditPayload.put("message", StringUtils.defaultString(ex.getMessage(), "unknown"));
                recordAudit("saleTask", "dispatch", "orderExecution", String.valueOf(execution.getExecutionId()), "failed", "任务调度失败", failedAuditPayload);
            }
        }
        refreshSaleTaskStatus(task.getTaskId());
        Map<String, Object> dispatchAuditPayload = new LinkedHashMap<>();
        dispatchAuditPayload.put("taskId", task.getTaskId());
        dispatchAuditPayload.put("executionCount", executions.size());
        dispatchAuditPayload.put("operator", userId);
        dispatchAuditPayload.put("triggerSource", triggerSource);
        dispatchAuditPayload.put("forceImmediate", forceImmediate);
        recordAudit("saleTask", "dispatch", "saleTask", String.valueOf(task.getTaskId()), "success", "任务已写入执行队列", dispatchAuditPayload);
    }

    // 后台定时收敛执行状态，避免列表查询线程同步刷新带来明显抖动。
    @Scheduled(initialDelay = 5000L, fixedDelay = 5000L)
    public void refreshSaleTaskExecutionStates() {
        try {
            markTimeoutExecutions();
            refreshActiveSaleTaskStatuses();
        } catch (Exception ex) {
            // 定时刷新只负责兜底汇总，不应影响主业务线程。
        }
    }

    @Override
    public void promoteLotteryExecution(Long executionId) {
        TicketOrderExecution execution = orderExecutionMapper.selectById(executionId);
        TicketSaleTask task = execution == null ? null : saleTaskMapper.selectById(execution.getTaskId());
        TicketPlatformConfig platform = execution == null ? null : platformMapper.selectById(execution.getPlatformId());
        if (execution != null && task != null && platform != null && isHandsFormPlatform(platform)) {
            prepareHandsFormExtensionExecution(executionId);
        } else {
            addLotteryReadyStream(executionId);
        }
    }

    @Override
    public void applyLivePocketRegisterResult(Map<Object, Object> fields) {
        Long batchId = Convert.toLong(fields.get("batchId"), null);
        if (batchId == null) {
            return;
        }
        String eventType = StringUtils.defaultIfBlank(Convert.toStr(fields.get("eventType")), "account_result");
        if ("batch_completed".equals(eventType)) {
            updateRegistrationBatchFromStream(batchId, fields, true);
            return;
        }

        TicketRegistrationBatch batch = registrationBatchMapper.selectById(batchId);
        Long platformId = batch == null ? null : batch.getPlatformId();
        boolean success = Convert.toBool(fields.get("success"), false);
        String message = StringUtils.defaultIfBlank(Convert.toStr(fields.get("message")), success ? "注册成功" : "注册失败");
        Long accountId = Convert.toLong(fields.get("accountId"), null);
        String email = Convert.toStr(fields.get("email"));
        String reqData = Convert.toStr(fields.get("reqData"));
        String accountStatus = Convert.toStr(fields.get("accountStatus"));
        String loginStatus = Convert.toStr(fields.get("loginStatus"));
        TicketManagedAccount account = findAccountForStream(platformId, accountId, email);
        if (account == null && success && platformId != null && StringUtils.isNotBlank(email)) {
            account = new TicketManagedAccount();
            account.setPlatformId(platformId);
            account.setEmail(email);
        }

        if (account != null) {
            if (StringUtils.isNotBlank(Convert.toStr(fields.get("accountInfo")))) {
                account.setAccountInfo(Convert.toStr(fields.get("accountInfo")));
            }
            if (StringUtils.isNotBlank(reqData)) {
                account.setReqData(reqData);
            }
            if (StringUtils.isNotBlank(Convert.toStr(fields.get("loginReqData")))) {
                account.setLoginReqData(Convert.toStr(fields.get("loginReqData")));
            }
            if (success) {
                account.setAccountStatus(StringUtils.defaultIfBlank(accountStatus, "activated"));
                account.setLoginStatus(StringUtils.defaultIfBlank(loginStatus,
                    StringUtils.isNotBlank(account.getLoginReqData()) ? "logged_in" : "offline"));
            } else {
                account.setAccountStatus(StringUtils.defaultIfBlank(account.getAccountStatus(), "pending_register"));
                account.setLoginStatus(StringUtils.defaultIfBlank(loginStatus, StringUtils.defaultIfBlank(account.getLoginStatus(), "offline")));
            }
            account.setLastError(success ? null : message);
            if (success && "logged_in".equals(account.getLoginStatus())) {
                account.setLastLoginTime(new Date());
            }
            saveAccount(account);
            String relationStatus = success
                ? ("logged_in".equals(account.getLoginStatus()) ? "logged_in" : StringUtils.defaultIfBlank(account.getAccountStatus(), "registered"))
                : "register_failed";
            syncRelationAfterAccountResult(account, relationStatus, success ? null : message);
            accountId = account.getAccountId();
            email = account.getEmail();
        }

        upsertRegistrationDetailByAccount(batchId, platformId, accountId, email, success ? "success" : "failed", message);
        updateRegistrationBatchFromStream(batchId, fields, false);
    }

    @Override
    public void applyLivePocketLoginResult(Map<Object, Object> fields) {
        Long batchId = Convert.toLong(fields.get("batchId"), null);
        if (batchId == null) {
            return;
        }
        String eventType = StringUtils.defaultIfBlank(Convert.toStr(fields.get("eventType")), "account_result");
        if ("batch_completed".equals(eventType)) {
            updateLoginBatchFromStream(batchId, fields, true);
            return;
        }

        TicketLoginBatch batch = loginBatchMapper.selectById(batchId);
        Long platformId = batch == null ? null : batch.getPlatformId();
        boolean success = Convert.toBool(fields.get("success"), false);
        String message = StringUtils.defaultIfBlank(Convert.toStr(fields.get("message")), success ? "登录成功" : "登录失败");
        Long accountId = Convert.toLong(fields.get("accountId"), null);
        String email = Convert.toStr(fields.get("email"));
        String loginReqData = Convert.toStr(fields.get("loginReqData"));
        String accountStatus = Convert.toStr(fields.get("accountStatus"));
        String loginStatus = Convert.toStr(fields.get("loginStatus"));
        TicketManagedAccount account = findAccountForStream(platformId, accountId, email);

        if (account != null) {
            if (success) {
                account.setLoginStatus(StringUtils.defaultIfBlank(loginStatus, "logged_in"));
                if (StringUtils.isNotBlank(accountStatus)) {
                    account.setAccountStatus(accountStatus);
                }
            } else {
                account.setLoginStatus(StringUtils.defaultIfBlank(loginStatus, "login_failed"));
            }
            if (StringUtils.isNotBlank(loginReqData)) {
                account.setLoginReqData(loginReqData);
            }
            account.setLastError(success ? null : message);
            if (success) {
                account.setLastLoginTime(new Date());
            }
            saveAccount(account);
            syncRelationAfterAccountResult(account, success ? "logged_in" : "login_failed", success ? null : message);
            accountId = account.getAccountId();
        }

        upsertLoginDetail(batchId, accountId, platformId, success ? "success" : "failed", message, loginReqData);
        updateLoginBatchFromStream(batchId, fields, false);
    }

    private TicketManagedAccount findAccountForStream(Long platformId, Long accountId, String email) {
        if (accountId != null) {
            TicketManagedAccount account = accountMapper.selectById(accountId);
            if (account != null) {
                return account;
            }
        }
        if (platformId == null || StringUtils.isBlank(email)) {
            return null;
        }
        return accountMapper.selectOne(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platformId)
            .eq(TicketManagedAccount::getEmail, email)
            .last("limit 1"), false);
    }

    private void syncRelationAfterAccountResult(TicketManagedAccount account, String status, String error) {
        if (account == null || account.getPhoneId() == null || account.getPlatformId() == null) {
            return;
        }
        TicketPhonePlatformRelation relation = getRelation(account.getPlatformId(), account.getPhoneId());
        if (relation == null) {
            relation = new TicketPhonePlatformRelation();
            relation.setPhoneId(account.getPhoneId());
            relation.setPlatformId(account.getPlatformId());
        }
        relation.setAccountId(account.getAccountId());
        relation.setStatus(status);
        relation.setLastError(error);
        relation.setLastOperateTime(new Date());
        saveRelation(relation);
    }

    private void updateRegistrationBatchFromStream(Long batchId, Map<Object, Object> fields, boolean finished) {
        Integer successCount = Convert.toInt(fields.get("successCount"), null);
        Integer failedCount = Convert.toInt(fields.get("failedCount"), null);
        Integer skippedCount = Convert.toInt(fields.get("skippedCount"), null);
        Integer totalCount = Convert.toInt(fields.get("totalCount"), null);
        String status = finished ? StringUtils.defaultIfBlank(Convert.toStr(fields.get("status")), "completed") : "executing";
        LambdaUpdateWrapper<TicketRegistrationBatch> wrapper = Wrappers.lambdaUpdate(TicketRegistrationBatch.class)
            .eq(TicketRegistrationBatch::getBatchId, batchId)
            .set(TicketRegistrationBatch::getBatchStatus, status)
            .set(TicketRegistrationBatch::getResultSummary, JSONUtil.toJsonStr(List.of(streamSummary(fields))));
        if (successCount != null) {
            wrapper.set(TicketRegistrationBatch::getSuccessCount, successCount);
        }
        if (failedCount != null) {
            wrapper.set(TicketRegistrationBatch::getFailedCount, failedCount);
        }
        if (skippedCount != null) {
            wrapper.set(TicketRegistrationBatch::getSkippedCount, skippedCount);
        }
        if (totalCount != null) {
            wrapper.set(TicketRegistrationBatch::getTotalCount, totalCount);
        }
        if (finished) {
            wrapper.set(TicketRegistrationBatch::getExecutedAt, new Date());
        }
        registrationBatchMapper.update(null, wrapper);
    }

    private void updateLoginBatchFromStream(Long batchId, Map<Object, Object> fields, boolean finished) {
        Integer successCount = Convert.toInt(fields.get("successCount"), null);
        Integer failedCount = Convert.toInt(fields.get("failedCount"), null);
        Integer totalCount = Convert.toInt(fields.get("totalCount"), null);
        String status = finished ? StringUtils.defaultIfBlank(Convert.toStr(fields.get("status")), "completed") : "executing";
        LambdaUpdateWrapper<TicketLoginBatch> wrapper = Wrappers.lambdaUpdate(TicketLoginBatch.class)
            .eq(TicketLoginBatch::getBatchId, batchId)
            .set(TicketLoginBatch::getBatchStatus, status)
            .set(TicketLoginBatch::getResultSummary, JSONUtil.toJsonStr(List.of(streamSummary(fields))));
        if (successCount != null) {
            wrapper.set(TicketLoginBatch::getSuccessCount, successCount);
        }
        if (failedCount != null) {
            wrapper.set(TicketLoginBatch::getFailedCount, failedCount);
        }
        if (totalCount != null) {
            wrapper.set(TicketLoginBatch::getTotalCount, totalCount);
        }
        if (finished) {
            wrapper.set(TicketLoginBatch::getExecutedAt, new Date());
        }
        loginBatchMapper.update(null, wrapper);
    }

    private Map<String, Object> streamSummary(Map<Object, Object> fields) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("eventType", Convert.toStr(fields.get("eventType")));
        summary.put("status", Convert.toStr(fields.get("status")));
        summary.put("success", Convert.toBool(fields.get("success"), false));
        summary.put("message", Convert.toStr(fields.get("message")));
        summary.put("email", Convert.toStr(fields.get("email")));
        summary.put("accountId", Convert.toLong(fields.get("accountId"), null));
        return summary;
    }

    @Override
    public void applyLotteryEventParseResult(Map<Object, Object> fields) {
        Long recordId = Convert.toLong(fields.get("recordId"), null);
        String requestId = Convert.toStr(fields.get("requestId"));
        if (recordId == null || StringUtils.isBlank(requestId)) {
            return;
        }
        TicketLotteryEventParseRecord record = parseRecordMapper.selectById(recordId);
        if (record == null || !Objects.equals(record.getParseRequestId(), requestId)) {
            return;
        }
        String status = StringUtils.defaultIfBlank(Convert.toStr(fields.get("status")), "failed");
        String message = StringUtils.defaultIfBlank(Convert.toStr(fields.get("message")), "活动解析结果已返回");
        if ("running".equals(status)) {
            parseRecordMapper.update(null, Wrappers.lambdaUpdate(TicketLotteryEventParseRecord.class)
                .eq(TicketLotteryEventParseRecord::getRecordId, recordId)
                .eq(TicketLotteryEventParseRecord::getParseRequestId, requestId)
                .set(TicketLotteryEventParseRecord::getParseStatus, "running")
                .set(TicketLotteryEventParseRecord::getParseMessage, message));
            return;
        }
        boolean success = Convert.toBool(fields.get("success"), false) || "completed".equals(status);
        LambdaUpdateWrapper<TicketLotteryEventParseRecord> wrapper = Wrappers.lambdaUpdate(TicketLotteryEventParseRecord.class)
            .eq(TicketLotteryEventParseRecord::getRecordId, recordId)
            .eq(TicketLotteryEventParseRecord::getParseRequestId, requestId)
            .set(TicketLotteryEventParseRecord::getParseStatus, success ? "completed" : "failed")
            .set(TicketLotteryEventParseRecord::getParseMessage, message);
        if (success) {
            JSONObject eventInfo = JSONUtil.parseObj(Convert.toStr(fields.get("eventInfo")));
            String ticketEntryUrl = StringUtils.defaultIfBlank(
                eventInfo.getStr("ticketEntryUrl"),
                isLivePocketLotteryTicketsUrl(record.getEventUrl()) ? record.getEventUrl() : ""
            );
            String resultEventUrl = StringUtils.defaultIfBlank(eventInfo.getStr("eventUrl"), record.getEventUrl());
            if (!Objects.equals(resultEventUrl, record.getEventUrl()) && parseRecordExists(record.getPlatformId(), resultEventUrl, recordId)) {
                log.warn(
                    "lottery event parse result keeps original eventUrl to avoid duplicate record, recordId={}, original={}, result={}",
                    recordId,
                    record.getEventUrl(),
                    resultEventUrl
                );
                resultEventUrl = record.getEventUrl();
            }
            wrapper
                .set(TicketLotteryEventParseRecord::getEventUrl, resultEventUrl)
                .set(TicketLotteryEventParseRecord::getTicketEntryUrl, ticketEntryUrl)
                .set(TicketLotteryEventParseRecord::getEventTitle, eventInfo.getStr("eventTitle"))
                .set(TicketLotteryEventParseRecord::getEntryStartTime, eventInfo.getStr("entryStartTime"))
                .set(TicketLotteryEventParseRecord::getEntryEndTime, eventInfo.getStr("entryEndTime"))
                .set(TicketLotteryEventParseRecord::getSessionsJson, JSONUtil.toJsonStr(eventInfo.get("sessions")))
                .set(TicketLotteryEventParseRecord::getRawSummary, eventInfo.getStr("rawSummary"));
        }
        parseRecordMapper.update(null, wrapper);
        ticketPythonStringRedisTemplate.delete(ticketPythonExecutorProperties.getEventParseJobKeyPrefix() + requestId);
    }

    private boolean parseRecordExists(Long platformId, String eventUrl, Long excludeRecordId) {
        if (platformId == null || StringUtils.isBlank(eventUrl)) {
            return false;
        }
        return parseRecordMapper.exists(Wrappers.lambdaQuery(TicketLotteryEventParseRecord.class)
            .eq(TicketLotteryEventParseRecord::getPlatformId, platformId)
            .eq(TicketLotteryEventParseRecord::getEventUrl, eventUrl)
            .ne(excludeRecordId != null, TicketLotteryEventParseRecord::getRecordId, excludeRecordId));
    }

    private void dispatchLotteryScheduleFromQueue(Long scheduleId) {
        TicketSaleTaskSchedule schedule = saleTaskScheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            removeLotterySchedulePayload(scheduleId);
            log.warn("lottery schedule skipped because record does not exist, scheduleId={}", scheduleId);
            return;
        }
        if (!"pending".equals(schedule.getScheduleStatus())) {
            removeLotterySchedulePayload(scheduleId);
            log.info("lottery schedule skipped because status is {}, scheduleId={}", schedule.getScheduleStatus(), scheduleId);
            return;
        }
        Date now = new Date();
        if (schedule.getScheduledTime() != null && schedule.getScheduledTime().after(now)) {
            enqueueLotterySchedule(schedule.getTaskId(), null, schedule);
            return;
        }

        int claimed = saleTaskScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTaskSchedule.class)
            .eq(TicketSaleTaskSchedule::getScheduleId, scheduleId)
            .eq(TicketSaleTaskSchedule::getScheduleStatus, "pending")
            .set(TicketSaleTaskSchedule::getScheduleStatus, "running")
            .set(TicketSaleTaskSchedule::getDispatchedTime, now)
            .set(TicketSaleTaskSchedule::getResultMessage, "正在执行抽票"));
        if (claimed <= 0) {
            return;
        }

        saleTaskMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTask.class)
            .eq(TicketSaleTask::getTaskId, schedule.getTaskId())
            .set(TicketSaleTask::getTaskStatus, "executing")
            .set(TicketSaleTask::getLastExecutedTime, now));
        TicketSaleTask task = saleTaskMapper.selectById(schedule.getTaskId());
        if (task == null) {
            finishLotterySchedule(scheduleId, "failed", "抽票任务不存在");
            return;
        }
        TicketPlatformConfig platform = platformMapper.selectById(task.getPlatformId());
        if (platform == null) {
            finishLotterySchedule(scheduleId, "failed", "平台不存在");
            return;
        }
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getLotteryScheduleId, scheduleId)
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .orderByAsc(TicketOrderExecution::getExecutionId));
        if (CollUtil.isEmpty(executions)) {
            refreshLotteryScheduleStatus(scheduleId);
            refreshSaleTaskStatus(task.getTaskId(), task);
            return;
        }
        for (TicketOrderExecution execution : executions) {
            TicketManagedAccount account = accountMapper.selectById(execution.getAccountId());
            if (account == null) {
                markLotteryExecutionFailed(execution.getExecutionId(), "抽票账号不存在", null);
                continue;
            }
            if (isHandsFormPlatform(platform)) {
                prepareHandsFormExtensionExecution(execution.getExecutionId());
                continue;
            }
            enqueueLotteryExecution(task, schedule, platform, execution, account);
        }
        log.info("lottery schedule dispatched, scheduleId={}, taskId={}, platformCode={}, executions={}",
            scheduleId, task.getTaskId(), platform.getPlatformCode(), executions.size());
    }

    private void markLotteryExecutionFailed(Long executionId, String message, Exception ex) {
        if (ex != null) {
            log.error("lottery execution failed, executionId={}", executionId, ex);
        }
        Date now = new Date();
        orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getExecutionId, executionId)
            .set(TicketOrderExecution::getExecutionStatus, "failed")
            .set(TicketOrderExecution::getCurrentStep, "LOTTERY_ENTRY")
            .set(TicketOrderExecution::getStepStatus, "failed")
            .set(TicketOrderExecution::getResultMessage, StringUtils.defaultIfBlank(message, "抽票执行失败"))
            .set(TicketOrderExecution::getExecutedAt, now)
            .set(TicketOrderExecution::getHeartbeatAt, now));
    }

    private void enqueueLotteryExecution(TicketSaleTask task, TicketSaleTaskSchedule schedule,
                                         TicketPlatformConfig platform, TicketOrderExecution execution,
                                         TicketManagedAccount account) {
        try {
            writeLotteryExecutionJob(task, schedule, platform, execution, account);
            addLotteryReadyStream(execution.getExecutionId());
            orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
                .eq(TicketOrderExecution::getExecutionId, execution.getExecutionId())
                .eq(TicketOrderExecution::getExecutionStatus, "queued")
                .set(TicketOrderExecution::getResultMessage, "等待 Python 抽票执行器消费")
                .set(TicketOrderExecution::getHeartbeatAt, new Date()));
        } catch (Exception ex) {
            log.error("enqueue lottery execution failed, executionId={}", execution.getExecutionId(), ex);
            markLotteryExecutionFailed(execution.getExecutionId(), "Python 抽票任务入队失败: " + StringUtils.defaultString(ex.getMessage(), "unknown"), ex);
        }
    }

    private void prepareHandsFormExtensionExecution(Long executionId) {
        orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getExecutionId, executionId)
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .set(TicketOrderExecution::getCurrentStep, "LOTTERY_ENTRY")
            .set(TicketOrderExecution::getStepStatus, "pending")
            .set(TicketOrderExecution::getWorkerId, null)
            .set(TicketOrderExecution::getHeartbeatAt, new Date())
            .set(TicketOrderExecution::getResultMessage, HANDS_EXTENSION_WAITING_MESSAGE));
    }

    private void writeLotteryExecutionJob(TicketSaleTask task, TicketSaleTaskSchedule schedule,
                                          TicketPlatformConfig platform, TicketOrderExecution execution,
                                          TicketManagedAccount account) {
        String password = resolveAccountPassword(account);
        String sessionId = schedule == null ? null : schedule.getSessionId();
        TicketLotteryEventSessionVo sessionMeta = resolveExecutionSessionMeta(task, platform, sessionId);
        Date scheduledTime = schedule == null ? task.getScheduledTime() : schedule.getScheduledTime();
        JSONObject taskOptions = JSONUtil.parseObj(StringUtils.defaultIfBlank(task.getTaskOptions(), "{}"));
        boolean isJumpShopFlashSale = isJumpShopFlashSaleTask(platform, task.getPurchaseType());
        if (sessionMeta != null && StringUtils.isNotBlank(sessionMeta.getEventUrl())) {
            taskOptions.set("eventUrl", sessionMeta.getEventUrl());
            if (TicketOrderFlowSupport.isLottery(task.getPurchaseType())) {
                taskOptions.remove("ticketEntryUrl");
                taskOptions.remove("lotteryEntryUrl");
            }
        }
        JSONObject payload = JSONUtil.createObj()
            .set("executionId", execution.getExecutionId())
            .set("taskId", task.getTaskId())
            .set("scheduleId", schedule == null ? null : schedule.getScheduleId())
            .set("platformId", platform.getPlatformId())
            .set("platformCode", platform.getPlatformCode())
            .set("platformName", platform.getPlatformName())
            .set("backendBaseUrl", requirePythonBackendBaseUrl())
            .set("accountId", account.getAccountId())
            .set("email", account.getEmail())
            .set("password", password)
            .set("platformPassword", password)
            .set("sessionId", sessionMeta == null ? sessionId : sessionMeta.getSessionId())
            .set("sessionLabel", sessionMeta == null ? (schedule == null ? null : schedule.getSessionLabel()) : sessionMeta.getSessionLabel())
            .set("accountInfo", account.getAccountInfo())
            .set("loginReqData", account.getLoginReqData())
            .set("purchaseType", task.getPurchaseType())
            .set("quantityMode", taskOptions.get("quantityMode"))
            .set("purchaseQuantity", task.getPurchaseQuantity())
            .set("taskOptions", taskOptions.toString())
            .set("scheduledTime", scheduledTime == null ? null : scheduledTime.getTime())
            .set("queuedAt", System.currentTimeMillis());
        if (isJumpShopFlashSale) {
            payload.set("productUrl", taskOptions.getStr("productUrl"))
                .set("variantId", Convert.toLong(taskOptions.get("variantId"), null))
                .set("productId", Convert.toLong(taskOptions.get("productId"), null))
                .set("sectionId", taskOptions.getStr("sectionId"))
                .set("profileId", Convert.toLong(taskOptions.get("profileId"), null))
                .set("jumpShopProfile", buildJumpShopProfilePayload(Convert.toLong(taskOptions.get("profileId"), null)))
                .set("paymentMode", taskOptions.getStr("paymentMode"))
                .set("configSchemaKey", task.getConfigSchemaKey());
        }
        if (sessionMeta != null) {
            payload.set("receptionId", sessionMeta.getReceptionId())
                .set("ticketId", sessionMeta.getTicketId())
                .set("ticketField", sessionMeta.getTicketField())
                .set("receptionTitle", sessionMeta.getReceptionTitle())
                .set("salesType", sessionMeta.getSalesType())
                .set("maxPurchaseQuantity", sessionMeta.getMaxPurchaseQuantity());
        }
        ticketPythonStringRedisTemplate.opsForValue().set(
            lotteryJobKey(execution.getExecutionId()),
            payload.toString(),
            Duration.ofSeconds(Math.max(ticketPythonExecutorProperties.getJobTtlSeconds(), 60L))
        );
    }

    private void writeFlashSaleExecutionJob(TicketSaleTask task, TicketPlatformConfig platform,
                                            TicketOrderExecution execution, TicketManagedAccount account) {
        String password = resolveAccountPassword(account);
        TicketLotteryEventSessionVo sessionMeta = resolveExecutionSessionMeta(task, platform, null);
        Date scheduledTime = task.getScheduledTime();
        JSONObject taskOptions = JSONUtil.parseObj(StringUtils.defaultIfBlank(task.getTaskOptions(), "{}"));
        if (sessionMeta != null && StringUtils.isNotBlank(sessionMeta.getEventUrl())) {
            taskOptions.set("eventUrl", sessionMeta.getEventUrl());
        }
        JSONObject payload = JSONUtil.createObj()
            .set("executionId", execution.getExecutionId())
            .set("taskId", task.getTaskId())
            .set("scheduleId", null)
            .set("platformId", platform.getPlatformId())
            .set("platformCode", platform.getPlatformCode())
            .set("platformName", platform.getPlatformName())
            .set("backendBaseUrl", requirePythonBackendBaseUrl())
            .set("accountId", account.getAccountId())
            .set("email", account.getEmail())
            .set("password", password)
            .set("platformPassword", password)
            .set("sessionId", sessionMeta == null ? null : sessionMeta.getSessionId())
            .set("sessionLabel", sessionMeta == null ? null : sessionMeta.getSessionLabel())
            .set("accountInfo", account.getAccountInfo())
            .set("loginReqData", account.getLoginReqData())
            .set("purchaseType", task.getPurchaseType())
            .set("quantityMode", taskOptions.get("quantityMode"))
            .set("purchaseQuantity", task.getPurchaseQuantity())
            .set("taskOptions", taskOptions.toString())
            .set("scheduledTime", scheduledTime == null ? null : scheduledTime.getTime())
            .set("warmupTime", task.getWarmupTime() == null ? null : task.getWarmupTime().getTime())
            .set("queuedAt", System.currentTimeMillis());
        if (sessionMeta != null) {
            payload.set("receptionId", sessionMeta.getReceptionId())
                .set("ticketId", sessionMeta.getTicketId())
                .set("ticketField", sessionMeta.getTicketField())
                .set("receptionTitle", sessionMeta.getReceptionTitle())
                .set("salesType", sessionMeta.getSalesType())
                .set("maxPurchaseQuantity", sessionMeta.getMaxPurchaseQuantity());
        }
        ticketPythonStringRedisTemplate.opsForValue().set(
            flashSaleJobKey(execution.getExecutionId()),
            payload.toString(),
            Duration.ofSeconds(Math.max(ticketPythonExecutorProperties.getJobTtlSeconds(), 60L))
        );
    }

    private Map<String, Object> buildJumpShopProfilePayload(Long profileId) {
        if (profileId == null) {
            return Map.of();
        }
        TicketJumpShopProfile profile = jumpShopProfileService.requireById(profileId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profileId", profile.getProfileId());
        payload.put("profileName", profile.getProfileName());
        payload.put("lastName", profile.getLastName());
        payload.put("firstName", profile.getFirstName());
        payload.put("phone", profile.getPhone());
        payload.put("postalCode", profile.getPostalCode());
        payload.put("province", profile.getProvince());
        payload.put("city", profile.getCity());
        payload.put("address1", profile.getAddress1());
        payload.put("address2", profile.getAddress2());
        payload.put("countryCode", profile.getCountryCode());
        payload.put("billingSameAsShipping", profile.getBillingSameAsShipping());
        payload.put("billingLastName", profile.getBillingLastName());
        payload.put("billingFirstName", profile.getBillingFirstName());
        payload.put("billingPhone", profile.getBillingPhone());
        payload.put("billingPostalCode", profile.getBillingPostalCode());
        payload.put("billingProvince", profile.getBillingProvince());
        payload.put("billingCity", profile.getBillingCity());
        payload.put("billingAddress1", profile.getBillingAddress1());
        payload.put("billingAddress2", profile.getBillingAddress2());
        payload.put("billingCountryCode", profile.getBillingCountryCode());
        payload.put("cardHolderName", profile.getCardHolderName());
        payload.put("cardNumber", profile.getCardNumber());
        payload.put("expMonth", profile.getExpMonth());
        payload.put("expYear", profile.getExpYear());
        payload.put("cvv", profile.getCvv());
        payload.put("issueMonth", profile.getIssueMonth());
        payload.put("issueYear", profile.getIssueYear());
        payload.put("issueNumber", profile.getIssueNumber());
        return payload;
    }

    private TicketLotteryEventSessionVo resolveExecutionSessionMeta(TicketSaleTask task, TicketPlatformConfig platform, String sessionId) {
        TicketLotteryEventSessionVo sessionMeta = findSelectedLotterySession(task.getTaskOptions(), sessionId);
        if (sessionMeta == null || StringUtils.isBlank(sessionMeta.getReceptionId()) || sessionMeta.getMaxPurchaseQuantity() == null) {
            TicketLotteryEventSessionVo parsedSessionMeta = findLatestParsedLotterySession(platform.getPlatformId(), task.getTaskOptions(), sessionId);
            if (parsedSessionMeta != null) {
                sessionMeta = mergeLotterySessionMeta(sessionMeta, parsedSessionMeta);
            }
        }
        return sessionMeta;
    }

    private TicketLotteryEventSessionVo findSelectedLotterySession(String taskOptionsText, String sessionId) {
        Map<String, Object> taskOptions = TicketOrderFlowSupport.parseTaskOptions(taskOptionsText);
        List<TicketLotteryEventSessionVo> selectedSessions = parseLotterySessionVos(taskOptions.get("selectedSessions"));
        if (CollUtil.isEmpty(selectedSessions)) {
            return null;
        }
        if (StringUtils.isBlank(sessionId)) {
            return selectedSessions.get(0);
        }
        return selectedSessions.stream().filter(item -> sessionId.equals(item.getSessionId())).findFirst().orElse(selectedSessions.get(0));
    }

    private String requirePythonBackendBaseUrl() {
        if (StringUtils.isBlank(ticketPythonExecutorProperties.getBackendBaseUrl())) {
            throw new ServiceException("未配置 Java 外部账号接口地址");
        }
        return ticketPythonExecutorProperties.getBackendBaseUrl();
    }

    private RecordId addLotteryReadyStream(Long executionId) {
        return addLotteryReadyStream(String.valueOf(executionId));
    }

    private RecordId addLotteryReadyStream(String executionId) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("executionId", String.valueOf(executionId));
        message.put("enqueuedAt", String.valueOf(System.currentTimeMillis()));
        return ticketPythonStringRedisTemplate.opsForStream().add(
            StreamRecords.mapBacked(message).withStreamKey(ticketPythonExecutorProperties.getReadyQueueKey())
        );
    }

    private RecordId addFlashSaleReadyStream(Long executionId, String phase) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("executionId", String.valueOf(executionId));
        message.put("phase", StringUtils.defaultIfBlank(phase, "run"));
        message.put("enqueuedAt", String.valueOf(System.currentTimeMillis()));
        return ticketPythonStringRedisTemplate.opsForStream().add(
            StreamRecords.mapBacked(message).withStreamKey(ticketPythonExecutorProperties.getFlashSaleReadyQueueKey())
        );
    }

    @Override
    public void applyLotteryResult(Map<Object, Object> fields) {
        Long executionId = Convert.toLong(fields.get("executionId"), null);
        if (executionId == null) {
            return;
        }
        TicketOrderExecution execution = orderExecutionMapper.selectById(executionId);
        if (execution == null) {
            return;
        }
        boolean isLottery = TicketOrderFlowSupport.isLottery(execution.getPurchaseType());
        String currentStep = isLottery ? "LOTTERY_ENTRY" : "PURCHASE_SUBMIT";
        String pendingPaymentStatus = TicketOrderFlowSupport.initialPaymentStatus(
            execution.getPurchaseType(),
            TicketOrderFlowSupport.parseTaskOptions(execution.getConfigSnapshot())
        );
        String status = StringUtils.defaultIfBlank(Convert.toStr(fields.get("status")), "failed");
        String defaultMessage = isLottery ? "抽票执行结果已返回" : "抢票执行结果已返回";
        String message = StringUtils.defaultIfBlank(Convert.toStr(fields.get("message")), defaultMessage);
        Date now = new Date();
        if ("running".equals(status)) {
            orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
                .eq(TicketOrderExecution::getExecutionId, executionId)
                .in(TicketOrderExecution::getExecutionStatus, List.of("queued", "running"))
                .set(TicketOrderExecution::getExecutionStatus, "running")
                .set(TicketOrderExecution::getCurrentStep, currentStep)
                .set(TicketOrderExecution::getStepStatus, "running")
                .set(TicketOrderExecution::getStartedAt, now)
                .set(TicketOrderExecution::getHeartbeatAt, now)
                .set(TicketOrderExecution::getResultMessage, message));
            if (execution != null && execution.getLotteryScheduleId() != null && execution.getBatchTaskId() == null) {
                saleTaskScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTaskSchedule.class)
                    .eq(TicketSaleTaskSchedule::getScheduleId, execution.getLotteryScheduleId())
                    .in(TicketSaleTaskSchedule::getScheduleStatus, List.of("pending", "running"))
                    .set(TicketSaleTaskSchedule::getScheduleStatus, "running")
                    .set(TicketSaleTaskSchedule::getDispatchedTime, now)
                    .set(TicketSaleTaskSchedule::getResultMessage, "抽票执行中"));
            }
            if (execution != null && execution.getTaskId() != null) {
                saleTaskMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTask.class)
                    .eq(TicketSaleTask::getTaskId, execution.getTaskId())
                    .set(TicketSaleTask::getTaskStatus, "executing")
                    .set(TicketSaleTask::getLastExecutedTime, now));
            }
            if (execution != null && execution.getBatchTaskId() != null) {
                ticketLotteryBatchTaskService.markExecutionRunning(execution, now, "抽票执行中");
            }
            return;
        }

        boolean success = Convert.toBool(fields.get("success"), false)
            || List.of("submitted", "paid", "completed").contains(status);
        String explicitExecutionStatus = Convert.toStr(fields.get("executionStatus"));
        String finalStatus = success
            ? StringUtils.defaultIfBlank(explicitExecutionStatus, status)
            : StringUtils.defaultIfBlank(explicitExecutionStatus, "failed");
        String orderId = Convert.toStr(fields.get("orderId"));
        String loginReqData = Convert.toStr(fields.get("loginReqData"));
        if (StringUtils.isBlank(orderId)) {
            orderId = Convert.toStr(fields.get("orderNo"));
        }
        String paymentStatus = success
            ? StringUtils.defaultIfBlank(Convert.toStr(fields.get("paymentStatus")), pendingPaymentStatus)
            : StringUtils.defaultIfBlank(Convert.toStr(fields.get("paymentStatus")), "not_required");
        JSONObject raw = JSONUtil.createObj();
        fields.forEach((key, value) -> raw.set(Convert.toStr(key), value));
        JSONObject trace = JSONUtil.createObj()
            .set("step", currentStep)
            .set("status", success ? "success" : "failed")
            .set("message", message)
            .set("time", DateUtil.formatDateTime(now))
            .set("rawStatus", status);
        List<String> updatableStatuses = success
            ? List.of("queued", "running", "timeout")
            : List.of("queued", "running", "timeout");
        orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getExecutionId, executionId)
            .in(TicketOrderExecution::getExecutionStatus, updatableStatuses)
            .set(TicketOrderExecution::getExecutionStatus, finalStatus)
            .set(StringUtils.isNotBlank(orderId), TicketOrderExecution::getOrderNo, orderId)
            .set(TicketOrderExecution::getCurrentStep, currentStep)
            .set(TicketOrderExecution::getStepStatus, success ? "success" : "failed")
            .set(TicketOrderExecution::getPaymentStatus, paymentStatus)
            .set(TicketOrderExecution::getResultMessage, message)
            .set(TicketOrderExecution::getRawResult, raw.toString())
            .set(TicketOrderExecution::getStepTrace, JSONUtil.toJsonStr(List.of(trace)))
            .set(TicketOrderExecution::getExecutedAt, now)
            .set(TicketOrderExecution::getHeartbeatAt, now));
        ticketPythonStringRedisTemplate.delete(lotteryJobKey(executionId));
        if (success && execution != null && execution.getAccountId() != null && StringUtils.isNotBlank(loginReqData)) {
            accountMapper.update(null, Wrappers.lambdaUpdate(TicketManagedAccount.class)
                .eq(TicketManagedAccount::getAccountId, execution.getAccountId())
                .set(TicketManagedAccount::getLoginReqData, loginReqData)
                .set(TicketManagedAccount::getLoginStatus, "logged_in")
                .set(TicketManagedAccount::getLastLoginTime, now)
                .set(TicketManagedAccount::getLastError, null));
        }
        if (execution != null && execution.getLotteryScheduleId() != null) {
            if (execution.getBatchTaskId() == null) {
                refreshLotteryScheduleStatus(execution.getLotteryScheduleId());
            }
        }
        if (execution != null && execution.getTaskId() != null) {
            refreshSaleTaskStatus(execution.getTaskId());
        }
        if (execution != null && execution.getBatchTaskId() != null) {
            ticketLotteryBatchTaskService.refreshAfterExecution(execution);
        }
    }

    private void finishLotterySchedule(Long scheduleId, String status, String message) {
        saleTaskScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTaskSchedule.class)
            .eq(TicketSaleTaskSchedule::getScheduleId, scheduleId)
            .set(TicketSaleTaskSchedule::getScheduleStatus, status)
            .set(TicketSaleTaskSchedule::getFinishedTime, new Date())
            .set(TicketSaleTaskSchedule::getResultMessage, message));
    }

    private void refreshLotteryScheduleStatus(Long scheduleId) {
        List<TicketOrderExecution> rawExecutions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getLotteryScheduleId, scheduleId));
        if (CollUtil.isEmpty(rawExecutions)) {
            finishLotterySchedule(scheduleId, "failed", "没有抽票执行记录");
            return;
        }
        List<TicketOrderExecution> executions = effectiveSaleTaskExecutions("lottery", rawExecutions);
        boolean hasRunning = executions.stream().anyMatch(item -> List.of("queued", "running").contains(item.getExecutionStatus()));
        if (hasRunning) {
            saleTaskScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTaskSchedule.class)
                .eq(TicketSaleTaskSchedule::getScheduleId, scheduleId)
                .set(TicketSaleTaskSchedule::getScheduleStatus, "running")
                .set(TicketSaleTaskSchedule::getResultMessage, "抽票执行中"));
            return;
        }
        long successCount = executions.stream().filter(item -> List.of("submitted", "pending_payment", "paid").contains(item.getExecutionStatus())).count();
        String status = successCount == executions.size() ? "completed" : successCount == 0 ? "failed" : "partial";
        String message = "抽票完成：" + successCount + "/" + executions.size();
        finishLotterySchedule(scheduleId, status, message);
    }

    private void refreshActiveSaleTaskStatuses() {
        List<TicketSaleTask> tasks = saleTaskMapper.selectList(new LambdaQueryWrapper<TicketSaleTask>()
            .in(TicketSaleTask::getTaskStatus, List.of("draft", "executing", "partial", "pending_payment", "paid")));
        for (TicketSaleTask task : tasks) {
            refreshSaleTaskStatus(task.getTaskId(), task);
        }
        ticketLotteryBatchTaskService.refreshActiveTaskStatuses();
    }

    private void markTimeoutExecutions() {
        long heartbeatTimeoutSeconds = Math.max(ticketOrderExecutorProperties.getHeartbeatTimeoutSeconds(), 5L);
        Date cutoff = new Date(System.currentTimeMillis() - heartbeatTimeoutSeconds * 1000L);
        List<TicketOrderExecution> staleExecutions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getExecutionStatus, "running")
            .and(wrapper -> wrapper.isNull(TicketOrderExecution::getPurchaseType)
                .or()
                .ne(TicketOrderExecution::getPurchaseType, "lottery"))
            .and(wrapper -> wrapper.lt(TicketOrderExecution::getHeartbeatAt, cutoff)
                .or()
                .isNull(TicketOrderExecution::getHeartbeatAt)
                .lt(TicketOrderExecution::getStartedAt, cutoff))
            .orderByAsc(TicketOrderExecution::getExecutionId));
        for (TicketOrderExecution execution : staleExecutions) {
            LambdaUpdateWrapper<TicketOrderExecution> updateWrapper = Wrappers.lambdaUpdate();
            updateWrapper.eq(TicketOrderExecution::getExecutionId, execution.getExecutionId())
                .eq(TicketOrderExecution::getExecutionStatus, "running")
                .set(TicketOrderExecution::getExecutionStatus, "timeout")
                .set(TicketOrderExecution::getStepStatus, "timeout")
                .set(TicketOrderExecution::getResultMessage, "执行心跳超时")
                .set(TicketOrderExecution::getExecutedAt, new Date());
            orderExecutionMapper.update(null, updateWrapper);
        }
    }

    private void normalizeSaleTask(TicketSaleTask task) {
        if (task == null) {
            return;
        }
        if (StringUtils.isBlank(task.getTaskStatus())) {
            task.setTaskStatus("draft");
        }
        if (task.getScheduleVersion() == null || task.getScheduleVersion() <= 0) {
            task.setScheduleVersion(1L);
        }
        task.setPurchaseType(TicketOrderFlowSupport.defaultPurchaseType(task.getPurchaseType()));
        applyDefaultWarmupTime(task);
        if (task.getPurchaseQuantity() == null || task.getPurchaseQuantity() <= 0) {
            task.setPurchaseQuantity(1);
        }
        if (StringUtils.isBlank(task.getTaskOptions())) {
            task.setTaskOptions("{}");
        } else if (!JSONUtil.isTypeJSON(task.getTaskOptions())) {
            throw new ServiceException("平台扩展参数必须是合法 JSON");
        }
    }

    private void applyDefaultWarmupTime(TicketSaleTask task) {
        if (task.getScheduledTime() == null) {
            task.setWarmupTime(null);
            return;
        }
        long warmupAt = Math.max(0L, task.getScheduledTime().getTime() - DEFAULT_WARMUP_LEAD_MILLIS);
        task.setWarmupTime(new Date(warmupAt));
    }

    private void normalizePlatformTaskOptions(TicketPlatformConfig platform, TicketSaleTask task) {
        if (platform == null || task == null) {
            return;
        }
        TicketPurchaseTemplateVo template = adapterRegistry
            .getAdapter(platform.getAdapterType())
            .getPurchaseTemplate(platform, task.getPurchaseType());
        Map<String, Object> mergedOptions = new LinkedHashMap<>(ObjectUtil.defaultIfNull(template.getConfigTemplate(), Map.of()));
        mergedOptions.putAll(TicketOrderFlowSupport.parseTaskOptions(task.getTaskOptions()));
        if (isLivePocketFlashSaleTask(platform, task.getPurchaseType())) {
            List<TicketLotteryEventSessionVo> selectedSessions = parseLotterySessionVos(mergedOptions.get("selectedSessions"));
            String configuredEventUrl = StringUtils.defaultIfBlank(
                Convert.toStr(mergedOptions.get("eventUrl")),
                StringUtils.defaultIfBlank(
                    Convert.toStr(mergedOptions.get("lotteryEventUrl")),
                    Convert.toStr(mergedOptions.get("lotteryEntryUrl"))
                )
            );
            String ticketEntryUrl = firstLivePocketLotteryTicketsUrl(mergedOptions, configuredEventUrl);
            int purchaseQuantity = Math.max(ObjectUtil.defaultIfNull(task.getPurchaseQuantity(), 1), 1);
            task.setPurchaseQuantity(purchaseQuantity);
            if (CollUtil.isEmpty(selectedSessions)) {
                String eventUrl = normalizeLotteryEventUrl(StringUtils.defaultIfBlank(configuredEventUrl, ticketEntryUrl));
                if (StringUtils.isBlank(eventUrl)) {
                    throw new ServiceException("普通抢票请填写 tickets 链接或 LivePocket 活动页链接");
                }
                mergedOptions.put("eventUrl", eventUrl);
                if (StringUtils.isNotBlank(ticketEntryUrl)) {
                    applyLivePocketLotteryTicketsUrl(mergedOptions, ticketEntryUrl);
                } else {
                    applyLivePocketLotteryTicketsUrl(mergedOptions, "");
                }
            } else {
                if (selectedSessions.size() != 1) {
                    throw new ServiceException("普通抢票任务只能选择一个票种");
                }
                TicketLotteryEventSessionVo selectedSession = selectedSessions.get(0);
                if (isLotteryDrawSalesType(selectedSession.getSalesType())) {
                    throw new ServiceException("普通抢票只能选择非抽選票卡");
                }
                String eventUrl = StringUtils.defaultIfBlank(configuredEventUrl, selectedSession.getEventUrl());
                ticketEntryUrl = firstLivePocketLotteryTicketsUrl(mergedOptions, eventUrl, selectedSession.getEventUrl());
                if (StringUtils.isBlank(eventUrl)) {
                    throw new ServiceException("普通抢票请先解析活动并选择一个票种");
                }
                eventUrl = normalizeLotteryEventUrl(eventUrl);
                mergedOptions.put("eventUrl", eventUrl);
                String receptionId = StringUtils.defaultIfBlank(Convert.toStr(mergedOptions.get("receptionId")), selectedSession.getReceptionId());
                if (StringUtils.isNotBlank(receptionId)) {
                    mergedOptions.put("receptionId", receptionId);
                }
                if (StringUtils.isBlank(ticketEntryUrl) && StringUtils.isNotBlank(receptionId) && !receptionId.startsWith("group:")) {
                    ticketEntryUrl = buildLivePocketTicketsUrl(eventUrl, receptionId);
                }
                if (StringUtils.isNotBlank(ticketEntryUrl)) {
                    applyLivePocketLotteryTicketsUrl(mergedOptions, ticketEntryUrl);
                }
            }
            mergedOptions.put("quantityMode", "auto_max");
            mergedOptions.put("purchaseQuantity", purchaseQuantity);
            mergedOptions.put("ticketQuantity", purchaseQuantity);
            mergedOptions.put("entryQuantity", purchaseQuantity);
            mergedOptions.put("paymentMode", "cod_store");
            mergedOptions.put("paymentProvider", "lawson");
            mergedOptions.put("paymentMethod", "cvs");
            mergedOptions.put("sbpsWebCvsType", "002");
        }
        if (isJumpShopFlashSaleTask(platform, task.getPurchaseType())) {
            String productUrl = normalizeJumpShopProductUrl(Convert.toStr(mergedOptions.get("productUrl")));
            if (StringUtils.isBlank(productUrl)) {
                throw new ServiceException("Jump Shop 抢购任务请先解析商品链接");
            }
            Long variantId = requirePositiveLongOption(mergedOptions, "variantId", "Jump Shop 商品缺少 variantId，请重新解析");
            Long productId = requirePositiveLongOption(mergedOptions, "productId", "Jump Shop 商品缺少 productId，请重新解析");
            Long profileId = requirePositiveLongOption(mergedOptions, "profileId", "请选择 Jump Shop 结算资料");
            String sectionId = StringUtils.defaultIfBlank(Convert.toStr(mergedOptions.get("sectionId")), "");
            if (StringUtils.isBlank(sectionId)) {
                throw new ServiceException("Jump Shop 商品缺少 sectionId，请重新解析");
            }
            int quantity = Convert.toInt(mergedOptions.get("quantity"), ObjectUtil.defaultIfNull(task.getPurchaseQuantity(), 10));
            if (quantity <= 0 || quantity > 10) {
                throw new ServiceException("Jump Shop 购买数量必须在 1-10 之间");
            }
            mergedOptions.put("productUrl", productUrl);
            mergedOptions.put("variantId", variantId);
            mergedOptions.put("productId", productId);
            mergedOptions.put("sectionId", sectionId);
            mergedOptions.put("profileId", profileId);
            mergedOptions.put("quantity", quantity);
            mergedOptions.put("purchaseQuantity", quantity);
            mergedOptions.put("purchaseMode", "cart_checkout");
            mergedOptions.put("paymentMode", "credit_card");
            mergedOptions.put("configSchemaKey", JumpShopOnlineAdapter.CONFIG_SCHEMA_KEY);
            task.setPurchaseQuantity(quantity);
        }
        if (TicketOrderFlowSupport.isLottery(task.getPurchaseType())) {
            String eventUrl = ObjectUtil.defaultIfNull(Convert.toStr(mergedOptions.get("eventUrl")), "").trim();
            if (StringUtils.isBlank(eventUrl)) {
                eventUrl = ObjectUtil.defaultIfNull(Convert.toStr(mergedOptions.get("lotteryEntryUrl")), "").trim();
            }
            if (StringUtils.isBlank(eventUrl)) {
                eventUrl = firstLivePocketLotteryTicketsUrl(mergedOptions);
            }
            if (StringUtils.isBlank(eventUrl)) {
                throw new ServiceException("抽票任务必须先解析活动链接");
            }
            mergedOptions.put("eventUrl", normalizeLotteryEventUrl(eventUrl));
            if (isLivePocketPlatform(platform)) {
                applyLivePocketLotteryTicketsUrl(mergedOptions, firstLivePocketLotteryTicketsUrl(mergedOptions));
            }
        }
        if (ObjectUtil.isNull(mergedOptions.get("ticketQuantity")) || Convert.toInt(mergedOptions.get("ticketQuantity"), 0) <= 0) {
            mergedOptions.put("ticketQuantity", ObjectUtil.defaultIfNull(task.getPurchaseQuantity(), 1));
        }
        task.setConfigSchemaKey(template.getConfigSchemaKey());
        task.setTaskOptions(JSONUtil.toJsonStr(mergedOptions));
    }

    private void applyLotteryEventSnapshot(TicketPlatformConfig platform, TicketSaleTaskBo bo, TicketSaleTask task) {
        if (bo == null || task == null || !TicketOrderFlowSupport.isLottery(task.getPurchaseType())) {
            return;
        }
        Map<String, Object> options = new LinkedHashMap<>(TicketOrderFlowSupport.parseTaskOptions(task.getTaskOptions()));
        String ticketsUrl = firstLivePocketLotteryTicketsUrl(options);
        if (StringUtils.isNotBlank(bo.getLotteryEventUrl())) {
            options.put("eventUrl", normalizeLotteryEventUrl(bo.getLotteryEventUrl().trim()));
        }
        if (isLivePocketPlatform(platform)) {
            applyLivePocketLotteryTicketsUrl(options, ticketsUrl);
        }
        if (CollUtil.isNotEmpty(bo.getSelectedSessions())) {
            List<TicketLotteryEventSessionBo> selectedSessions = bo.getSelectedSessions().stream()
                .filter(item -> item != null && StringUtils.isNotBlank(item.getSessionId()))
                .toList();
            boolean hasNonLotterySession = selectedSessions.stream().anyMatch(item -> !isLotteryDrawSalesType(item.getSalesType()));
            if (hasNonLotterySession) {
                throw new ServiceException("抽票任务只能选择抽選受付，不能选择先着票卡");
            }
            String selectedReceptionId = requireSingleLotteryReceptionBo(selectedSessions);
            List<Map<String, Object>> sessions = selectedSessions.stream()
                .map(item -> {
                    Map<String, Object> session = new LinkedHashMap<>();
                    session.put("sessionId", item.getSessionId());
                    session.put("sessionLabel", StringUtils.defaultIfBlank(item.getSessionLabel(), item.getSessionId()));
                    putIfNotBlank(session, "eventUrl", item.getEventUrl());
                    putIfNotBlank(session, "receptionId", item.getReceptionId());
                    putIfNotBlank(session, "ticketId", item.getTicketId());
                    putIfNotBlank(session, "ticketField", item.getTicketField());
                    putIfNotBlank(session, "receptionTitle", item.getReceptionTitle());
                    putIfNotBlank(session, "salesType", item.getSalesType());
                    putIfNotBlank(session, "notes", item.getNotes());
                    if (item.getMaxPurchaseQuantity() != null && item.getMaxPurchaseQuantity() > 0) {
                        session.put("maxPurchaseQuantity", item.getMaxPurchaseQuantity());
                    }
                    return session;
                })
                .toList();
            options.put("receptionId", selectedReceptionId);
            options.put("receptionTitle", selectedSessions.get(0).getReceptionTitle());
            options.put("selectedSessions", sessions);
        }
        task.setTaskOptions(JSONUtil.toJsonStr(options));
    }

    private void applyLivePocketFlashSaleSnapshot(TicketPlatformConfig platform, TicketSaleTaskBo bo, TicketSaleTask task) {
        if (bo == null || task == null || !isLivePocketFlashSaleTask(platform, task.getPurchaseType())) {
            return;
        }
        Map<String, Object> options = new LinkedHashMap<>(TicketOrderFlowSupport.parseTaskOptions(task.getTaskOptions()));
        String eventUrl = StringUtils.defaultIfBlank(
            StringUtils.trim(bo.getLotteryEventUrl()),
            StringUtils.defaultIfBlank(
                StringUtils.trim(Convert.toStr(options.get("eventUrl"))),
                StringUtils.defaultIfBlank(
                    StringUtils.trim(Convert.toStr(options.get("lotteryEventUrl"))),
                    StringUtils.trim(Convert.toStr(options.get("lotteryEntryUrl")))
                )
            )
        );
        String ticketsUrl = firstLivePocketLotteryTicketsUrl(options, bo.getLotteryEventUrl(), eventUrl);
        List<TicketLotteryEventSessionBo> selectedSessions = CollUtil.emptyIfNull(bo.getSelectedSessions()).stream()
            .filter(item -> item != null && StringUtils.isNotBlank(item.getSessionId()))
            .toList();
        if (CollUtil.isEmpty(selectedSessions)) {
            String normalizedEventUrl = normalizeLotteryEventUrl(StringUtils.defaultIfBlank(eventUrl, ticketsUrl));
            if (StringUtils.isBlank(normalizedEventUrl)) {
                throw new ServiceException("普通抢票请填写 tickets 链接或 LivePocket 活动页链接");
            }
            int purchaseQuantity = Math.max(ObjectUtil.defaultIfNull(task.getPurchaseQuantity(), ObjectUtil.defaultIfNull(bo.getPurchaseQuantity(), 1)), 1);
            task.setPurchaseQuantity(purchaseQuantity);
            options.put("eventUrl", normalizedEventUrl);
            if (StringUtils.isNotBlank(ticketsUrl)) {
                applyLivePocketLotteryTicketsUrl(options, ticketsUrl);
            } else {
                applyLivePocketLotteryTicketsUrl(options, "");
            }
            options.put("quantityMode", "auto_max");
            options.put("purchaseQuantity", purchaseQuantity);
            options.put("ticketQuantity", purchaseQuantity);
            options.put("entryQuantity", purchaseQuantity);
            options.put("paymentMode", "cod_store");
            options.put("paymentProvider", "lawson");
            options.put("paymentMethod", "cvs");
            options.put("sbpsWebCvsType", "002");
            options.put("selectedSessions", List.of());
            task.setTaskOptions(JSONUtil.toJsonStr(options));
            return;
        }
        if (selectedSessions.size() != 1) {
            throw new ServiceException("普通抢票任务只能选择一个票种");
        }
        TicketLotteryEventSessionBo selectedSession = selectedSessions.get(0);
        if (isLotteryDrawSalesType(selectedSession.getSalesType())) {
            throw new ServiceException("普通抢票只能选择非抽選票卡");
        }
        int maxPurchaseQuantity = ObjectUtil.defaultIfNull(selectedSession.getMaxPurchaseQuantity(), 0);
        int purchaseQuantity = Math.max(
            ObjectUtil.defaultIfNull(task.getPurchaseQuantity(), ObjectUtil.defaultIfNull(bo.getPurchaseQuantity(), 1)),
            1
        );
        task.setPurchaseQuantity(purchaseQuantity);
        ticketsUrl = firstLivePocketLotteryTicketsUrl(options, bo.getLotteryEventUrl(), eventUrl, selectedSession.getEventUrl());
        eventUrl = normalizeLotteryEventUrl(StringUtils.defaultIfBlank(selectedSession.getEventUrl(), eventUrl));
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("sessionId", selectedSession.getSessionId());
        session.put("sessionLabel", StringUtils.defaultIfBlank(selectedSession.getSessionLabel(), selectedSession.getSessionId()));
        putIfNotBlank(session, "eventUrl", eventUrl);
        putIfNotBlank(session, "receptionId", selectedSession.getReceptionId());
        putIfNotBlank(session, "ticketId", selectedSession.getTicketId());
        putIfNotBlank(session, "ticketField", selectedSession.getTicketField());
        putIfNotBlank(session, "receptionTitle", selectedSession.getReceptionTitle());
        putIfNotBlank(session, "salesType", selectedSession.getSalesType());
        putIfNotBlank(session, "notes", selectedSession.getNotes());
        if (maxPurchaseQuantity > 0) {
            session.put("maxPurchaseQuantity", maxPurchaseQuantity);
        }
        if (StringUtils.isBlank(ticketsUrl) && StringUtils.isNotBlank(selectedSession.getReceptionId()) && !selectedSession.getReceptionId().startsWith("group:")) {
            ticketsUrl = buildLivePocketTicketsUrl(eventUrl, selectedSession.getReceptionId());
        }
        options.put("eventUrl", eventUrl);
        if (StringUtils.isNotBlank(ticketsUrl)) {
            applyLivePocketLotteryTicketsUrl(options, ticketsUrl);
        }
        options.put("quantityMode", "auto_max");
        options.put("purchaseQuantity", purchaseQuantity);
        options.put("ticketQuantity", purchaseQuantity);
        options.put("entryQuantity", purchaseQuantity);
        options.put("paymentMode", "cod_store");
        options.put("paymentProvider", "lawson");
        options.put("paymentMethod", "cvs");
        options.put("sbpsWebCvsType", "002");
        options.put("receptionId", selectedSession.getReceptionId());
        options.put("receptionTitle", selectedSession.getReceptionTitle());
        options.put("ticketId", selectedSession.getTicketId());
        options.put("ticketField", selectedSession.getTicketField());
        options.put("salesType", selectedSession.getSalesType());
        options.put("sessionId", selectedSession.getSessionId());
        options.put("sessionLabel", StringUtils.defaultIfBlank(selectedSession.getSessionLabel(), selectedSession.getSessionId()));
        if (maxPurchaseQuantity > 0) {
            options.put("maxPurchaseQuantity", maxPurchaseQuantity);
        } else {
            options.remove("maxPurchaseQuantity");
        }
        options.put("selectedSessions", List.of(session));
        task.setTaskOptions(JSONUtil.toJsonStr(options));
    }

    private void applyJumpShopFlashSaleSnapshot(TicketPlatformConfig platform, TicketSaleTaskBo bo, TicketSaleTask task) {
        if (bo == null || task == null || !isJumpShopFlashSaleTask(platform, task.getPurchaseType())) {
            return;
        }
        Map<String, Object> options = new LinkedHashMap<>(TicketOrderFlowSupport.parseTaskOptions(task.getTaskOptions()));
        options.putAll(TicketOrderFlowSupport.parseTaskOptions(bo.getTaskOptions()));
        String productUrl = normalizeJumpShopProductUrl(StringUtils.defaultIfBlank(bo.getLotteryEventUrl(), Convert.toStr(options.get("productUrl"))));
        if (StringUtils.isBlank(productUrl)) {
            throw new ServiceException("Jump Shop 抢购任务请先解析商品链接");
        }
        options.put("productUrl", productUrl);
        copyLongOption(options, "variantId");
        copyLongOption(options, "productId");
        copyLongOption(options, "profileId");
        copyStringOption(options, "sectionId");
        copyStringOption(options, "productTitle");
        copyStringOption(options, "imageUrl");
        copyStringOption(options, "currency");
        if (options.containsKey("available")) {
            options.put("available", Convert.toBool(options.get("available"), true));
        }
        int quantity = ObjectUtil.defaultIfNull(bo.getPurchaseQuantity(), Convert.toInt(options.get("quantity"), 10));
        if (quantity <= 0) {
            quantity = 10;
        }
        task.setPurchaseQuantity(quantity);
        options.put("quantity", quantity);
        options.put("purchaseQuantity", quantity);
        options.put("purchaseMode", "cart_checkout");
        options.put("paymentMode", "credit_card");
        options.put("configSchemaKey", JumpShopOnlineAdapter.CONFIG_SCHEMA_KEY);
        task.setTaskOptions(JSONUtil.toJsonStr(options));
    }

    private void copyLongOption(Map<String, Object> options, String key) {
        Long value = Convert.toLong(options.get(key), null);
        if (value != null) {
            options.put(key, value);
        }
    }

    private void copyStringOption(Map<String, Object> options, String key) {
        String value = Convert.toStr(options.get(key));
        if (StringUtils.isNotBlank(value)) {
            options.put(key, value.trim());
        }
    }

    private Long requirePositiveLongOption(Map<String, Object> options, String key, String message) {
        Long value = Convert.toLong(options.get(key), null);
        if (value == null || value <= 0L) {
            throw new ServiceException(message);
        }
        return value;
    }

    private String normalizeJumpShopProductUrl(String productUrl) {
        String normalized = StringUtils.trim(productUrl);
        if (StringUtils.isBlank(normalized)) {
            return "";
        }
        try {
            URI uri = URI.create(normalized);
            String path = StringUtils.defaultString(uri.getPath());
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            return new URI("https", "jumpshop-benelic.com", path, null, null).toString();
        } catch (Exception ex) {
            throw new ServiceException("Jump Shop 商品链接格式不正确");
        }
    }

    private boolean isLotteryDrawSalesType(String salesType) {
        return StringUtils.isNotBlank(salesType) && salesType.contains("抽選");
    }

    private String requireSingleLotteryReception(List<? extends TicketLotteryEventSessionVo> sessions) {
        Set<String> receptionIds = sessions.stream()
            .map(TicketLotteryEventSessionVo::getReceptionId)
            .filter(StringUtils::isNotBlank)
            .map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (receptionIds.isEmpty()) {
            throw new ServiceException("抽票场次缺少受付标识，请重新解析活动");
        }
        if (receptionIds.size() > 1) {
            throw new ServiceException("一个抽票任务只能选择同一个受付下的场次");
        }
        return receptionIds.iterator().next();
    }

    private String requireSingleLotteryReceptionBo(List<TicketLotteryEventSessionBo> sessions) {
        Set<String> receptionIds = sessions.stream()
            .map(TicketLotteryEventSessionBo::getReceptionId)
            .filter(StringUtils::isNotBlank)
            .map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (receptionIds.isEmpty()) {
            throw new ServiceException("抽票场次缺少受付标识，请重新解析活动");
        }
        if (receptionIds.size() > 1) {
            throw new ServiceException("一个抽票任务只能选择同一个受付下的场次");
        }
        return receptionIds.iterator().next();
    }

    private String firstLivePocketLotteryTicketsUrl(Map<String, Object> options, String... extraUrls) {
        return TicketOrderFlowSupport.firstLivePocketLotteryTicketsUrl(options, extraUrls);
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (target != null && StringUtils.isNotBlank(value)) {
            target.put(key, value);
        }
    }

    private void applyLotteryEventUrl(TicketOrderExecution execution, String purchaseType, String taskOptionsText) {
        if (execution == null || !TicketOrderFlowSupport.isLottery(purchaseType)) {
            return;
        }
        String lotteryEventUrl = resolveLotteryEventUrlFromTaskOptions(taskOptionsText);
        if (StringUtils.isNotBlank(lotteryEventUrl)) {
            execution.setLotteryEventUrl(lotteryEventUrl);
        }
    }

    private void enrichLotteryLinkOccupancy(List<TicketManagedAccountVo> rows, Long platformId, String eventUrl, Long excludeTaskId) {
        Map<Long, TicketLotteryLinkOccupancyService.OccupancyInfo> occupiedMap = ticketLotteryLinkOccupancyService.query(
            rows.stream().map(TicketManagedAccountVo::getAccountId).filter(Objects::nonNull).toList(),
            ObjectUtil.isNotNull(platformId)
                ? List.of(platformId)
                : rows.stream().map(TicketManagedAccountVo::getPlatformId).filter(Objects::nonNull).distinct().toList(),
            eventUrl,
            excludeTaskId
        );
        for (TicketManagedAccountVo row : rows) {
            TicketLotteryLinkOccupancyService.OccupancyInfo info = occupiedMap.get(row.getAccountId());
            if (info == null) {
                continue;
            }
            row.setLotteryLinkOccupied(Boolean.TRUE);
            row.setLotteryLinkOccupiedTaskId(info.taskId());
            row.setLotteryLinkOccupiedTaskName(info.taskName());
            row.setLotteryLinkOccupiedAt(info.occupiedAt());
            row.setLotteryLinkOccupiedStatus(info.executionStatus());
        }
    }

    private String resolveLotteryEventUrlFromTaskOptions(String taskOptionsText) {
        return TicketOrderFlowSupport.resolveLotteryEventUrlFromTaskOptions(taskOptionsText);
    }

    private String normalizeLotteryEventUrl(String rawUrl) {
        return TicketOrderFlowSupport.normalizeLotteryEventUrl(rawUrl);
    }

    private boolean isLivePocketLotteryTicketsUrl(String url) {
        return TicketOrderFlowSupport.isLivePocketLotteryTicketsUrl(url);
    }

    private void applyLivePocketLotteryTicketsUrl(Map<String, Object> options, String ticketsUrl) {
        if (CollUtil.isEmpty(options)) {
            return;
        }
        if (StringUtils.isBlank(ticketsUrl)) {
            LIVEPOCKET_LOTTERY_TICKETS_URL_KEYS.forEach(options::remove);
            return;
        }
        options.put("ticketEntryUrl", ticketsUrl);
        options.put("lotteryEntryUrl", ticketsUrl);
    }

    private String buildLivePocketTicketsUrl(String eventUrl, String receptionId) {
        String normalizedEventUrl = normalizeLotteryEventUrl(eventUrl);
        if (StringUtils.isBlank(normalizedEventUrl) || StringUtils.isBlank(receptionId)) {
            return "";
        }
        if (receptionId.startsWith("group:")) {
            return "";
        }
        return normalizedEventUrl + "/receptions/" + receptionId + "/tickets";
    }

    private List<TicketLotteryEventSessionVo> parseLotterySessionVos(Object value) {
        if (value == null) {
            return List.of();
        }
        JSONArray array = value instanceof JSONArray jsonArray ? jsonArray : JSONUtil.parseArray(value);
        List<TicketLotteryEventSessionVo> sessions = new ArrayList<>();
        for (Object item : array) {
            JSONObject object = item instanceof JSONObject jsonObject ? jsonObject : JSONUtil.parseObj(item);
            String sessionId = object.getStr("sessionId");
            if (StringUtils.isBlank(sessionId)) {
                continue;
            }
            TicketLotteryEventSessionVo vo = new TicketLotteryEventSessionVo();
            vo.setSessionId(sessionId);
            vo.setSessionLabel(StringUtils.defaultIfBlank(object.getStr("sessionLabel"), sessionId));
            vo.setEventUrl(object.getStr("eventUrl"));
            vo.setReceptionId(object.getStr("receptionId"));
            vo.setTicketId(object.getStr("ticketId"));
            vo.setTicketField(object.getStr("ticketField"));
            vo.setReceptionTitle(object.getStr("receptionTitle"));
            vo.setSalesType(object.getStr("salesType"));
            vo.setNotes(object.getStr("notes"));
            Integer maxPurchaseQuantity = object.getInt("maxPurchaseQuantity");
            if (maxPurchaseQuantity == null) {
                maxPurchaseQuantity = Convert.toInt(object.get("maxPurchaseQuantity"), null);
            }
            vo.setMaxPurchaseQuantity(maxPurchaseQuantity);
            sessions.add(vo);
        }
        return sessions;
    }

    private TicketLotteryEventSessionVo findLatestParsedLotterySession(Long platformId, String taskOptionsText, String sessionId) {
        if (platformId == null || StringUtils.isBlank(taskOptionsText)) {
            return null;
        }
        Map<String, Object> taskOptions = TicketOrderFlowSupport.parseTaskOptions(taskOptionsText);
        String eventUrl = Convert.toStr(taskOptions.get("eventUrl"));
        if (StringUtils.isBlank(eventUrl)) {
            return null;
        }
        TicketLotteryEventParseRecord record = parseRecordMapper.selectOne(new LambdaQueryWrapper<TicketLotteryEventParseRecord>()
            .eq(TicketLotteryEventParseRecord::getPlatformId, platformId)
            .eq(TicketLotteryEventParseRecord::getEventUrl, eventUrl)
            .eq(TicketLotteryEventParseRecord::getParseStatus, "completed")
            .orderByDesc(TicketLotteryEventParseRecord::getUpdateTime)
            .last("LIMIT 1"));
        if (record == null || StringUtils.isBlank(record.getSessionsJson())) {
            return null;
        }
        List<TicketLotteryEventSessionVo> sessions = parseLotterySessionVos(record.getSessionsJson());
        if (CollUtil.isEmpty(sessions)) {
            return null;
        }
        if (StringUtils.isBlank(sessionId)) {
            return sessions.get(0);
        }
        return sessions.stream().filter(item -> sessionId.equals(item.getSessionId())).findFirst().orElse(null);
    }

    private TicketLotteryEventSessionVo mergeLotterySessionMeta(TicketLotteryEventSessionVo base, TicketLotteryEventSessionVo fallback) {
        if (base == null) {
            return fallback;
        }
        if (fallback == null) {
            return base;
        }
        if (StringUtils.isBlank(base.getReceptionId())) {
            base.setReceptionId(fallback.getReceptionId());
        }
        if (StringUtils.isBlank(base.getTicketId())) {
            base.setTicketId(fallback.getTicketId());
        }
        if (StringUtils.isBlank(base.getTicketField())) {
            base.setTicketField(fallback.getTicketField());
        }
        if (StringUtils.isBlank(base.getReceptionTitle())) {
            base.setReceptionTitle(fallback.getReceptionTitle());
        }
        if (StringUtils.isBlank(base.getSalesType())) {
            base.setSalesType(fallback.getSalesType());
        }
        if (StringUtils.isBlank(base.getNotes())) {
            base.setNotes(fallback.getNotes());
        }
        if (base.getMaxPurchaseQuantity() == null || base.getMaxPurchaseQuantity() <= 0) {
            base.setMaxPurchaseQuantity(fallback.getMaxPurchaseQuantity());
        }
        return base;
    }

    private void normalizeSaleTaskView(TicketSaleTaskVo row, boolean includeDetailFields) {
        row.setPurchaseType(TicketOrderFlowSupport.defaultPurchaseType(row.getPurchaseType()));
        if (StringUtils.isBlank(row.getConfigSchemaKey()) && ObjectUtil.isNotNull(row.getPlatformId())) {
            TicketPlatformConfig platform = platformMapper.selectById(row.getPlatformId());
            if (platform != null) {
                row.setConfigSchemaKey(TicketOrderFlowSupport.resolveConfigSchemaKey(platform, row.getPurchaseType()));
            }
        }
        String taskOptionsText = StringUtils.defaultIfBlank(row.getTaskOptions(), "{}");
        Map<String, Object> options = TicketOrderFlowSupport.parseTaskOptions(taskOptionsText);
        row.setEventTitle(Convert.toStr(options.get("eventTitle")));
        if (StringUtils.isBlank(row.getLotteryEventUrl()) && StringUtils.isNotBlank(Convert.toStr(options.get("eventUrl")))) {
            row.setLotteryEventUrl(Convert.toStr(options.get("eventUrl")));
        }
        if (includeDetailFields) {
            row.setTaskOptions(taskOptionsText);
            row.setSelectedSessions(parseLotterySessionVos(options.get("selectedSessions")));
        }
    }

    private void refreshSaleTaskStatus(Long taskId) {
        if (taskId == null) {
            return;
        }
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        refreshSaleTaskStatus(taskId, task);
    }

    private void refreshSaleTaskStatus(Long taskId, TicketSaleTask task) {
        if (taskId == null || task == null) {
            return;
        }
        if ("cancelled".equals(task.getTaskStatus())) {
            return;
        }
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getTaskId, taskId)
            .eq(TicketOrderExecution::getScheduleVersion, defaultScheduleVersion(task.getScheduleVersion()))
            .orderByAsc(TicketOrderExecution::getExecutionId));
        if (CollUtil.isEmpty(executions)) {
            return;
        }
        String nextStatus = calculateSaleTaskStatus(task, effectiveSaleTaskExecutions(task.getPurchaseType(), executions));
        boolean shouldUpdate = !Objects.equals(task.getTaskStatus(), nextStatus);
        if (!"draft".equals(nextStatus) && task.getLastExecutedTime() == null) {
            task.setLastExecutedTime(new Date());
            shouldUpdate = true;
        }
        if (shouldUpdate) {
            task.setTaskStatus(nextStatus);
            saleTaskMapper.updateById(task);
        }
    }

    private String calculateSaleTaskStatus(TicketSaleTask task, List<TicketOrderExecution> executions) {
        boolean hasRunning = executions.stream().anyMatch(item -> EXECUTION_RUNNING_STATUSES.contains(item.getExecutionStatus()));
        if (hasRunning) {
            return "executing";
        }
        boolean allQueued = executions.stream().allMatch(item -> "queued".equals(item.getExecutionStatus()));
        if (allQueued) {
            return "draft".equals(task.getTaskStatus()) ? "draft" : "executing";
        }
        boolean hasPendingPayment = executions.stream().anyMatch(item -> EXECUTION_PAYMENT_PENDING_STATUSES.contains(item.getExecutionStatus()));
        boolean hasPaid = executions.stream().anyMatch(item -> "paid".equals(item.getExecutionStatus()));
        boolean hasFailure = executions.stream().anyMatch(item -> EXECUTION_FAILURE_STATUSES.contains(item.getExecutionStatus()));
        boolean allBlocked = executions.stream().allMatch(item -> "blocked".equals(item.getExecutionStatus()));
        boolean allFailed = executions.stream().allMatch(item -> EXECUTION_FAILURE_STATUSES.contains(item.getExecutionStatus()));
        boolean isLottery = TicketOrderFlowSupport.isLottery(task.getPurchaseType());
        if (isLottery) {
            boolean hasLotterySuccess = executions.stream().anyMatch(item -> LOTTERY_SUCCESS_STATUSES.contains(item.getExecutionStatus()));
            boolean allLotterySuccess = executions.stream().allMatch(item -> LOTTERY_SUCCESS_STATUSES.contains(item.getExecutionStatus()));
            boolean hasSubmitted = executions.stream().anyMatch(item -> "submitted".equals(item.getExecutionStatus()));
            if (allBlocked) {
                return "blocked";
            }
            if (allFailed) {
                return "failed";
            }
            if (allLotterySuccess) {
                return "completed";
            }
            if (hasLotterySuccess && hasFailure) {
                return "partial";
            }
            if (hasLotterySuccess || hasSubmitted) {
                return "partial";
            }
            return "failed";
        }

        if (allBlocked) {
            return "blocked";
        }
        if (allFailed) {
            return "failed";
        }
        if ((hasPendingPayment || hasPaid) && hasFailure) {
            return "partial";
        }
        if (hasPendingPayment || hasPaid) {
            return "completed";
        }
        return "failed";
    }

    private void registerLotteryScheduleDispatchAfterCommit(TicketSaleTask task, List<TicketSaleTaskSchedule> schedules) {
        registerLotteryScheduleDispatchAfterCommit(task, schedules, false);
    }

    private void registerLotteryScheduleDispatchAfterCommit(TicketSaleTask task, List<TicketSaleTaskSchedule> schedules, boolean forceImmediate) {
        if (task == null || CollUtil.isEmpty(schedules)) {
            return;
        }
        Long taskId = task.getTaskId();
        Long scheduleVersion = defaultScheduleVersion(task.getScheduleVersion());
        List<Long> scheduleIds = schedules.stream()
            .map(TicketSaleTaskSchedule::getScheduleId)
            .filter(Objects::nonNull)
            .toList();
        Runnable dispatchAction = () -> scheduledExecutorService.execute(() -> enqueueLotterySchedules(taskId, scheduleVersion, scheduleIds, forceImmediate));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatchAction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatchAction.run();
            }
        });
    }

    private void registerRetryLotteryDispatchAfterCommit(Long taskId, List<Long> executionIds) {
        if (taskId == null || CollUtil.isEmpty(executionIds)) {
            return;
        }
        List<Long> ids = new ArrayList<>(executionIds);
        Runnable dispatchAction = () -> scheduledExecutorService.execute(() -> enqueueRetryLotteryExecutions(taskId, ids));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatchAction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatchAction.run();
            }
        });
    }

    private void enqueueRetryLotteryExecutions(Long taskId, List<Long> executionIds) {
        if (taskId == null || CollUtil.isEmpty(executionIds)) {
            return;
        }
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        TicketPlatformConfig platform = task == null ? null : platformMapper.selectById(task.getPlatformId());
        if (task == null || platform == null) {
            log.warn("retry lottery enqueue skipped, taskId={}, reason={}", taskId, task == null ? "task missing" : "platform missing");
            return;
        }
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .in(TicketOrderExecution::getExecutionId, executionIds)
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .orderByAsc(TicketOrderExecution::getExecutionId));
        Set<Long> touchedScheduleIds = new LinkedHashSet<>();
        for (TicketOrderExecution execution : executions) {
            TicketManagedAccount account = accountMapper.selectById(execution.getAccountId());
            TicketSaleTaskSchedule schedule = saleTaskScheduleMapper.selectById(execution.getLotteryScheduleId());
            if (account == null || schedule == null) {
                markLotteryExecutionFailed(execution.getExecutionId(), account == null ? "抽票账号不存在" : "抽票时段不存在", null);
                continue;
            }
            if (isHandsFormPlatform(platform)) {
                prepareHandsFormExtensionExecution(execution.getExecutionId());
                touchedScheduleIds.add(schedule.getScheduleId());
                continue;
            }
            enqueueLotteryExecution(task, schedule, platform, execution, account);
            touchedScheduleIds.add(schedule.getScheduleId());
        }
        for (Long scheduleId : touchedScheduleIds) {
            refreshLotteryScheduleStatus(scheduleId);
        }
        refreshSaleTaskStatus(taskId);
    }

    private void enqueueLotterySchedules(Long taskId, Long scheduleVersion, List<Long> scheduleIds) {
        enqueueLotterySchedules(taskId, scheduleVersion, scheduleIds, false);
    }

    private void enqueueLotterySchedules(Long taskId, Long scheduleVersion, List<Long> scheduleIds, boolean forceImmediate) {
        if (CollUtil.isEmpty(scheduleIds)) {
            return;
        }
        List<TicketSaleTaskSchedule> schedules = saleTaskScheduleMapper.selectList(new LambdaQueryWrapper<TicketSaleTaskSchedule>()
            .in(TicketSaleTaskSchedule::getScheduleId, scheduleIds)
            .orderByAsc(TicketSaleTaskSchedule::getScheduledTime)
            .orderByAsc(TicketSaleTaskSchedule::getScheduleId));
        for (TicketSaleTaskSchedule schedule : schedules) {
            try {
                enqueueLotterySchedule(taskId, scheduleVersion, schedule, forceImmediate);
            } catch (Exception ex) {
                log.error("lottery schedule redis enqueue failed, taskId={}, scheduleId={}", taskId, schedule.getScheduleId(), ex);
                markLotteryScheduleQueueFailed(schedule.getScheduleId(), "Redis 抽票时段入队失败: " + StringUtils.defaultString(ex.getMessage(), "unknown"));
            }
        }
    }

    private void enqueueLotterySchedule(Long taskId, Long scheduleVersion, TicketSaleTaskSchedule schedule) {
        enqueueLotterySchedule(taskId, scheduleVersion, schedule, false);
    }

    private void enqueueLotterySchedule(Long taskId, Long scheduleVersion, TicketSaleTaskSchedule schedule, boolean forceImmediate) {
        if (schedule == null || schedule.getScheduleId() == null) {
            return;
        }
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        TicketPlatformConfig platform = task == null ? null : platformMapper.selectById(task.getPlatformId());
        if (task == null || platform == null) {
            markLotteryScheduleQueueFailed(schedule.getScheduleId(), task == null ? "抽票任务不存在" : "平台不存在");
            return;
        }
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getLotteryScheduleId, schedule.getScheduleId())
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .orderByAsc(TicketOrderExecution::getExecutionId));
        if (CollUtil.isEmpty(executions)) {
            return;
        }
        long dispatchAt = forceImmediate ? 0L : (schedule.getScheduledTime() == null ? 0L : schedule.getScheduledTime().getTime());
        Date now = new Date();
        boolean handsFormPlatform = isHandsFormPlatform(platform);
        for (TicketOrderExecution execution : executions) {
            TicketManagedAccount account = accountMapper.selectById(execution.getAccountId());
            if (account == null) {
                markLotteryExecutionFailed(execution.getExecutionId(), "抽票账号不存在", null);
                continue;
            }
            if (handsFormPlatform) {
                if (dispatchAt > System.currentTimeMillis()) {
                    ticketPythonStringRedisTemplate.opsForZSet().add(
                        ticketPythonExecutorProperties.getDelayedZsetKey(),
                        String.valueOf(execution.getExecutionId()),
                        dispatchAt
                    );
                } else {
                    prepareHandsFormExtensionExecution(execution.getExecutionId());
                }
                continue;
            }
            writeLotteryExecutionJob(task, schedule, platform, execution, account);
            if (dispatchAt > System.currentTimeMillis()) {
                ticketPythonStringRedisTemplate.opsForZSet().add(ticketPythonExecutorProperties.getDelayedZsetKey(), String.valueOf(execution.getExecutionId()), dispatchAt);
            } else {
                addLotteryReadyStream(execution.getExecutionId());
            }
        }
        saleTaskScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTaskSchedule.class)
            .eq(TicketSaleTaskSchedule::getScheduleId, schedule.getScheduleId())
            .eq(TicketSaleTaskSchedule::getScheduleStatus, "pending")
            .set(
                TicketSaleTaskSchedule::getResultMessage,
                handsFormPlatform
                    ? (dispatchAt > System.currentTimeMillis() ? "等待 Hands 扩展执行时间" : HANDS_EXTENSION_WAITING_MESSAGE)
                    : (forceImmediate ? "立即执行，已写入 Python 抽票队列" : (dispatchAt > System.currentTimeMillis() ? "等待 Python 抽票队列调度" : "已写入 Python 抽票队列"))
            )
            .set(dispatchAt <= System.currentTimeMillis(), TicketSaleTaskSchedule::getDispatchedTime, now));
        log.info(
            "lottery schedule queued, taskId={}, scheduleId={}, executions={}, dispatchAt={}, forceImmediate={}, handsForm={}",
            taskId, schedule.getScheduleId(), executions.size(), dispatchAt, forceImmediate, handsFormPlatform
        );
    }

    private void markLotteryScheduleQueueFailed(Long scheduleId, String message) {
        if (scheduleId == null) {
            return;
        }
        Date now = new Date();
        TicketSaleTaskSchedule schedule = saleTaskScheduleMapper.selectById(scheduleId);
        saleTaskScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTaskSchedule.class)
            .eq(TicketSaleTaskSchedule::getScheduleId, scheduleId)
            .eq(TicketSaleTaskSchedule::getScheduleStatus, "pending")
            .set(TicketSaleTaskSchedule::getScheduleStatus, "failed")
            .set(TicketSaleTaskSchedule::getFinishedTime, now)
            .set(TicketSaleTaskSchedule::getResultMessage, message));
        orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getLotteryScheduleId, scheduleId)
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .set(TicketOrderExecution::getExecutionStatus, "blocked")
            .set(TicketOrderExecution::getCurrentStep, "completed")
            .set(TicketOrderExecution::getStepStatus, "failed")
            .set(TicketOrderExecution::getResultMessage, message)
            .set(TicketOrderExecution::getExecutedAt, now));
        if (schedule != null) {
            refreshSaleTaskStatus(schedule.getTaskId());
        }
    }

    private void cleanupLotteryScheduleQueue(Long taskId) {
        if (taskId == null) {
            return;
        }
        List<Long> scheduleIds = saleTaskScheduleMapper.selectList(new LambdaQueryWrapper<TicketSaleTaskSchedule>()
                .select(TicketSaleTaskSchedule::getScheduleId)
                .eq(TicketSaleTaskSchedule::getTaskId, taskId))
            .stream()
            .map(TicketSaleTaskSchedule::getScheduleId)
            .filter(Objects::nonNull)
            .toList();
        cleanupLotteryScheduleQueueItems(scheduleIds);
    }

    private void cleanupLotteryScheduleQueueItems(List<Long> scheduleIds) {
        if (CollUtil.isEmpty(scheduleIds)) {
            return;
        }
        for (Long scheduleId : scheduleIds) {
            String member = String.valueOf(scheduleId);
            try {
                ticketPythonStringRedisTemplate.opsForZSet().remove(ticketPythonExecutorProperties.getDelayedZsetKey(), member);
                removeLotterySchedulePayload(scheduleId);
            } catch (Exception ex) {
                log.warn("cleanup lottery redis schedule failed, scheduleId={}", scheduleId, ex);
            }
        }
    }

    private void removeLotterySchedulePayload(Long scheduleId) {
        if (scheduleId == null) {
            return;
        }
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .select(TicketOrderExecution::getExecutionId)
            .eq(TicketOrderExecution::getLotteryScheduleId, scheduleId));
        if (CollUtil.isEmpty(executions)) {
            return;
        }
        for (TicketOrderExecution execution : executions) {
            if (execution.getExecutionId() == null) {
                continue;
            }
            ticketPythonStringRedisTemplate.opsForZSet().remove(ticketPythonExecutorProperties.getDelayedZsetKey(), String.valueOf(execution.getExecutionId()));
            ticketPythonStringRedisTemplate.delete(lotteryJobKey(execution.getExecutionId()));
        }
    }

    private String lotteryJobKey(Long executionId) {
        return lotteryJobKey(String.valueOf(executionId));
    }

    private String lotteryJobKey(String executionId) {
        return ticketPythonExecutorProperties.getJobKeyPrefix() + executionId;
    }

    private String flashSaleJobKey(Long executionId) {
        return ticketPythonExecutorProperties.getFlashSaleJobKeyPrefix() + executionId;
    }

    private String flashSaleQueueMember(Long executionId, String phase) {
        return executionId + ":" + StringUtils.defaultIfBlank(phase, "run");
    }

    private String resolveAccountPassword(TicketManagedAccount account) {
        if (account == null || StringUtils.isBlank(account.getAccountInfo())) {
            return "";
        }
        try {
            JSONObject accountInfo = JSONUtil.parseObj(account.getAccountInfo());
            return StringUtils.defaultIfBlank(accountInfo.getStr("platformPassword"), accountInfo.getStr("password"));
        } catch (Exception ex) {
            log.warn("resolve account password failed, accountId={}", account.getAccountId(), ex);
            return "";
        }
    }

    private boolean hasLivePocketFlashSaleCredential(TicketManagedAccount account) {
        return account != null
            && (StringUtils.isNotBlank(account.getLoginReqData()) || StringUtils.isNotBlank(resolveAccountPassword(account)));
    }

    private void cancelPendingSaleTaskSchedules(Long taskId, String message, Date now) {
        if (taskId == null) {
            return;
        }
        saleTaskScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTaskSchedule.class)
            .eq(TicketSaleTaskSchedule::getTaskId, taskId)
            .eq(TicketSaleTaskSchedule::getScheduleStatus, "pending")
            .set(TicketSaleTaskSchedule::getScheduleStatus, "cancelled")
            .set(TicketSaleTaskSchedule::getFinishedTime, now)
            .set(TicketSaleTaskSchedule::getResultMessage, message));
    }

    private void cancelQueuedExecutions(Long taskId, String message, Date now) {
        if (taskId == null) {
            return;
        }
        List<TicketOrderExecution> queuedExecutions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getTaskId, taskId)
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .orderByAsc(TicketOrderExecution::getExecutionId));
        if (CollUtil.isEmpty(queuedExecutions)) {
            return;
        }
        for (TicketOrderExecution execution : queuedExecutions) {
            log.warn("cancel queued execution, executionId={}, taskId={}", execution.getExecutionId(), taskId);
            ticketOrderExecutorClient.removeDelayedExecution(execution.getExecutionId());
            removePythonQueuedExecution(execution.getExecutionId());
            orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
                .eq(TicketOrderExecution::getExecutionId, execution.getExecutionId())
                .eq(TicketOrderExecution::getExecutionStatus, "queued")
                .set(TicketOrderExecution::getExecutionStatus, "cancelled")
                .set(TicketOrderExecution::getCurrentStep, "completed")
                .set(TicketOrderExecution::getStepStatus, "cancelled")
                .set(TicketOrderExecution::getResultMessage, message)
                .set(TicketOrderExecution::getExecutedAt, now));
        }
    }

    private void cleanupPendingSaleTaskSchedules(List<Long> taskIds, String message) {
        if (CollUtil.isEmpty(taskIds)) {
            return;
        }
        List<Long> scheduleIds = saleTaskScheduleMapper.selectList(new LambdaQueryWrapper<TicketSaleTaskSchedule>()
                .select(TicketSaleTaskSchedule::getScheduleId)
                .in(TicketSaleTaskSchedule::getTaskId, taskIds))
            .stream()
            .map(TicketSaleTaskSchedule::getScheduleId)
            .filter(Objects::nonNull)
            .toList();
        cleanupLotteryScheduleQueueItems(scheduleIds);
        List<TicketOrderExecution> queuedExecutions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .in(TicketOrderExecution::getTaskId, taskIds)
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .orderByAsc(TicketOrderExecution::getExecutionId));
        blockQueuedExecutions(queuedExecutions, message);
    }

    private void invalidateQueuedExecutions(Long taskId, String message) {
        if (taskId == null) {
            return;
        }
        List<TicketOrderExecution> queuedExecutions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getTaskId, taskId)
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .orderByAsc(TicketOrderExecution::getExecutionId));
        blockQueuedExecutions(queuedExecutions, message);
    }

    private void invalidateQueuedBatchExecutions(Long batchTaskId, String message) {
        if (batchTaskId == null) {
            return;
        }
        List<TicketOrderExecution> queuedExecutions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getBatchTaskId, batchTaskId)
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .orderByAsc(TicketOrderExecution::getExecutionId));
        blockQueuedExecutions(queuedExecutions, message);
    }

    private void blockQueuedExecutions(List<TicketOrderExecution> executions, String message) {
        if (CollUtil.isEmpty(executions)) {
            return;
        }
        Date now = new Date();
        for (TicketOrderExecution execution : executions) {
            log.warn("block queued execution, executionId={}, taskId={}, reason={}", execution.getExecutionId(), execution.getTaskId(), message);
            ticketOrderExecutorClient.removeDelayedExecution(execution.getExecutionId());
            removePythonQueuedExecution(execution.getExecutionId());
            LambdaUpdateWrapper<TicketOrderExecution> updateWrapper = Wrappers.lambdaUpdate();
            updateWrapper.eq(TicketOrderExecution::getExecutionId, execution.getExecutionId())
                .eq(TicketOrderExecution::getExecutionStatus, "queued")
                .set(TicketOrderExecution::getExecutionStatus, "blocked")
                .set(TicketOrderExecution::getCurrentStep, "completed")
                .set(TicketOrderExecution::getStepStatus, "failed")
                .set(TicketOrderExecution::getResultMessage, message)
                .set(TicketOrderExecution::getExecutedAt, now);
            orderExecutionMapper.update(null, updateWrapper);
        }
    }

    private void removePythonQueuedExecution(Long executionId) {
        if (executionId == null) {
            return;
        }
        ticketPythonStringRedisTemplate.opsForZSet().remove(ticketPythonExecutorProperties.getDelayedZsetKey(), String.valueOf(executionId));
        ticketPythonStringRedisTemplate.opsForZSet().remove(
            ticketPythonExecutorProperties.getFlashSaleDelayedZsetKey(),
            flashSaleQueueMember(executionId, "warmup"),
            flashSaleQueueMember(executionId, "run"),
            String.valueOf(executionId)
        );
        ticketPythonStringRedisTemplate.delete(lotteryJobKey(executionId));
        ticketPythonStringRedisTemplate.delete(flashSaleJobKey(executionId));
    }

    private void registerDispatchAfterCommit(TicketSaleTask task, TicketPlatformConfig platform, List<TicketManagedAccount> accounts,
                                             List<TicketOrderExecution> executions, Long operatorUserId, String triggerSource,
                                             boolean forceImmediate) {
        log.info("register purchase task dispatch after commit, taskId={}, executionCount={}, triggerSource={}, forceImmediate={}",
            task.getTaskId(), executions.size(), triggerSource, forceImmediate);
        Runnable dispatchAction = () -> scheduledExecutorService.execute(
            () -> dispatchSaleTask(task, platform, accounts, executions, operatorUserId, triggerSource, forceImmediate)
        );
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatchAction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatchAction.run();
            }
        });
    }

    private void registerFlashSaleDispatchAfterCommit(TicketSaleTask task, TicketPlatformConfig platform,
                                                      List<TicketOrderExecution> executions, boolean forceImmediate) {
        if (task == null || platform == null || CollUtil.isEmpty(executions)) {
            return;
        }
        Long taskId = task.getTaskId();
        Long scheduleVersion = defaultScheduleVersion(task.getScheduleVersion());
        List<Long> executionIds = executions.stream()
            .map(TicketOrderExecution::getExecutionId)
            .filter(Objects::nonNull)
            .toList();
        Runnable dispatchAction = () -> scheduledExecutorService.execute(
            () -> enqueueFlashSaleExecutions(taskId, scheduleVersion, executionIds, forceImmediate)
        );
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatchAction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatchAction.run();
            }
        });
    }

    private void enqueueFlashSaleExecutions(Long taskId, Long scheduleVersion, List<Long> executionIds, boolean forceImmediate) {
        if (taskId == null || CollUtil.isEmpty(executionIds)) {
            return;
        }
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        TicketPlatformConfig platform = task == null ? null : platformMapper.selectById(task.getPlatformId());
        if (task == null || platform == null) {
            log.warn("flash sale python enqueue skipped, taskId={}, reason={}", taskId, task == null ? "task missing" : "platform missing");
            return;
        }
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .in(TicketOrderExecution::getExecutionId, executionIds)
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .eq(TicketOrderExecution::getScheduleVersion, defaultScheduleVersion(scheduleVersion))
            .orderByAsc(TicketOrderExecution::getExecutionId));
        for (TicketOrderExecution execution : executions) {
            try {
                enqueueFlashSaleExecution(task, platform, execution, forceImmediate);
            } catch (Exception ex) {
                log.error("flash sale python enqueue failed, executionId={}", execution.getExecutionId(), ex);
                markFlashSaleExecutionFailed(execution.getExecutionId(), "Python 抢票任务入队失败: " + StringUtils.defaultString(ex.getMessage(), "unknown"), ex);
            }
        }
        refreshSaleTaskStatus(taskId);
    }

    private void enqueueFlashSaleExecution(TicketSaleTask task, TicketPlatformConfig platform,
                                           TicketOrderExecution execution, boolean forceImmediate) {
        if (task == null || platform == null || execution == null || execution.getExecutionId() == null) {
            return;
        }
        TicketManagedAccount account = accountMapper.selectById(execution.getAccountId());
        if (account == null) {
            markFlashSaleExecutionFailed(execution.getExecutionId(), "抢票账号不存在", null);
            return;
        }
        long nowMillis = System.currentTimeMillis();
        long dispatchAt = forceImmediate ? 0L : (task.getScheduledTime() == null ? 0L : task.getScheduledTime().getTime());
        long warmupAt = forceImmediate ? 0L : (task.getWarmupTime() == null ? 0L : task.getWarmupTime().getTime());
        boolean hasWarmup = dispatchAt > nowMillis && warmupAt > 0L && warmupAt < dispatchAt;
        boolean useLivePocketFlashSaleQueue = isLivePocketFlashSaleTask(platform, task.getPurchaseType());
        String message;
        if (useLivePocketFlashSaleQueue) {
            writeFlashSaleExecutionJob(task, platform, execution, account);
            if (hasWarmup && warmupAt > nowMillis) {
                ticketPythonStringRedisTemplate.opsForZSet().add(
                    ticketPythonExecutorProperties.getFlashSaleDelayedZsetKey(),
                    flashSaleQueueMember(execution.getExecutionId(), "warmup"),
                    warmupAt
                );
                message = "等待 Python 普通抢票预热登录";
            } else if (hasWarmup) {
                addFlashSaleReadyStream(execution.getExecutionId(), "warmup");
                message = "等待 Python 普通抢票预热登录";
            } else if (dispatchAt > nowMillis) {
                ticketPythonStringRedisTemplate.opsForZSet().add(
                    ticketPythonExecutorProperties.getFlashSaleDelayedZsetKey(),
                    flashSaleQueueMember(execution.getExecutionId(), "run"),
                    dispatchAt
                );
                message = "等待 Python 普通抢票队列调度";
            } else {
                addFlashSaleReadyStream(execution.getExecutionId(), "run");
                message = "等待 Python 普通抢票执行器消费";
            }
        } else {
            writeLotteryExecutionJob(task, null, platform, execution, account);
            if (dispatchAt > nowMillis) {
                ticketPythonStringRedisTemplate.opsForZSet().add(
                    ticketPythonExecutorProperties.getDelayedZsetKey(),
                    String.valueOf(execution.getExecutionId()),
                    dispatchAt
                );
                message = "等待 Python 抢票队列调度";
            } else {
                addLotteryReadyStream(execution.getExecutionId());
                message = "等待 Python 抢票执行器消费";
            }
        }
        Date now = new Date();
        orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getExecutionId, execution.getExecutionId())
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .set(TicketOrderExecution::getResultMessage, message)
            .set(dispatchAt <= nowMillis || (useLivePocketFlashSaleQueue && hasWarmup && warmupAt <= nowMillis), TicketOrderExecution::getHeartbeatAt, now));
    }

    private void markFlashSaleExecutionFailed(Long executionId, String message, Exception ex) {
        if (ex != null) {
            log.error("flash sale execution failed, executionId={}", executionId, ex);
        }
        Date now = new Date();
        orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getExecutionId, executionId)
            .set(TicketOrderExecution::getExecutionStatus, "failed")
            .set(TicketOrderExecution::getCurrentStep, "PURCHASE_SUBMIT")
            .set(TicketOrderExecution::getStepStatus, "failed")
            .set(TicketOrderExecution::getResultMessage, StringUtils.defaultIfBlank(message, "普通抢票执行失败"))
            .set(TicketOrderExecution::getExecutedAt, now)
            .set(TicketOrderExecution::getHeartbeatAt, now));
    }

    private Date resolveTaskDispatchTime(TicketSaleTask task, boolean forceImmediate) {
        if (forceImmediate) {
            return new Date();
        }
        long now = System.currentTimeMillis();
        Long scheduledAt = task.getScheduledTime() == null ? null : task.getScheduledTime().getTime();
        Long warmupAt = task.getWarmupTime() == null ? null : task.getWarmupTime().getTime();
        if (warmupAt != null && scheduledAt != null && warmupAt < scheduledAt && scheduledAt > now) {
            return warmupAt > now ? task.getWarmupTime() : new Date(now);
        }
        if (warmupAt != null && warmupAt > now && scheduledAt == null) {
            return task.getWarmupTime();
        }
        if (scheduledAt != null && scheduledAt > now) {
            long leadMs = Math.max(ticketOrderExecutorProperties.getAutoWarmupLeadMs(), 0L);
            return new Date(Math.max(now, scheduledAt - leadMs));
        }
        return new Date(now);
    }

    private Long nextScheduleVersion(Long currentVersion) {
        return defaultScheduleVersion(currentVersion) + 1L;
    }

    private Long defaultScheduleVersion(Long currentVersion) {
        return currentVersion == null || currentVersion <= 0 ? 1L : currentVersion;
    }

    private static final class LoginProgress {

        private Long batchId;
        private Long platformId;
        private String platformName;
        private Long accountId;
        private Long phoneId;
        private String email;
        private String accountInfo;
        private String reqData;
        private String phoneNumber;
        private String stepStatus;
        private String loginStatus;
        private String lastError;
        private Date lastLoginTime;
        private String message;
        private TicketManagedAccount account;

        private static LoginProgress processing(Long batchId, TicketPlatformConfig platform, TicketManagedAccount account, TicketPhoneNumber phone) {
            LoginProgress progress = base(batchId, platform, account, phone);
            progress.stepStatus = "processing";
            progress.loginStatus = account == null ? null : account.getLoginStatus();
            progress.message = "正在登录";
            progress.account = account;
            return progress;
        }

        private static LoginProgress success(Long batchId, TicketPlatformConfig platform, TicketManagedAccount account, TicketPhoneNumber phone, String message) {
            LoginProgress progress = base(batchId, platform, account, phone);
            progress.stepStatus = "success";
            progress.loginStatus = account == null ? null : account.getLoginStatus();
            progress.lastError = account == null ? null : account.getLastError();
            progress.lastLoginTime = account == null ? null : account.getLastLoginTime();
            progress.message = message;
            progress.account = account;
            return progress;
        }

        private static LoginProgress failed(Long batchId, TicketPlatformConfig platform, TicketManagedAccount account, TicketPhoneNumber phone, String message) {
            LoginProgress progress = base(batchId, platform, account, phone);
            progress.stepStatus = "failed";
            progress.loginStatus = account == null ? null : account.getLoginStatus();
            progress.lastError = account == null ? message : account.getLastError();
            progress.lastLoginTime = account == null ? null : account.getLastLoginTime();
            progress.message = message;
            progress.account = account;
            return progress;
        }

        private static LoginProgress base(Long batchId, TicketPlatformConfig platform, TicketManagedAccount account, TicketPhoneNumber phone) {
            LoginProgress progress = new LoginProgress();
            progress.batchId = batchId;
            progress.platformId = platform.getPlatformId();
            progress.platformName = platform.getPlatformName();
            progress.accountId = account == null ? null : account.getAccountId();
            progress.phoneId = account == null ? null : account.getPhoneId();
            progress.email = account == null ? null : account.getEmail();
            progress.accountInfo = account == null ? null : account.getAccountInfo();
            progress.reqData = account == null ? null : account.getLoginReqData();
            progress.phoneNumber = phone == null ? null : phone.getPhoneNumber();
            return progress;
        }

        private Long getBatchId() {
            return batchId;
        }

        private Long getPlatformId() {
            return platformId;
        }

        private String getPlatformName() {
            return platformName;
        }

        private Long getAccountId() {
            return accountId;
        }

        private Long getPhoneId() {
            return phoneId;
        }

        private String getEmail() {
            return email;
        }

        private String getAccountInfo() {
            return accountInfo;
        }

        private String getReqData() {
            return reqData;
        }

        private String getPhoneNumber() {
            return phoneNumber;
        }

        private String getStepStatus() {
            return stepStatus;
        }

        private String getLoginStatus() {
            return loginStatus;
        }

        private String getLastError() {
            return lastError;
        }

        private Date getLastLoginTime() {
            return lastLoginTime;
        }

        private String getMessage() {
            return message;
        }

        private TicketManagedAccount getAccount() {
            return account;
        }
    }

    private static final class RegistrationProgress {

        private Long batchId;
        private Long platformId;
        private String platformName;
        private Long phoneId;
        private String phoneNumber;
        private String stepStatus;
        private String phoneStatus;
        private String note;
        private Long accountId;
        private String email;
        private String accountInfo;
        private String reqData;
        private String message;
        private TicketPhoneNumber phone;

        private static RegistrationProgress processing(Long batchId, TicketPlatformConfig platform, TicketPhoneNumber phone, String note) {
            RegistrationProgress progress = base(batchId, platform, phone);
            progress.stepStatus = "processing";
            progress.phoneStatus = phone == null ? null : phone.getStatus();
            progress.note = note;
            progress.message = "正在注册";
            progress.phone = phone;
            return progress;
        }

        private static RegistrationProgress success(Long batchId, TicketPlatformConfig platform, TicketPhoneNumber phone, String message, String note, TicketManagedAccount account) {
            RegistrationProgress progress = base(batchId, platform, phone);
            progress.stepStatus = "success";
            progress.phoneStatus = phone == null ? null : phone.getStatus();
            progress.note = note;
            progress.accountId = account == null ? null : account.getAccountId();
            progress.email = account == null ? null : account.getEmail();
            progress.accountInfo = account == null ? null : account.getAccountInfo();
            progress.reqData = account == null ? null : account.getReqData();
            progress.message = message;
            progress.phone = phone;
            return progress;
        }

        private static RegistrationProgress failed(Long batchId, TicketPlatformConfig platform, TicketPhoneNumber phone, String message, String note, TicketManagedAccount account) {
            RegistrationProgress progress = base(batchId, platform, phone);
            progress.stepStatus = "failed";
            progress.phoneStatus = phone == null ? null : phone.getStatus();
            progress.note = note;
            progress.accountId = account == null ? null : account.getAccountId();
            progress.email = account == null ? null : account.getEmail();
            progress.accountInfo = account == null ? null : account.getAccountInfo();
            progress.reqData = account == null ? null : account.getReqData();
            progress.message = message;
            progress.phone = phone;
            return progress;
        }

        private static RegistrationProgress skipped(Long batchId, TicketPlatformConfig platform, TicketPhoneNumber phone, String message, String note, TicketManagedAccount account) {
            RegistrationProgress progress = base(batchId, platform, phone);
            progress.stepStatus = "skipped";
            progress.phoneStatus = phone == null ? null : phone.getStatus();
            progress.note = note;
            progress.accountId = account == null ? null : account.getAccountId();
            progress.email = account == null ? null : account.getEmail();
            progress.accountInfo = account == null ? null : account.getAccountInfo();
            progress.reqData = account == null ? null : account.getReqData();
            progress.message = message;
            progress.phone = phone;
            return progress;
        }

        private static RegistrationProgress base(Long batchId, TicketPlatformConfig platform, TicketPhoneNumber phone) {
            RegistrationProgress progress = new RegistrationProgress();
            progress.batchId = batchId;
            progress.platformId = platform.getPlatformId();
            progress.platformName = platform.getPlatformName();
            progress.phoneId = phone == null ? null : phone.getPhoneId();
            progress.phoneNumber = phone == null ? null : phone.getPhoneNumber();
            return progress;
        }

        private Long getBatchId() {
            return batchId;
        }

        private Long getPlatformId() {
            return platformId;
        }

        private String getPlatformName() {
            return platformName;
        }

        private Long getPhoneId() {
            return phoneId;
        }

        private String getPhoneNumber() {
            return phoneNumber;
        }

        private String getStepStatus() {
            return stepStatus;
        }

        private String getPhoneStatus() {
            return phoneStatus;
        }

        private String getNote() {
            return note;
        }

        private Long getAccountId() {
            return accountId;
        }

        private String getEmail() {
            return email;
        }

        private String getAccountInfo() {
            return accountInfo;
        }

        private String getReqData() {
            return reqData;
        }

        private String getMessage() {
            return message;
        }

        private TicketPhoneNumber getPhone() {
            return phone;
        }
    }

    private <T, K> Map<K, T> loadMap(List<K> ids, Function<List<K>, List<T>> loader, Function<T, K> keyMapper) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyMap();
        }
        return loader.apply(ids).stream()
            .collect(Collectors.toMap(keyMapper, Function.identity(), (left, right) -> right));
    }

    private void recordAudit(String moduleName, String actionType, String businessType, String businessKey, String status, String message, Object payload) {
        try {
            TicketAuditEvent auditEvent = new TicketAuditEvent();
            auditEvent.setModuleName(moduleName);
            auditEvent.setActionType(actionType);
            auditEvent.setBusinessType(businessType);
            auditEvent.setBusinessKey(fitAuditText(businessKey, AUDIT_BUSINESS_KEY_MAX_LENGTH));
            auditEvent.setAuditStatus(status);
            auditEvent.setMessage(fitAuditText(message, AUDIT_MESSAGE_MAX_LENGTH));
            auditEvent.setPayload(JSONUtil.toJsonStr(payload));
            auditEvent.setEventTime(new Date());
            auditEventMapper.insert(auditEvent);
        } catch (Exception ex) {
            log.warn("ticket audit record failed, module={}, action={}, businessType={}, businessKey={}",
                moduleName, actionType, businessType, businessKey, ex);
        }
    }

    private String fitAuditText(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private String normalizeLastError(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return fitLastError(StringUtils.trim(value));
    }

    private String fitLastError(String value) {
        return fitAuditText(value, LAST_ERROR_MAX_LENGTH);
    }

    private String fitResultMessage(String value) {
        return fitAuditText(value, RESULT_MESSAGE_MAX_LENGTH);
    }

    private record NamePair(String text, String kana) {
    }

    private record HandsFormProfile(String familyName, String givenName, String fullName, String furigana) {
    }
}
