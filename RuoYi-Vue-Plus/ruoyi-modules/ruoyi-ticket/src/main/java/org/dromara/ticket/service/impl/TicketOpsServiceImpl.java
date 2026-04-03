package org.dromara.ticket.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
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
import org.dromara.ticket.domain.*;
import org.dromara.ticket.domain.bo.*;
import org.dromara.ticket.domain.dto.TicketLoginProgressMessage;
import org.dromara.ticket.domain.dto.TicketRegisterProgressMessage;
import org.dromara.ticket.domain.vo.*;
import org.dromara.ticket.mapper.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.dromara.ticket.service.ITicketOpsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketOpsServiceImpl implements ITicketOpsService {

    private static final Set<String> ACTIVE_RELATION_STATUSES = Set.of("registered", "logged_in", "registering", "verification_pending");
    private static final Set<String> ENABLED_PHONE_STATUSES = Set.of("available", "disabled");
    private static final Set<String> RUNNING_REGISTER_RELATION_STATUSES = Set.of("registering", "verification_pending");

    private final TicketPlatformConfigMapper platformMapper;
    private final TicketPhoneNumberMapper phoneMapper;
    private final TicketPhonePlatformRelationMapper relationMapper;
    private final TicketManagedAccountMapper accountMapper;
    private final TicketRegistrationBatchMapper registrationBatchMapper;
    private final TicketRegistrationBatchDetailMapper registrationBatchDetailMapper;
    private final TicketLoginBatchMapper loginBatchMapper;
    private final TicketLoginBatchDetailMapper loginBatchDetailMapper;
    private final TicketEventConfigMapper eventMapper;
    private final TicketSaleTaskMapper saleTaskMapper;
    private final TicketOrderExecutionMapper orderExecutionMapper;
    private final TicketAuditEventMapper auditEventMapper;
    private final TicketPlatformAdapterRegistry adapterRegistry;
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
    public int savePlatform(TicketPlatformConfigBo bo) {
        TicketPlatformConfig entity = MapstructUtils.convert(bo, TicketPlatformConfig.class);
        if (StringUtils.isBlank(entity.getAdapterType())) {
            entity.setAdapterType("mock");
        }
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
        recordAudit("phone", "bulkImport", "phone", String.valueOf(entities.size()), "success", "鍙风爜鎵归噺瀵煎叆瀹屾垚", resultVo);
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
                details.add(buildDetail(phone.getPhoneId(), "skipped", "鍚屽彿鍚屽钩鍙板凡瀛樺湪鏈夋晥鍏崇郴"));
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
                account.setAccountNo(result.getAccountNo());
                account.setDisplayName(result.getDisplayName());
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
        wrapper.eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketManagedAccount::getPlatformId, bo.getPlatformId())
            .eq(ObjectUtil.isNotNull(bo.getPhoneId()), TicketManagedAccount::getPhoneId, bo.getPhoneId())
            .like(StringUtils.isNotBlank(bo.getAccountNo()), TicketManagedAccount::getAccountNo, bo.getAccountNo())
            .like(StringUtils.isNotBlank(bo.getDisplayName()), TicketManagedAccount::getDisplayName, bo.getDisplayName())
            .eq(StringUtils.isNotBlank(bo.getAccountStatus()), TicketManagedAccount::getAccountStatus, bo.getAccountStatus())
            .eq(StringUtils.isNotBlank(bo.getLoginStatus()), TicketManagedAccount::getLoginStatus, bo.getLoginStatus())
            .orderByDesc(TicketManagedAccount::getAccountId);
        Page<TicketManagedAccountVo> page = accountMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichAccounts(page.getRecords());
        return TableDataInfo.build(page);
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
            .like(StringUtils.isNotBlank(bo.getAccountNo()), TicketManagedAccount::getAccountNo, bo.getAccountNo())
            .like(StringUtils.isNotBlank(bo.getDisplayName()), TicketManagedAccount::getDisplayName, bo.getDisplayName())
            .eq(StringUtils.isNotBlank(bo.getLoginStatus()), TicketManagedAccount::getLoginStatus, bo.getLoginStatus())
            .in(TicketManagedAccount::getPhoneId, availablePhoneIds)
            .eq(TicketManagedAccount::getAccountStatus, "registered")
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
        LambdaQueryWrapper<TicketSaleTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketSaleTask::getPlatformId, bo.getPlatformId())
            .eq(ObjectUtil.isNotNull(bo.getEventId()), TicketSaleTask::getEventId, bo.getEventId())
            .like(StringUtils.isNotBlank(bo.getTaskName()), TicketSaleTask::getTaskName, bo.getTaskName())
            .eq(StringUtils.isNotBlank(bo.getTaskMode()), TicketSaleTask::getTaskMode, bo.getTaskMode())
            .eq(StringUtils.isNotBlank(bo.getTaskStatus()), TicketSaleTask::getTaskStatus, bo.getTaskStatus())
            .orderByDesc(TicketSaleTask::getTaskId);
        Page<TicketSaleTaskVo> page = saleTaskMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichSaleTasks(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public TicketSaleTaskVo selectSaleTaskById(Long taskId) {
        TicketSaleTaskVo vo = saleTaskMapper.selectVoById(taskId);
        if (vo != null) {
            enrichSaleTasks(List.of(vo));
        }
        return vo;
    }

    @Override
    public int saveSaleTask(TicketSaleTaskBo bo) {
        TicketSaleTask entity = MapstructUtils.convert(bo, TicketSaleTask.class);
        if (StringUtils.isBlank(entity.getTaskStatus())) {
            entity.setTaskStatus("draft");
        }
        int rows = saleTaskMapper.insert(entity);
        recordAudit("saleTask", "create", "saleTask", String.valueOf(entity.getTaskId()), "success", "閿€鍞换鍔″凡鍒涘缓", bo);
        return rows;
    }

    @Override
    public int updateSaleTask(TicketSaleTaskBo bo) {
        TicketSaleTask entity = MapstructUtils.convert(bo, TicketSaleTask.class);
        int rows = saleTaskMapper.updateById(entity);
        recordAudit("saleTask", "update", "saleTask", String.valueOf(entity.getTaskId()), "success", "閿€鍞换鍔″凡鏇存柊", bo);
        return rows;
    }

    @Override
    public int removeSaleTasks(Long[] taskIds) {
        int rows = saleTaskMapper.deleteByIds(Arrays.asList(taskIds));
        recordAudit("saleTask", "remove", "saleTask", Arrays.toString(taskIds), "success", "閿€鍞换鍔″凡鍒犻櫎", taskIds);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Long> executeSaleTask(Long taskId) {
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("閿€鍞换鍔′笉瀛樺湪");
        }
        TicketPlatformConfig platform = requirePlatform(task.getPlatformId());
        TicketPlatformAdapter adapter = adapterRegistry.getAdapter(platform.getAdapterType());
        List<TicketManagedAccount> accounts = accountMapper.selectList(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, task.getPlatformId())
            .in(TicketManagedAccount::getLoginStatus, "logged_in", "offline")
            .last("limit 3"));

        Map<String, Object> inventory = adapter.queryInventory(platform, task);
        Map<String, Object> preparedOrder = adapter.prepareOrder(platform, task);
        if (CollUtil.isEmpty(accounts)) {
            TicketOrderExecution execution = new TicketOrderExecution();
            execution.setTaskId(taskId);
            execution.setPlatformId(task.getPlatformId());
            execution.setExecutionStatus("blocked");
            execution.setResultMessage("No available account");
            execution.setExecutedAt(new Date());
            orderExecutionMapper.insert(execution);
            task.setTaskStatus("blocked");
            task.setLastExecutedTime(new Date());
            saleTaskMapper.updateById(task);
            recordAudit("saleTask", "execute", "saleTask", String.valueOf(taskId), "warn", "閿€鍞换鍔℃棤鍙敤璐﹀彿", Map.of("inventory", inventory, "prepared", preparedOrder));
            return R.warn("鏆傛棤鍙敤璐﹀彿", execution.getExecutionId());
        }

        Long lastExecutionId = null;
        for (TicketManagedAccount account : accounts) {
            TicketOrderResult result = adapter.submitOrder(platform, task, account);
            TicketOrderExecution execution = new TicketOrderExecution();
            execution.setTaskId(taskId);
            execution.setPlatformId(task.getPlatformId());
            execution.setAccountId(account.getAccountId());
            execution.setOrderNo(result.getOrderNo());
            execution.setExecutionStatus(result.isSuccess() ? "submitted" : "failed");
            execution.setResultMessage(result.getMessage());
            execution.setExecutedAt(new Date());
            orderExecutionMapper.insert(execution);
            lastExecutionId = execution.getExecutionId();
        }
        task.setTaskStatus("executed");
        task.setLastExecutedTime(new Date());
        saleTaskMapper.updateById(task);
        recordAudit("saleTask", "execute", "saleTask", String.valueOf(taskId), "success", "閿€鍞换鍔″凡鎵ц(mock)", Map.of("inventory", inventory, "prepared", preparedOrder));
        return R.ok("閿€鍞换鍔″凡鎵ц", lastExecutionId);
    }

    @Override
    public TableDataInfo<TicketOrderExecutionVo> selectOrderExecutionPage(TicketOrderExecutionBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketOrderExecution> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ObjectUtil.isNotNull(bo.getTaskId()), TicketOrderExecution::getTaskId, bo.getTaskId())
            .eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketOrderExecution::getPlatformId, bo.getPlatformId())
            .eq(ObjectUtil.isNotNull(bo.getAccountId()), TicketOrderExecution::getAccountId, bo.getAccountId())
            .like(StringUtils.isNotBlank(bo.getOrderNo()), TicketOrderExecution::getOrderNo, bo.getOrderNo())
            .eq(StringUtils.isNotBlank(bo.getExecutionStatus()), TicketOrderExecution::getExecutionStatus, bo.getExecutionStatus())
            .orderByDesc(TicketOrderExecution::getExecutionId);
        Page<TicketOrderExecutionVo> page = orderExecutionMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichOrderExecutions(page.getRecords());
        return TableDataInfo.build(page);
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
        TicketPlatformConfig platform = platformMapper.selectOne(new LambdaQueryWrapper<TicketPlatformConfig>()
            .eq(TicketPlatformConfig::getPlatformCode, platformCode));
        if (platform == null) {
            return R.fail("骞冲彴涓嶅瓨鍦? " + platformCode);
        }
        TicketPlatformAdapter adapter = adapterRegistry.getAdapter(platform.getAdapterType());
        String message = adapter.handleCallback(platform, payload);
        recordAudit("callback", "receive", "platform", platformCode, "success", message, payload);
        return R.ok(message);
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
            recordAudit("registration", "finishBatch", "registrationBatch", String.valueOf(batchId), "success", "骞冲彴娉ㄥ唽浠诲姟瀹屾垚", Map.of(
                "batchId", batchId,
                "successCount", successCount,
                "failedCount", failedCount,
                "skippedCount", skippedCount
            ));
        } catch (Exception ex) {
            updateRegistrationBatch(batchId, "blocked", successCount, failedCount, skippedCount, summary, new Date());
            publishBatchFailed(batchId, platform, userId, ex.getMessage(), successCount, failedCount, skippedCount, processedCount, phoneIds.size());
            recordAudit("registration", "finishBatch", "registrationBatch", String.valueOf(batchId), "failed", "骞冲彴娉ㄥ唽浠诲姟寮傚父缁撴潫", Map.of(
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
                String note = "宸茶烦杩? 璇ュ钩鍙板凡瀛樺湪鏈夋晥娉ㄥ唽鍏崇郴";
                phone.setNote(note);
                phoneMapper.updateById(phone);
                upsertRegistrationDetail(batchId, phoneId, platform.getPlatformId(), "skipped", note, relation.getAccountId(), null);
                return RegistrationProgress.skipped(batchId, platform, phone, note, note);
            }
            phone.setNote(String.format("姝ｅ湪娉ㄥ唽 %s", platform.getPlatformName()));
            phoneMapper.updateById(phone);

            TicketPhonePlatformRelation pendingRelation = relation == null ? new TicketPhonePlatformRelation() : relation;
            pendingRelation.setPhoneId(phoneId);
            pendingRelation.setPlatformId(platform.getPlatformId());
            pendingRelation.setStatus("registering");
            pendingRelation.setLastError(null);
            pendingRelation.setLastOperateTime(new Date());
            saveRelation(pendingRelation);

            upsertRegistrationDetail(batchId, phoneId, platform.getPlatformId(), "processing", "姝ｅ湪娉ㄥ唽", pendingRelation.getAccountId(), null);
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
                account.setAccountNo(result.getAccountNo());
                account.setDisplayName(result.getDisplayName());
                account.setAccountStatus("registered");
                account.setLoginStatus("offline");
                account.setLastError(null);
                saveAccount(account);

                relation.setAccountId(account.getAccountId());
                relation.setStatus("registered");
                relation.setLastError(null);
                relation.setLastOperateTime(new Date());
                saveRelation(relation);

                String note = String.format("宸叉敞鍐?%s%s", platform.getPlatformName(), StringUtils.isNotBlank(account.getAccountNo()) ? " / 璐﹀彿: " + account.getAccountNo() : "");
                currentPhone.setNote(note);
                phoneMapper.updateById(currentPhone);

                upsertRegistrationDetail(batchId, phone.getPhoneId(), platform.getPlatformId(), "success", StringUtils.defaultIfBlank(result.getMessage(), "娉ㄥ唽鎴愬姛"), account.getAccountId(), account.getAccountNo());
                return RegistrationProgress.success(batchId, platform, currentPhone, StringUtils.defaultIfBlank(result.getMessage(), "娉ㄥ唽鎴愬姛"), note, account.getAccountId(), account.getAccountNo());
            }

            String error = adapter.normalizeError(result == null ? "register_result_missing" : result.getMessage());
            relation.setStatus("register_failed");
            relation.setLastError(error);
            relation.setLastOperateTime(new Date());
            saveRelation(relation);
            currentPhone.setNote("娉ㄥ唽澶辫触: " + error);
            phoneMapper.updateById(currentPhone);

            upsertRegistrationDetail(batchId, phone.getPhoneId(), platform.getPlatformId(), "failed", error, relation.getAccountId(), null);
            return RegistrationProgress.failed(batchId, platform, currentPhone, error, currentPhone.getNote(), relation.getAccountId());
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
                upsertLoginDetail(batchId, accountId, platform.getPlatformId(), "failed", "账号不存在", null, null);
                return LoginProgress.failed(batchId, platform, null, null, "账号不存在");
            }

            TicketPhoneNumber phone = account.getPhoneId() == null ? null : phoneMapper.selectById(account.getPhoneId());
            if (!"registered".equals(account.getAccountStatus())) {
                String message = "账号未注册，不允许登录";
                upsertLoginDetail(batchId, accountId, platform.getPlatformId(), "failed", message, null, null);
                return LoginProgress.failed(batchId, platform, account, phone, message);
            }

            if (phone == null || !"available".equals(phone.getStatus())) {
                String message = "号码不可用，不允许登录";
                upsertLoginDetail(batchId, accountId, platform.getPlatformId(), "failed", message, null, null);
                return LoginProgress.failed(batchId, platform, account, phone, message);
            }

            upsertLoginDetail(batchId, accountId, platform.getPlatformId(), "processing", "正在登录", null, null);
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
                currentAccount.setSessionToken(result.getSessionToken());
                currentAccount.setSessionExpireTime(result.getSessionExpireTime());
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
                upsertLoginDetail(batchId, currentAccount.getAccountId(), platform.getPlatformId(), "success", message, result.getSessionToken(), result.getSessionExpireTime());
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

            upsertLoginDetail(batchId, currentAccount.getAccountId(), platform.getPlatformId(), "failed", error, null, null);
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

    private void upsertLoginDetail(Long batchId, Long accountId, Long platformId, String executeStatus, String resultMessage, String sessionToken, Date sessionExpireTime) {
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
        detail.setSessionToken(sessionToken);
        detail.setSessionExpireTime(sessionExpireTime);
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
        message.setAccountNo(progress.getAccountNo());
        message.setPhoneNumber(progress.getPhoneNumber());
        message.setStepStatus(progress.getStepStatus());
        message.setLoginStatus(progress.getLoginStatus());
        message.setLastError(progress.getLastError());
        message.setSessionExpireTime(progress.getSessionExpireTime());
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
        message.setAccountNo(progress.getAccountNo());
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
        message.setMessage("娉ㄥ唽鎵规鎵ц瀹屾垚");
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
        message.setMessage("娉ㄥ唽鎵规寮傚父缁撴潫: " + StringUtils.defaultString(rawMessage, "unknown"));
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

    private void upsertRegistrationDetail(Long batchId, Long phoneId, Long platformId, String executeStatus, String resultMessage, Long accountId, String accountNo) {
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
        detail.setAccountNo(accountNo);
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
        }
    }

    private void saveAccount(TicketManagedAccount account) {
        if (account.getAccountId() == null) {
            accountMapper.insert(account);
        } else {
            accountMapper.updateById(account);
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
                row.setAccountNo(account.getAccountNo());
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
        for (TicketRegistrationBatchDetailVo row : rows) {
            TicketPhoneNumber phone = phoneMap.get(row.getPhoneId());
            if (phone != null) {
                row.setPhoneNumber(phone.getPhoneNumber());
            }
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
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
                row.setAccountNo(account.getAccountNo());
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
        Map<Long, TicketPlatformConfig> platformMap = loadMap(rows.stream().map(TicketSaleTaskVo::getPlatformId).filter(Objects::nonNull).toList(), platformMapper::selectByIds, TicketPlatformConfig::getPlatformId);
        Map<Long, TicketEventConfig> eventMap = loadMap(rows.stream().map(TicketSaleTaskVo::getEventId).filter(Objects::nonNull).toList(), eventMapper::selectByIds, TicketEventConfig::getEventId);
        for (TicketSaleTaskVo row : rows) {
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
            TicketEventConfig event = eventMap.get(row.getEventId());
            if (event != null) {
                row.setEventName(event.getEventName());
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
        for (TicketOrderExecutionVo row : rows) {
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
            TicketManagedAccount account = accountMap.get(row.getAccountId());
            if (account != null) {
                row.setAccountNo(account.getAccountNo());
            }
            TicketSaleTask task = taskMap.get(row.getTaskId());
            if (task != null) {
                row.setTaskName(task.getTaskName());
            }
        }
    }

    private static final class LoginProgress {

        private Long batchId;
        private Long platformId;
        private String platformName;
        private Long accountId;
        private Long phoneId;
        private String accountNo;
        private String phoneNumber;
        private String stepStatus;
        private String loginStatus;
        private String lastError;
        private Date sessionExpireTime;
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
            progress.sessionExpireTime = account == null ? null : account.getSessionExpireTime();
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
            progress.sessionExpireTime = account == null ? null : account.getSessionExpireTime();
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
            progress.accountNo = account == null ? null : account.getAccountNo();
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

        private String getAccountNo() {
            return accountNo;
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

        private Date getSessionExpireTime() {
            return sessionExpireTime;
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
        private String accountNo;
        private String message;
        private TicketPhoneNumber phone;

        private static RegistrationProgress processing(Long batchId, TicketPlatformConfig platform, TicketPhoneNumber phone, String note) {
            RegistrationProgress progress = base(batchId, platform, phone);
            progress.stepStatus = "processing";
            progress.phoneStatus = phone == null ? null : phone.getStatus();
            progress.note = note;
            progress.message = "姝ｅ湪娉ㄥ唽";
            progress.phone = phone;
            return progress;
        }

        private static RegistrationProgress success(Long batchId, TicketPlatformConfig platform, TicketPhoneNumber phone, String message, String note, Long accountId, String accountNo) {
            RegistrationProgress progress = base(batchId, platform, phone);
            progress.stepStatus = "success";
            progress.phoneStatus = phone == null ? null : phone.getStatus();
            progress.note = note;
            progress.accountId = accountId;
            progress.accountNo = accountNo;
            progress.message = message;
            progress.phone = phone;
            return progress;
        }

        private static RegistrationProgress failed(Long batchId, TicketPlatformConfig platform, TicketPhoneNumber phone, String message, String note, Long accountId) {
            RegistrationProgress progress = base(batchId, platform, phone);
            progress.stepStatus = "failed";
            progress.phoneStatus = phone == null ? null : phone.getStatus();
            progress.note = note;
            progress.accountId = accountId;
            progress.message = message;
            progress.phone = phone;
            return progress;
        }

        private static RegistrationProgress skipped(Long batchId, TicketPlatformConfig platform, TicketPhoneNumber phone, String message, String note) {
            RegistrationProgress progress = base(batchId, platform, phone);
            progress.stepStatus = "skipped";
            progress.phoneStatus = phone == null ? null : phone.getStatus();
            progress.note = note;
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

        private String getAccountNo() {
            return accountNo;
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
        TicketAuditEvent auditEvent = new TicketAuditEvent();
        auditEvent.setModuleName(moduleName);
        auditEvent.setActionType(actionType);
        auditEvent.setBusinessType(businessType);
        auditEvent.setBusinessKey(businessKey);
        auditEvent.setAuditStatus(status);
        auditEvent.setMessage(message);
        auditEvent.setPayload(JSONUtil.toJsonStr(payload));
        auditEvent.setEventTime(new Date());
        auditEventMapper.insert(auditEvent);
    }
}
