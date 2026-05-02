package org.dromara.ticket.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
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
import org.dromara.ticket.adapter.*;
import org.dromara.ticket.config.TicketOrderExecutorProperties;
import org.dromara.ticket.domain.*;
import org.dromara.ticket.domain.bo.*;
import org.dromara.ticket.domain.dto.TicketLoginProgressMessage;
import org.dromara.ticket.domain.dto.TicketOrderDispatchRequest;
import org.dromara.ticket.domain.dto.TicketRegisterProgressMessage;
import org.dromara.ticket.domain.vo.*;
import org.dromara.ticket.mapper.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.dromara.ticket.service.ITicketOpsService;
import org.dromara.ticket.service.TicketMailReaderService;
import org.dromara.ticket.service.TicketOrderExecutorClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TicketOpsServiceImpl implements ITicketOpsService {

    private static final Set<String> ACTIVE_RELATION_STATUSES = Set.of("registered", "logged_in", "registering", "verification_pending");
    private static final Set<String> ENABLED_PHONE_STATUSES = Set.of("available", "disabled");
    private static final Set<String> RUNNING_REGISTER_RELATION_STATUSES = Set.of("registering", "verification_pending");
    private static final Set<String> ACCOUNT_STATUSES = Set.of("pending_register", "pending_activation", "activated", "registered", "disabled");
    private static final Set<String> LOGIN_STATUSES = Set.of("offline", "logged_in", "login_failed");
    private static final Set<String> EXECUTION_RUNNING_STATUSES = Set.of("running");
    private static final Set<String> EXECUTION_PAYMENT_PENDING_STATUSES = Set.of("submitted", "pending_payment");
    private static final Set<String> EXECUTION_FAILURE_STATUSES = Set.of("failed", "blocked", "timeout");
    private static final int AUDIT_BUSINESS_KEY_MAX_LENGTH = 128;
    private static final int AUDIT_MESSAGE_MAX_LENGTH = 500;

    private final TicketPlatformConfigMapper platformMapper;
    private final TicketPhoneNumberMapper phoneMapper;
    private final TicketPhonePlatformRelationMapper relationMapper;
    private final TicketManagedAccountMapper accountMapper;
    private final TicketRegistrationBatchMapper registrationBatchMapper;
    private final TicketRegistrationBatchDetailMapper registrationBatchDetailMapper;
    private final TicketLoginBatchMapper loginBatchMapper;
    private final TicketLoginBatchDetailMapper loginBatchDetailMapper;
    private final TicketMailboxAccountMapper mailboxAccountMapper;
    private final TicketEventConfigMapper eventMapper;
    private final TicketSaleTaskMapper saleTaskMapper;
    private final TicketSaleTaskAccountMapper saleTaskAccountMapper;
    private final TicketOrderExecutionMapper orderExecutionMapper;
    private final TicketAuditEventMapper auditEventMapper;
    private final TicketPlatformAdapterRegistry adapterRegistry;
    private final TicketOrderExecutorClient ticketOrderExecutorClient;
    private final TicketOrderExecutorProperties ticketOrderExecutorProperties;
    private final TicketMailReaderService ticketMailReaderService;
    private final TransactionTemplate transactionTemplate;
    @Qualifier("scheduledExecutorService")
    private final ScheduledExecutorService scheduledExecutorService;

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
        entity.setAdapterType(entity.getPlatformCode());
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
        entity.setAdapterType(entity.getPlatformCode());
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
    public R<Long> registerFromPhones(Long platformId, TicketBatchRegisterBo bo) {
        TicketPlatformConfig platform = requirePlatform(platformId);
        List<Long> phoneIds = CollUtil.distinct(bo.getPhoneIds());
        if (CollUtil.isEmpty(phoneIds)) {
            return R.warn("号码列表为空");
        }

        TicketRegistrationBatch batch = new TicketRegistrationBatch();
        batch.setPlatformId(platformId);
        batch.setBatchNo("REG-" + System.currentTimeMillis() + RandomUtil.randomNumbers(4));
        batch.setBatchStatus("executing");
        batch.setTotalCount(phoneIds.size());
        batch.setSuccessCount(0);
        batch.setSkippedCount(0);
        batch.setFailedCount(0);
        batch.setResultSummary("[]");
        batch.setExecutedAt(null);
        registrationBatchMapper.insert(batch);

        Long userId = LoginHelper.getUserId();
        scheduledExecutorService.execute(() -> processRegistrationBatch(batch.getBatchId(), platform, phoneIds, userId));

        recordAudit("registration", "startBatch", "registrationBatch", String.valueOf(batch.getBatchId()), "success", "Registration batch started", batch);
        return R.ok("Registration batch started", batch.getBatchId());
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
        LambdaQueryWrapper<TicketManagedAccount> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ObjectUtil.isNotNull(bo.getAccountId()), TicketManagedAccount::getAccountId, bo.getAccountId())
            .eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketManagedAccount::getPlatformId, bo.getPlatformId())
            .eq(ObjectUtil.isNotNull(bo.getPhoneId()), TicketManagedAccount::getPhoneId, bo.getPhoneId())
            .like(StringUtils.isNotBlank(bo.getEmail()), TicketManagedAccount::getEmail, bo.getEmail())
            .eq(StringUtils.isNotBlank(bo.getAccountStatus()), TicketManagedAccount::getAccountStatus, bo.getAccountStatus())
            .eq(StringUtils.isNotBlank(bo.getLoginStatus()), TicketManagedAccount::getLoginStatus, bo.getLoginStatus())
            .orderByDesc(TicketManagedAccount::getAccountId);
        Page<TicketManagedAccountVo> page = accountMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichAccounts(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public TableDataInfo<TicketPhoneNumberVo> selectBindablePhonePage(Long platformId, TicketPhoneNumberBo bo, PageQuery pageQuery) {
        return selectRegisterablePhonePage(platformId, bo, pageQuery);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int createManagedAccount(TicketManagedAccountCreateBo bo) {
        TicketPlatformConfig platform = requirePlatform(bo.getPlatformId());
        if (ObjectUtil.isNull(bo.getPhoneId())) {
            throw new ServiceException("来源号码不能为空");
        }

        String email = StringUtils.trim(bo.getEmail());
        if (StringUtils.isBlank(email)) {
            throw new ServiceException("邮箱不能为空");
        }

        TicketPhoneNumber phone = phoneMapper.selectById(bo.getPhoneId());
        if (phone == null) {
            throw new ServiceException("号码不存在");
        }
        if (!"available".equals(phone.getStatus())) {
            throw new ServiceException("号码不可用");
        }

        long emailExists = accountMapper.selectCount(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platform.getPlatformId())
            .eq(TicketManagedAccount::getEmail, email));
        if (emailExists > 0) {
            throw new ServiceException("同平台下邮箱已存在");
        }

        TicketPhonePlatformRelation relation = getRelation(platform.getPlatformId(), phone.getPhoneId());
        if (relation != null && ACTIVE_RELATION_STATUSES.contains(relation.getStatus())) {
            throw new ServiceException("该号码在当前平台已存在有效关系");
        }

        TicketManagedAccount account = new TicketManagedAccount();
        account.setPlatformId(platform.getPlatformId());
        account.setPhoneId(phone.getPhoneId());
        account.setEmail(email);
        account.setAccountInfo(normalizeJsonText(bo.getAccountInfo()));
        account.setReqData(normalizeJsonText(bo.getReqData()));
        account.setLoginReqData(normalizeJsonText(bo.getLoginReqData()));
        account.setAccountStatus("registered");
        account.setLoginStatus("offline");
        account.setLastLoginTime(null);
        account.setLastError(null);
        saveAccount(account);

        TicketPhonePlatformRelation targetRelation = relation == null ? new TicketPhonePlatformRelation() : relation;
        targetRelation.setPhoneId(phone.getPhoneId());
        targetRelation.setPlatformId(platform.getPlatformId());
        targetRelation.setAccountId(account.getAccountId());
        targetRelation.setStatus("registered");
        targetRelation.setLastError(null);
        targetRelation.setLastOperateTime(new Date());
        saveRelation(targetRelation);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("platformId", platform.getPlatformId());
        auditPayload.put("phoneId", phone.getPhoneId());
        auditPayload.put("accountId", account.getAccountId());
        auditPayload.put("email", account.getEmail());
        recordAudit("account", "create", "account", String.valueOf(account.getAccountId()), "success", "账号已创建", auditPayload);
        return 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateManagedAccount(TicketManagedAccountUpdateBo bo) {
        TicketManagedAccount account = accountMapper.selectById(bo.getAccountId());
        if (account == null) {
            throw new ServiceException("账号不存在");
        }

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

        boolean newlyLoggedIn = "logged_in".equals(loginStatus) && !"logged_in".equals(account.getLoginStatus());
        String accountInfo = normalizeJsonText(bo.getAccountInfo());
        String reqData = normalizeJsonText(bo.getReqData());
        String loginReqData = normalizeJsonText(bo.getLoginReqData());
        String lastError = StringUtils.isBlank(bo.getLastError()) ? null : StringUtils.trim(bo.getLastError());
        account.setEmail(email);
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

        TicketPhonePlatformRelation relation = account.getPhoneId() == null ? null : getRelation(account.getPlatformId(), account.getPhoneId());
        if (relation != null) {
            relation.setAccountId(account.getAccountId());
            relation.setStatus(resolveRelationStatus(accountStatus, loginStatus));
            relation.setLastError(account.getLastError());
            relation.setLastOperateTime(new Date());
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

        int rows = accountMapper.deleteByIds(ids);
        recordAudit("account", "remove", "account", ids.toString(), "success", "账号已删除", ids);
        return rows;
    }

    @Override
    public TableDataInfo<TicketManagedAccountVo> selectLoginableAccountPage(Long platformId, TicketManagedAccountBo bo, PageQuery pageQuery) {
        List<Long> availablePhoneIds = phoneMapper.selectList(new LambdaQueryWrapper<TicketPhoneNumber>()
                .select(TicketPhoneNumber::getPhoneId)
                .eq(TicketPhoneNumber::getStatus, "available"))
            .stream()
            .map(TicketPhoneNumber::getPhoneId)
            .toList();
        if (CollUtil.isEmpty(availablePhoneIds)) {
            return TableDataInfo.build(new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize()));
        }
        LambdaQueryWrapper<TicketManagedAccount> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TicketManagedAccount::getPlatformId, platformId)
            .eq(ObjectUtil.isNotNull(bo.getAccountId()), TicketManagedAccount::getAccountId, bo.getAccountId())
            .like(StringUtils.isNotBlank(bo.getEmail()), TicketManagedAccount::getEmail, bo.getEmail())
            .eq(StringUtils.isNotBlank(bo.getLoginStatus()), TicketManagedAccount::getLoginStatus, bo.getLoginStatus())
            .in(TicketManagedAccount::getPhoneId, availablePhoneIds)
            .in(TicketManagedAccount::getAccountStatus, List.of("activated", "registered"))
            .orderByDesc(TicketManagedAccount::getAccountId);
        Page<TicketManagedAccountVo> page = accountMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichAccounts(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Long> loginAccounts(Long platformId, TicketBatchLoginBo bo) {
        TicketPlatformConfig platform = requirePlatform(platformId);
        List<TicketManagedAccount> accounts = loadAccountsForLogin(platformId, bo);
        if (CollUtil.isEmpty(accounts)) {
            return R.warn("没有可登录的账号");
        }

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

        Long userId = LoginHelper.getUserId();
        List<Long> accountIds = accounts.stream().map(TicketManagedAccount::getAccountId).toList();
        scheduledExecutorService.execute(() -> processLoginBatch(batch.getBatchId(), platform, accountIds, userId));

        recordAudit("login", "startBatch", "loginBatch", String.valueOf(batch.getBatchId()), "success", "平台登录任务已启动", batch);
        return R.ok("登录批次已启动", batch.getBatchId());
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
        refreshActiveSaleTaskStatuses();
        LambdaQueryWrapper<TicketSaleTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketSaleTask::getPlatformId, bo.getPlatformId())
            .eq(StringUtils.isNotBlank(bo.getPurchaseType()), TicketSaleTask::getPurchaseType, bo.getPurchaseType())
            .like(StringUtils.isNotBlank(bo.getTaskName()), TicketSaleTask::getTaskName, bo.getTaskName())
            .eq(StringUtils.isNotBlank(bo.getTaskStatus()), TicketSaleTask::getTaskStatus, bo.getTaskStatus())
            .orderByDesc(TicketSaleTask::getTaskId);
        Page<TicketSaleTaskVo> page = saleTaskMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichSaleTasks(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public TicketSaleTaskVo selectSaleTaskById(Long taskId) {
        refreshSaleTaskStatus(taskId);
        TicketSaleTaskVo vo = saleTaskMapper.selectVoById(taskId);
        if (vo != null) {
            enrichSaleTasks(List.of(vo));
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveSaleTask(TicketSaleTaskBo bo) {
        TicketPlatformConfig platform = requirePlatform(bo.getPlatformId());
        validateSaleTaskAccounts(bo.getPlatformId(), bo.getAccountIds());
        TicketSaleTask entity = MapstructUtils.convert(bo, TicketSaleTask.class);
        normalizeSaleTask(entity);
        normalizePlatformTaskOptions(platform, entity);
        entity.setScheduleVersion(1L);
        entity.setTaskStatus("draft");
        entity.setLastExecutedTime(null);
        int rows = saleTaskMapper.insert(entity);
        saveSaleTaskAccounts(entity.getTaskId(), bo.getAccountIds());
        planSaleTaskSchedule(entity.getTaskId(), LoginHelper.getUserId(), "save", false);
        recordAudit("saleTask", "create", "saleTask", String.valueOf(entity.getTaskId()), "success", "商品抢购任务已创建", bo);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateSaleTask(TicketSaleTaskBo bo) {
        TicketPlatformConfig platform = requirePlatform(bo.getPlatformId());
        validateSaleTaskAccounts(bo.getPlatformId(), bo.getAccountIds());
        TicketSaleTask existing = saleTaskMapper.selectById(bo.getTaskId());
        if (existing == null) {
            throw new ServiceException("商品抢购任务不存在");
        }
        TicketSaleTask entity = MapstructUtils.convert(bo, TicketSaleTask.class);
        normalizeSaleTask(entity);
        normalizePlatformTaskOptions(platform, entity);
        entity.setScheduleVersion(nextScheduleVersion(existing.getScheduleVersion()));
        entity.setTaskStatus("draft");
        entity.setLastExecutedTime(null);
        int rows = saleTaskMapper.updateById(entity);
        saveSaleTaskAccounts(entity.getTaskId(), bo.getAccountIds());
        planSaleTaskSchedule(entity.getTaskId(), LoginHelper.getUserId(), "update", false);
        recordAudit("saleTask", "update", "saleTask", String.valueOf(entity.getTaskId()), "success", "商品抢购任务已更新", bo);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeSaleTasks(Long[] taskIds) {
        cleanupPendingSaleTaskSchedules(Arrays.asList(taskIds), "任务已删除，执行计划已取消");
        saleTaskAccountMapper.deleteByTaskIdsPhysical(Arrays.asList(taskIds));
        int rows = saleTaskMapper.deleteByIds(Arrays.asList(taskIds));
        recordAudit("saleTask", "remove", "saleTask", Arrays.toString(taskIds), "success", "商品抢购任务已删除", taskIds);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Long> executeSaleTask(Long taskId) {
        Long executionId = executeSaleTaskInternal(taskId, LoginHelper.getUserId(), "manual");
        return R.ok("商品抢购任务已重新排队，系统将立即执行", executionId);
    }

    private Long executeSaleTaskInternal(Long taskId, Long operatorUserId, String triggerSource) {
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("商品抢购任务不存在");
        }
        task.setScheduleVersion(nextScheduleVersion(task.getScheduleVersion()));
        task.setTaskStatus("draft");
        task.setLastExecutedTime(null);
        saleTaskMapper.updateById(task);
        return planSaleTaskSchedule(taskId, operatorUserId, triggerSource, true);
    }

    private Long planSaleTaskSchedule(Long taskId, Long operatorUserId, String triggerSource, boolean forceImmediate) {
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("商品抢购任务不存在");
        }
        TicketPlatformConfig platform = requirePlatform(task.getPlatformId());
        List<TicketManagedAccount> accounts = loadSaleTaskAccounts(task);
        invalidateQueuedExecutions(taskId, "任务计划已重排，请以最新调度为准");
        if (TicketOrderFlowSupport.isLottery(task.getPurchaseType())) {
            log.info("skip lottery dispatch before executor implementation, taskId={}, scheduleVersion={}", taskId, task.getScheduleVersion());
            task.setTaskStatus("draft");
            saleTaskMapper.updateById(task);
            Map<String, Object> auditPayload = new LinkedHashMap<>();
            auditPayload.put("taskId", taskId);
            auditPayload.put("platformId", task.getPlatformId());
            auditPayload.put("purchaseType", task.getPurchaseType());
            auditPayload.put("triggerSource", triggerSource);
            recordAudit("saleTask", "schedule", "saleTask", String.valueOf(taskId), "warn", "抽票执行链路暂未实现，任务仅保存配置", auditPayload);
            return 0L;
        }
        if (CollUtil.isEmpty(accounts)) {
            TicketOrderExecution execution = new TicketOrderExecution();
            execution.setTaskId(taskId);
            execution.setPlatformId(task.getPlatformId());
            execution.setPurchaseType(task.getPurchaseType());
            execution.setPurchaseQuantity(task.getPurchaseQuantity());
            execution.setConfigSnapshot(task.getTaskOptions());
            execution.setScheduleVersion(defaultScheduleVersion(task.getScheduleVersion()));
            execution.setCurrentStep("completed");
            execution.setStepStatus("failed");
            execution.setPaymentStatus(TicketOrderFlowSupport.initialPaymentStatus(task.getPurchaseType(), TicketOrderFlowSupport.parseTaskOptions(task.getTaskOptions())));
            execution.setExecutionStatus("blocked");
            execution.setStepTrace("[]");
            execution.setResultMessage("没有可执行的已登录账号");
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
            recordAudit("saleTask", "schedule", "saleTask", String.valueOf(taskId), "warn", "商品抢购任务无可用账号", auditPayload);
            return execution.getExecutionId();
        }

        Date now = new Date();
        Date dispatchTime = resolveTaskDispatchTime(task, forceImmediate);
        List<TicketOrderExecution> executions = new ArrayList<>();
        for (TicketManagedAccount account : accounts) {
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
            execution.setPaymentStatus(TicketOrderFlowSupport.initialPaymentStatus(task.getPurchaseType(), TicketOrderFlowSupport.parseTaskOptions(task.getTaskOptions())));
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
        task.setTaskStatus(dispatchTime.after(now) ? "draft" : "executing");
        task.setLastExecutedTime(dispatchTime.after(now) ? null : now);
        saleTaskMapper.updateById(task);

        registerDispatchAfterCommit(task, platform, accounts, executions, operatorUserId, triggerSource, forceImmediate);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("taskId", taskId);
        auditPayload.put("platformId", task.getPlatformId());
        auditPayload.put("purchaseType", task.getPurchaseType());
        auditPayload.put("accountCount", accounts.size());
        auditPayload.put("triggerSource", triggerSource);
        auditPayload.put("dispatchAt", dispatchTime);
        recordAudit("saleTask", "schedule", "saleTask", String.valueOf(taskId), "success", "商品抢购任务已排队", auditPayload);
        return executions.get(0).getExecutionId();
    }

    @Override
    public TableDataInfo<TicketOrderExecutionVo> selectOrderExecutionPage(TicketOrderExecutionBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketOrderExecution> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ObjectUtil.isNotNull(bo.getTaskId()), TicketOrderExecution::getTaskId, bo.getTaskId())
            .eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketOrderExecution::getPlatformId, bo.getPlatformId())
            .eq(ObjectUtil.isNotNull(bo.getAccountId()), TicketOrderExecution::getAccountId, bo.getAccountId())
            .eq(StringUtils.isNotBlank(bo.getPurchaseType()), TicketOrderExecution::getPurchaseType, bo.getPurchaseType())
            .like(StringUtils.isNotBlank(bo.getOrderNo()), TicketOrderExecution::getOrderNo, bo.getOrderNo())
            .eq(StringUtils.isNotBlank(bo.getExecutionStatus()), TicketOrderExecution::getExecutionStatus, bo.getExecutionStatus())
            .eq(StringUtils.isNotBlank(bo.getPaymentStatus()), TicketOrderExecution::getPaymentStatus, bo.getPaymentStatus())
            .orderByDesc(TicketOrderExecution::getExecutionId);
        Page<TicketOrderExecutionVo> page = orderExecutionMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichOrderExecutions(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markOrderExecutionPaid(Long executionId, TicketOrderExecutionPaymentBo bo) {
        TicketOrderExecution execution = orderExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new ServiceException("下单执行记录不存在");
        }
        if (!EXECUTION_PAYMENT_PENDING_STATUSES.contains(execution.getExecutionStatus())) {
            throw new ServiceException("当前执行状态不允许标记已支付");
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

        TicketMailReaderService.MailReadResult mail;
        if (mailbox != null) {
            try {
                mail = switch (StringUtils.blankToDefault(expectedParseType, "")) {
                    case "verify_code" -> ticketMailReaderService.readLatestVerifyCodeForMailbox(mailbox.getUsername(), mailbox.getPassword());
                    case "activation_url" -> ticketMailReaderService.readLatestActivationUrlForMailbox(mailbox.getUsername(), mailbox.getPassword());
                    default -> ticketMailReaderService.readLatestForMailbox(mailbox.getUsername(), mailbox.getPassword());
                };
            } catch (ServiceException e) {
                mail = readCachedMailboxEmailResult(mailbox, expectedParseType);
                if (mail == null) {
                    return R.fail(e.getMessage());
                }
            }
        } else {
            return R.fail("邮箱账号池不存在该邮箱: " + account.getEmail());
        }

        if (!mail.isParsed()) {
            TicketMailReaderService.MailReadResult cachedMail = readCachedMailboxEmailResult(mailbox, expectedParseType);
            if (cachedMail != null) {
                mail = cachedMail;
            }
        }

        updateAccountLatestMail(account.getAccountId(), mail);

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
        return mailboxAccountMapper.selectOne(new LambdaQueryWrapper<TicketMailboxAccount>()
            .eq(TicketMailboxAccount::getEmail, email)
            .orderByDesc(TicketMailboxAccount::getLastMailSyncTime)
            .orderByDesc(TicketMailboxAccount::getMailboxId)
            .last("limit 1"));
    }

    private TicketMailReaderService.MailReadResult readCachedMailboxEmailResult(TicketMailboxAccount mailbox, String expectedParseType) {
        if (mailbox == null) {
            return null;
        }

        String activationUrl = mailbox.getLatestActivationUrl();
        String verifyCode = mailbox.getLatestVerifyCode();
        if ("activation_url".equals(expectedParseType) && StringUtils.isBlank(activationUrl)) {
            return null;
        }
        if ("verify_code".equals(expectedParseType) && StringUtils.isBlank(verifyCode)) {
            return null;
        }
        if (StringUtils.isBlank(expectedParseType) && StringUtils.isBlank(activationUrl) && StringUtils.isBlank(verifyCode)) {
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

    private void updateAccountLatestMail(Long accountId, TicketMailReaderService.MailReadResult mail) {
        accountMapper.update(null, new LambdaUpdateWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getAccountId, accountId)
            .set(TicketManagedAccount::getLatestVerifyCode, mail.getVerifyCode())
            .set(TicketManagedAccount::getLatestActivationUrl, mail.getActivationUrl())
            .set(TicketManagedAccount::getLatestMailSubject, mail.getSubject())
            .set(TicketManagedAccount::getLatestMailReceivedAt, mail.getReceivedAt())
            .set(TicketManagedAccount::getLatestMailMessageId, mail.getMessageId()));
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
        detail.setResultMessage(resultMessage);
        detail.setReqData(reqData);
        detail.setExecutedAt(new Date());
        if (detail.getDetailId() == null) {
            loginBatchDetailMapper.insert(detail);
        } else {
            loginBatchDetailMapper.updateById(detail);
        }
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
        detail.setResultMessage(resultMessage);
        detail.setAccountId(accountId);
        detail.setEmail(email);
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
        List<TicketManagedAccount> accounts;
        if (CollUtil.isNotEmpty(bo.getAccountIds())) {
            accounts = accountMapper.selectByIds(bo.getAccountIds()).stream()
                .filter(account -> Objects.equals(account.getPlatformId(), platformId))
                .filter(account -> "registered".equals(account.getAccountStatus()))
                .toList();
        } else {
            LambdaQueryWrapper<TicketManagedAccount> wrapper = Wrappers.lambdaQuery();
            wrapper.eq(TicketManagedAccount::getPlatformId, platformId)
                .eq(TicketManagedAccount::getAccountStatus, "registered")
                .eq(StringUtils.isNotBlank(bo.getLoginStatus()), TicketManagedAccount::getLoginStatus, bo.getLoginStatus())
                .orderByDesc(TicketManagedAccount::getAccountId);
            accounts = accountMapper.selectList(wrapper);
        }
        if (CollUtil.isEmpty(accounts)) {
            return accounts;
        }
        Set<Long> availablePhoneIds = phoneMapper.selectList(new LambdaQueryWrapper<TicketPhoneNumber>()
                .select(TicketPhoneNumber::getPhoneId)
                .in(TicketPhoneNumber::getPhoneId, accounts.stream().map(TicketManagedAccount::getPhoneId).filter(Objects::nonNull).toList())
                .eq(TicketPhoneNumber::getStatus, "available"))
            .stream()
            .map(TicketPhoneNumber::getPhoneId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        return accounts.stream()
            .filter(account -> availablePhoneIds.contains(account.getPhoneId()))
            .toList();
    }

    private TicketPhonePlatformRelation getRelation(Long platformId, Long phoneId) {
        return relationMapper.selectOne(new LambdaQueryWrapper<TicketPhonePlatformRelation>()
            .eq(TicketPhonePlatformRelation::getPlatformId, platformId)
            .eq(TicketPhonePlatformRelation::getPhoneId, phoneId), false);
    }

    private String normalizeJsonText(String value) {
        String normalized = StringUtils.trim(value);
        return StringUtils.isBlank(normalized) ? null : normalized;
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
        if (relation.getRelationId() == null) {
            relationMapper.insert(relation);
        } else {
            relationMapper.updateById(relation);
            if (relation.getLastError() == null) {
                relationMapper.update(
                    null,
                    Wrappers.<TicketPhonePlatformRelation>lambdaUpdate()
                        .eq(TicketPhonePlatformRelation::getRelationId, relation.getRelationId())
                        .set(TicketPhonePlatformRelation::getLastError, null)
                );
            }
        }
    }

    private void saveAccount(TicketManagedAccount account) {
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
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketManagedAccountVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        Map<Long, TicketPhoneNumber> phoneMap = loadMap(rows.stream().map(TicketManagedAccountVo::getPhoneId).filter(Objects::nonNull).toList(), phoneMapper::selectByIds, TicketPhoneNumber::getPhoneId);
        for (TicketManagedAccountVo row : rows) {
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
            TicketPhoneNumber phone = phoneMap.get(row.getPhoneId());
            if (phone != null) {
                row.setPhoneNumber(phone.getPhoneNumber());
            }
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

    private void enrichSaleTasks(List<TicketSaleTaskVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        rows.forEach(this::normalizeSaleTaskView);
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketSaleTaskVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        List<Long> taskIds = rows.stream().map(TicketSaleTaskVo::getTaskId).filter(Objects::nonNull).toList();
        Map<Long, List<TicketSaleTaskAccount>> bindingMap = saleTaskAccountMapper.selectList(new LambdaQueryWrapper<TicketSaleTaskAccount>()
                .in(CollUtil.isNotEmpty(taskIds), TicketSaleTaskAccount::getTaskId, taskIds)
                .orderByAsc(TicketSaleTaskAccount::getBindingId))
            .stream()
            .collect(Collectors.groupingBy(TicketSaleTaskAccount::getTaskId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, TicketManagedAccount> accountMap = loadMap(bindingMap.values().stream()
            .flatMap(Collection::stream)
            .map(TicketSaleTaskAccount::getAccountId)
            .filter(Objects::nonNull)
            .distinct()
            .toList(), accountMapper::selectByIds, TicketManagedAccount::getAccountId);
        for (TicketSaleTaskVo row : rows) {
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
            List<TicketSaleTaskAccount> bindings = bindingMap.getOrDefault(row.getTaskId(), List.of());
            List<Long> accountIds = bindings.stream().map(TicketSaleTaskAccount::getAccountId).filter(Objects::nonNull).toList();
            List<String> emails = accountIds.stream()
                .map(accountMap::get)
                .filter(Objects::nonNull)
                .map(TicketManagedAccount::getEmail)
                .filter(StringUtils::isNotBlank)
                .toList();
            row.setAccountIds(accountIds);
            row.setBoundAccountCount(accountIds.size());
            row.setAccountEmails(CollUtil.isEmpty(emails) ? null : String.join(" / ", emails));
        }
    }

    private void enrichOrderExecutions(List<TicketOrderExecutionVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketOrderExecutionVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        Map<Long, TicketManagedAccount> accountMap = loadMap(rows.stream().map(TicketOrderExecutionVo::getAccountId).filter(Objects::nonNull).toList(), accountMapper::selectByIds, TicketManagedAccount::getAccountId);
        Map<Long, TicketSaleTask> taskMap = loadMap(rows.stream().map(TicketOrderExecutionVo::getTaskId).filter(Objects::nonNull).toList(), saleTaskMapper::selectByIds, TicketSaleTask::getTaskId);
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
        }
    }

    private void validateSaleTaskAccounts(Long platformId, List<Long> accountIds) {
        if (ObjectUtil.isNull(platformId)) {
            throw new ServiceException("请选择目标平台");
        }
        if (CollUtil.isEmpty(accountIds)) {
            throw new ServiceException("请至少绑定一个已登录账号");
        }
        List<TicketManagedAccount> accounts = accountMapper.selectByIds(CollUtil.distinct(accountIds));
        if (accounts.size() != CollUtil.distinct(accountIds).size()) {
            throw new ServiceException("绑定账号中包含不存在的账号");
        }
        boolean hasInvalidAccount = accounts.stream().anyMatch(account ->
            !Objects.equals(account.getPlatformId(), platformId)
                || !List.of("registered", "activated").contains(account.getAccountStatus())
                || !"logged_in".equals(account.getLoginStatus())
                || StringUtils.isBlank(account.getLoginReqData())
        );
        if (hasInvalidAccount) {
            throw new ServiceException("只能绑定目标平台下已登录且带会话上下文的账号");
        }
    }

    private void saveSaleTaskAccounts(Long taskId, List<Long> accountIds) {
        saleTaskAccountMapper.delete(new LambdaQueryWrapper<TicketSaleTaskAccount>()
            .eq(TicketSaleTaskAccount::getTaskId, taskId));
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
        return accountMapper.selectByIds(accountIds).stream()
            .filter(account -> Objects.equals(account.getPlatformId(), task.getPlatformId()))
            .filter(account -> List.of("registered", "activated").contains(account.getAccountStatus()))
            .filter(account -> "logged_in".equals(account.getLoginStatus()))
            .filter(account -> StringUtils.isNotBlank(account.getLoginReqData()))
            .sorted(Comparator.comparing(TicketManagedAccount::getAccountId))
            .toList();
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
                recordAudit("saleTask", "dispatch", "orderExecution", String.valueOf(execution.getExecutionId()), "failed", "商品抢购任务调度失败", failedAuditPayload);
            }
        }
        refreshSaleTaskStatus(task.getTaskId());
        Map<String, Object> dispatchAuditPayload = new LinkedHashMap<>();
        dispatchAuditPayload.put("taskId", task.getTaskId());
        dispatchAuditPayload.put("executionCount", executions.size());
        dispatchAuditPayload.put("operator", userId);
        dispatchAuditPayload.put("triggerSource", triggerSource);
        dispatchAuditPayload.put("forceImmediate", forceImmediate);
        recordAudit("saleTask", "dispatch", "saleTask", String.valueOf(task.getTaskId()), "success", "商品抢购任务已写入执行队列", dispatchAuditPayload);
    }

    @Scheduled(initialDelay = 5000L, fixedDelay = 5000L)
    public void refreshSaleTaskExecutionStates() {
        try {
            markTimeoutExecutions();
            refreshActiveSaleTaskStatuses();
        } catch (Exception ex) {
            // 定时刷新只负责兜底汇总，不应影响主业务线程。
        }
    }

    private void refreshActiveSaleTaskStatuses() {
        List<TicketSaleTask> tasks = saleTaskMapper.selectList(new LambdaQueryWrapper<TicketSaleTask>()
            .in(TicketSaleTask::getTaskStatus, List.of("executing", "pending_payment", "partial", "draft")));
        for (TicketSaleTask task : tasks) {
            refreshSaleTaskStatus(task.getTaskId(), task);
        }
    }

    private void markTimeoutExecutions() {
        long heartbeatTimeoutSeconds = Math.max(ticketOrderExecutorProperties.getHeartbeatTimeoutSeconds(), 5L);
        Date cutoff = new Date(System.currentTimeMillis() - heartbeatTimeoutSeconds * 1000L);
        List<TicketOrderExecution> staleExecutions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getExecutionStatus, "running")
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
                .set(TicketOrderExecution::getStepStatus, "failed")
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
        if (task.getPurchaseQuantity() == null || task.getPurchaseQuantity() <= 0) {
            task.setPurchaseQuantity(1);
        }
        if (StringUtils.isBlank(task.getTaskOptions())) {
            task.setTaskOptions("{}");
        } else if (!JSONUtil.isTypeJSON(task.getTaskOptions())) {
            throw new ServiceException("平台扩展参数必须是合法 JSON");
        }
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
        if (TicketOrderFlowSupport.isFlashSale(task.getPurchaseType()) && "livepocket".equalsIgnoreCase(StringUtils.defaultString(platform.getAdapterType()))) {
            String ticketsPageUrl = ObjectUtil.defaultIfNull(Convert.toStr(mergedOptions.get("ticketsPageUrl")), "").trim();
            if (StringUtils.isBlank(ticketsPageUrl)) {
                throw new ServiceException("LivePocket 抢票任务必须配置 ticketsPageUrl");
            }
            mergedOptions.put("ticketsPageUrl", ticketsPageUrl);
        }
        if (ObjectUtil.isNull(mergedOptions.get("ticketQuantity")) || Convert.toInt(mergedOptions.get("ticketQuantity"), 0) <= 0) {
            mergedOptions.put("ticketQuantity", ObjectUtil.defaultIfNull(task.getPurchaseQuantity(), 1));
        }
        task.setConfigSchemaKey(template.getConfigSchemaKey());
        task.setTaskOptions(JSONUtil.toJsonStr(mergedOptions));
    }

    private void normalizeSaleTaskView(TicketSaleTaskVo row) {
        row.setPurchaseType(TicketOrderFlowSupport.defaultPurchaseType(row.getPurchaseType()));
        if (StringUtils.isBlank(row.getTaskOptions())) {
            row.setTaskOptions("{}");
        }
        if (StringUtils.isBlank(row.getConfigSchemaKey()) && ObjectUtil.isNotNull(row.getPlatformId())) {
            TicketPlatformConfig platform = platformMapper.selectById(row.getPlatformId());
            if (platform != null) {
                row.setConfigSchemaKey(TicketOrderFlowSupport.resolveConfigSchemaKey(platform, row.getPurchaseType()));
            }
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
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getTaskId, taskId)
            .eq(TicketOrderExecution::getScheduleVersion, defaultScheduleVersion(task.getScheduleVersion()))
            .orderByAsc(TicketOrderExecution::getExecutionId));
        if (CollUtil.isEmpty(executions)) {
            return;
        }
        String nextStatus = calculateSaleTaskStatus(task, executions);
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
        boolean allPaid = executions.stream().allMatch(item -> "paid".equals(item.getExecutionStatus()));

        if (allPaid) {
            return "paid";
        }
        if (allBlocked) {
            return "blocked";
        }
        if (allFailed) {
            return "failed";
        }
        if (hasPendingPayment && !hasFailure && !hasPaid) {
            return "pending_payment";
        }
        if ((hasPendingPayment || hasPaid) && hasFailure) {
            return "partial";
        }
        if (hasPendingPayment || hasPaid) {
            return hasPaid && !hasPendingPayment ? "paid" : "pending_payment";
        }
        return "failed";
    }

    private void cleanupPendingSaleTaskSchedules(List<Long> taskIds, String message) {
        if (CollUtil.isEmpty(taskIds)) {
            return;
        }
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

    private void blockQueuedExecutions(List<TicketOrderExecution> executions, String message) {
        if (CollUtil.isEmpty(executions)) {
            return;
        }
        Date now = new Date();
        for (TicketOrderExecution execution : executions) {
            log.warn("block queued execution, executionId={}, taskId={}, reason={}", execution.getExecutionId(), execution.getTaskId(), message);
            ticketOrderExecutorClient.removeDelayedExecution(execution.getExecutionId());
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

    private Date resolveTaskDispatchTime(TicketSaleTask task, boolean forceImmediate) {
        if (forceImmediate) {
            return new Date();
        }
        long now = System.currentTimeMillis();
        Long scheduledAt = task.getScheduledTime() == null ? null : task.getScheduledTime().getTime();
        Long warmupAt = task.getWarmupTime() == null ? null : task.getWarmupTime().getTime();
        if (warmupAt != null && warmupAt > now && (scheduledAt == null || warmupAt < scheduledAt)) {
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
}
