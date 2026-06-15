package org.dromara.ticket.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.adapter.TicketOrderFlowSupport;
import org.dromara.ticket.config.TicketPythonExecutorProperties;
import org.dromara.ticket.domain.TicketAuditEvent;
import org.dromara.ticket.domain.TicketLotteryBatchTask;
import org.dromara.ticket.domain.TicketLotteryBatchTaskAccount;
import org.dromara.ticket.domain.TicketLotteryBatchTaskItem;
import org.dromara.ticket.domain.TicketLotteryBatchTaskItemSchedule;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketOrderExecution;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.bo.TicketBatchStatusUpdateBo;
import org.dromara.ticket.domain.bo.TicketLotteryBatchTaskBo;
import org.dromara.ticket.domain.bo.TicketLotteryBatchTaskItemBo;
import org.dromara.ticket.domain.bo.TicketLotteryBatchTaskItemScheduleBo;
import org.dromara.ticket.domain.bo.TicketLotteryEventSessionBo;
import org.dromara.ticket.domain.vo.TicketLotteryBatchTaskItemScheduleVo;
import org.dromara.ticket.domain.vo.TicketLotteryBatchTaskItemVo;
import org.dromara.ticket.domain.vo.TicketLotteryBatchTaskProcessVo;
import org.dromara.ticket.domain.vo.TicketLotteryBatchTaskVo;
import org.dromara.ticket.domain.vo.TicketLotteryEventSessionVo;
import org.dromara.ticket.domain.vo.TicketOrderExecutionVo;
import org.dromara.ticket.domain.vo.TicketSaleTaskProcessStepVo;
import org.dromara.ticket.mapper.TicketAuditEventMapper;
import org.dromara.ticket.mapper.TicketLotteryBatchTaskAccountMapper;
import org.dromara.ticket.mapper.TicketLotteryBatchTaskItemMapper;
import org.dromara.ticket.mapper.TicketLotteryBatchTaskItemScheduleMapper;
import org.dromara.ticket.mapper.TicketLotteryBatchTaskMapper;
import org.dromara.ticket.mapper.TicketManagedAccountMapper;
import org.dromara.ticket.mapper.TicketOrderExecutionMapper;
import org.dromara.ticket.mapper.TicketPlatformConfigMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketLotteryBatchTaskService {

    private static final Set<String> LOTTERY_BATCH_TASK_EDITABLE_STATUSES = Set.of("draft");
    private static final Set<String> LOTTERY_BATCH_TASK_EXECUTE_NOW_STATUSES = Set.of("draft");
    private static final Set<String> EXECUTION_RUNNING_STATUSES = Set.of("running");
    private static final Set<String> EXECUTION_FAILURE_STATUSES = Set.of("failed", "blocked", "timeout", "cancelled");
    private static final Set<String> LOTTERY_SUCCESS_STATUSES = Set.of("submitted", "pending_payment", "paid");
    private static final Set<String> LOTTERY_RETRY_PENDING_STATUSES = Set.of("queued", "running");
    private static final int AUDIT_BUSINESS_KEY_MAX_LENGTH = 128;
    private static final int AUDIT_MESSAGE_MAX_LENGTH = 500;

    private final TicketPlatformConfigMapper platformMapper;
    private final TicketManagedAccountMapper accountMapper;
    private final TicketLotteryBatchTaskMapper lotteryBatchTaskMapper;
    private final TicketLotteryBatchTaskAccountMapper lotteryBatchTaskAccountMapper;
    private final TicketLotteryBatchTaskItemMapper lotteryBatchTaskItemMapper;
    private final TicketLotteryBatchTaskItemScheduleMapper lotteryBatchTaskItemScheduleMapper;
    private final TicketOrderExecutionMapper orderExecutionMapper;
    private final TicketAuditEventMapper auditEventMapper;
    private final TicketPythonExecutorProperties ticketPythonExecutorProperties;
    @Qualifier("ticketPythonStringRedisTemplate")
    private final StringRedisTemplate ticketPythonStringRedisTemplate;
    @Qualifier("scheduledExecutorService")
    private final ScheduledExecutorService scheduledExecutorService;

    public TableDataInfo<TicketLotteryBatchTaskVo> selectPage(TicketLotteryBatchTaskBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TicketLotteryBatchTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ObjectUtil.isNotNull(bo.getPlatformId()), TicketLotteryBatchTask::getPlatformId, bo.getPlatformId())
            .like(StrUtil.isNotBlank(bo.getTaskName()), TicketLotteryBatchTask::getTaskName, bo.getTaskName())
            .eq(StrUtil.isNotBlank(bo.getTaskStatus()), TicketLotteryBatchTask::getTaskStatus, bo.getTaskStatus())
            .orderByDesc(TicketLotteryBatchTask::getBatchTaskId);
        Page<TicketLotteryBatchTaskVo> page = lotteryBatchTaskMapper.selectVoPage(pageQuery.build(), wrapper);
        enrichLotteryBatchTasks(page.getRecords());
        return TableDataInfo.build(page);
    }

    public TicketLotteryBatchTaskVo selectById(Long batchTaskId) {
        refreshTaskStatus(batchTaskId);
        TicketLotteryBatchTaskVo vo = lotteryBatchTaskMapper.selectVoById(batchTaskId);
        if (vo != null) {
            enrichLotteryBatchTasks(List.of(vo));
        }
        return vo;
    }

    public TicketLotteryBatchTaskProcessVo selectProcess(Long batchTaskId) {
        TicketLotteryBatchTaskVo task = selectById(batchTaskId);
        if (task == null) {
            throw new ServiceException("批量抽票任务不存在");
        }
        List<TicketLotteryBatchTaskItemVo> items = lotteryBatchTaskItemMapper.selectVoList(new LambdaQueryWrapper<TicketLotteryBatchTaskItem>()
            .eq(TicketLotteryBatchTaskItem::getBatchTaskId, batchTaskId)
            .orderByAsc(TicketLotteryBatchTaskItem::getBatchItemId));
        enrichLotteryBatchTaskItems(items);
        for (TicketLotteryBatchTaskItemVo item : items) {
            List<TicketLotteryBatchTaskItemScheduleVo> schedules = lotteryBatchTaskItemScheduleMapper.selectVoList(
                new LambdaQueryWrapper<TicketLotteryBatchTaskItemSchedule>()
                    .eq(TicketLotteryBatchTaskItemSchedule::getBatchItemId, item.getBatchItemId())
                    .orderByAsc(TicketLotteryBatchTaskItemSchedule::getScheduledTime)
                    .orderByAsc(TicketLotteryBatchTaskItemSchedule::getScheduleId)
            );
            item.setSchedules(schedules);
        }
        TicketLotteryBatchTaskProcessVo vo = new TicketLotteryBatchTaskProcessVo();
        vo.setTask(task);
        vo.setItems(items);
        Map<String, Integer> summary = buildLotteryBatchTaskExecutionSummary(batchTaskId, task.getScheduleVersion());
        vo.setExecutionSummary(summary);
        vo.setCreateSteps(buildLotteryBatchTaskProcessSteps(task, items, summary));
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public int save(TicketLotteryBatchTaskBo bo) {
        TicketPlatformConfig platform = requirePlatform(bo.getPlatformId());
        validateLotteryBatchTaskBo(platform, bo);
        TicketLotteryBatchTask entity = MapstructUtils.convert(bo, TicketLotteryBatchTask.class);
        normalizeLotteryBatchTask(entity);
        entity.setScheduleVersion(1L);
        entity.setTaskStatus("draft");
        int rows = lotteryBatchTaskMapper.insert(entity);
        replaceLotteryBatchTaskChildren(entity, bo);
        planLotteryBatchTaskSchedule(entity, "save", false);
        recordAudit("lotteryBatchTask", "create", "lotteryBatchTask", String.valueOf(entity.getBatchTaskId()), "success", "批量抽票任务已创建", bo);
        return rows;
    }

    @Transactional(rollbackFor = Exception.class)
    public int update(TicketLotteryBatchTaskBo bo) {
        TicketPlatformConfig platform = requirePlatform(bo.getPlatformId());
        validateLotteryBatchTaskBo(platform, bo);
        TicketLotteryBatchTask existing = lotteryBatchTaskMapper.selectById(bo.getBatchTaskId());
        if (existing == null) {
            throw new ServiceException("批量抽票任务不存在");
        }
        assertLotteryBatchTaskEditable(existing);
        TicketLotteryBatchTask entity = MapstructUtils.convert(bo, TicketLotteryBatchTask.class);
        normalizeLotteryBatchTask(entity);
        entity.setScheduleVersion(nextScheduleVersion(existing.getScheduleVersion()));
        entity.setTaskStatus("draft");
        lotteryBatchTaskMapper.updateById(entity);
        invalidateQueuedBatchExecutions(existing.getBatchTaskId(), "批量抽票任务计划已重排，请以最新调度为准");
        replaceLotteryBatchTaskChildren(entity, bo);
        planLotteryBatchTaskSchedule(entity, "update", false);
        recordAudit("lotteryBatchTask", "update", "lotteryBatchTask", String.valueOf(entity.getBatchTaskId()), "success", "批量抽票任务已更新", bo);
        return 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public int updateStatus(Long batchTaskId, TicketBatchStatusUpdateBo bo) {
        TicketLotteryBatchTask task = lotteryBatchTaskMapper.selectById(batchTaskId);
        if (task == null) {
            throw new ServiceException("批量抽票任务不存在");
        }
        String nextStatus = StrUtil.blankToDefault(StrUtil.trim(bo.getBatchStatus()), "");
        if (StrUtil.isBlank(nextStatus)) {
            throw new ServiceException("任务状态不能为空");
        }
        lotteryBatchTaskMapper.update(null, Wrappers.lambdaUpdate(TicketLotteryBatchTask.class)
            .eq(TicketLotteryBatchTask::getBatchTaskId, batchTaskId)
            .set(TicketLotteryBatchTask::getTaskStatus, nextStatus)
            .set(StrUtil.isNotBlank(bo.getRemark()), TicketLotteryBatchTask::getRemark, bo.getRemark()));
        recordAudit("lotteryBatchTask", "updateStatus", "lotteryBatchTask", String.valueOf(batchTaskId), "success", "批量抽票任务状态已更新", bo);
        return 1;
    }

    public R<Long> executeNow(Long batchTaskId) {
        TicketLotteryBatchTask task = lotteryBatchTaskMapper.selectById(batchTaskId);
        if (task == null) {
            throw new ServiceException("批量抽票任务不存在");
        }
        if (!LOTTERY_BATCH_TASK_EXECUTE_NOW_STATUSES.contains(StrUtil.blankToDefault(task.getTaskStatus(), ""))) {
            throw new ServiceException("只有待执行批量抽票任务可以立即执行");
        }
        planLotteryBatchTaskSchedule(task, "manual-now", true);
        recordAudit("lotteryBatchTask", "executeNow", "lotteryBatchTask", String.valueOf(batchTaskId), "success", "批量抽票任务立即执行已提交", Map.of("batchTaskId", batchTaskId));
        return R.ok("批量抽票任务已立即执行", batchTaskId);
    }

    public void enrichBatchOrderExecutions(List<TicketOrderExecutionVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketLotteryBatchTask> batchTaskMap = loadMap(rows.stream().map(TicketOrderExecutionVo::getBatchTaskId).filter(Objects::nonNull).toList(), lotteryBatchTaskMapper::selectByIds, TicketLotteryBatchTask::getBatchTaskId);
        Map<Long, TicketLotteryBatchTaskItem> batchItemMap = loadMap(rows.stream().map(TicketOrderExecutionVo::getBatchItemId).filter(Objects::nonNull).toList(), lotteryBatchTaskItemMapper::selectByIds, TicketLotteryBatchTaskItem::getBatchItemId);
        Map<Long, TicketLotteryBatchTaskItemSchedule> batchScheduleMap = loadMap(rows.stream().map(TicketOrderExecutionVo::getLotteryScheduleId).filter(Objects::nonNull).toList(), lotteryBatchTaskItemScheduleMapper::selectByIds, TicketLotteryBatchTaskItemSchedule::getScheduleId);
        for (TicketOrderExecutionVo row : rows) {
            if (row.getBatchTaskId() == null) {
                continue;
            }
            TicketLotteryBatchTask batchTask = batchTaskMap.get(row.getBatchTaskId());
            if (batchTask != null) {
                row.setTaskName(batchTask.getTaskName());
                if (StrUtil.isBlank(row.getConfigSnapshot())) {
                    row.setConfigSnapshot(batchTask.getTaskOptions());
                }
            }
            TicketLotteryBatchTaskItem batchItem = batchItemMap.get(row.getBatchItemId());
            TicketLotteryBatchTaskItemSchedule batchSchedule = batchScheduleMap.get(row.getLotteryScheduleId());
            enrichBatchLotteryExecutionDisplay(row, batchItem, batchSchedule);
        }
    }

    public void markExecutionRunning(TicketOrderExecution execution, Date now, String message) {
        if (execution == null) {
            return;
        }
        if (execution.getLotteryScheduleId() != null) {
            lotteryBatchTaskItemScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketLotteryBatchTaskItemSchedule.class)
                .eq(TicketLotteryBatchTaskItemSchedule::getScheduleId, execution.getLotteryScheduleId())
                .in(TicketLotteryBatchTaskItemSchedule::getScheduleStatus, List.of("pending", "running"))
                .set(TicketLotteryBatchTaskItemSchedule::getScheduleStatus, "running")
                .set(TicketLotteryBatchTaskItemSchedule::getDispatchedTime, now)
                .set(TicketLotteryBatchTaskItemSchedule::getResultMessage, message));
        }
        if (execution.getBatchTaskId() != null) {
            lotteryBatchTaskMapper.update(null, Wrappers.lambdaUpdate(TicketLotteryBatchTask.class)
                .eq(TicketLotteryBatchTask::getBatchTaskId, execution.getBatchTaskId())
                .set(TicketLotteryBatchTask::getTaskStatus, "executing"));
        }
    }

    public void refreshAfterExecution(TicketOrderExecution execution) {
        if (execution == null) {
            return;
        }
        if (execution.getLotteryScheduleId() != null) {
            refreshItemScheduleStatus(execution.getLotteryScheduleId());
        }
        if (execution.getBatchItemId() != null) {
            refreshItemStatus(execution.getBatchItemId());
        }
        if (execution.getBatchTaskId() != null) {
            refreshTaskStatus(execution.getBatchTaskId());
        }
    }

    public void refreshActiveTaskStatuses() {
        List<TicketLotteryBatchTask> batchTasks = lotteryBatchTaskMapper.selectList(new LambdaQueryWrapper<TicketLotteryBatchTask>()
            .in(TicketLotteryBatchTask::getTaskStatus, List.of("draft", "executing", "partial")));
        for (TicketLotteryBatchTask batchTask : batchTasks) {
            refreshTaskStatus(batchTask.getBatchTaskId());
        }
    }

    @Scheduled(fixedDelay = 10000L, initialDelay = 20000L)
    public void refreshBatchTaskStatusesPeriodically() {
        refreshActiveTaskStatuses();
    }

    private TicketPlatformConfig requirePlatform(Long platformId) {
        if (platformId == null) {
            throw new ServiceException("平台不能为空");
        }
        TicketPlatformConfig platform = platformMapper.selectById(platformId);
        if (platform == null) {
            throw new ServiceException("平台不存在");
        }
        return platform;
    }

    private void normalizeLotteryBatchTask(TicketLotteryBatchTask task) {
        if (task == null) {
            return;
        }
        if (StrUtil.isBlank(task.getTaskStatus())) {
            task.setTaskStatus("draft");
        }
        if (task.getScheduleVersion() == null || task.getScheduleVersion() <= 0) {
            task.setScheduleVersion(1L);
        }
        if (StrUtil.isBlank(task.getTaskOptions())) {
            task.setTaskOptions("{}");
        } else if (!JSONUtil.isTypeJSON(task.getTaskOptions())) {
            throw new ServiceException("批量抽票任务扩展参数必须是合法 JSON");
        }
    }

    private void validateLotteryBatchTaskBo(TicketPlatformConfig platform, TicketLotteryBatchTaskBo bo) {
        if (platform == null || bo == null) {
            throw new ServiceException("批量抽票任务参数不完整");
        }
        if (!"livepocket".equalsIgnoreCase(StrUtil.blankToDefault(platform.getPlatformCode(), ""))) {
            throw new ServiceException("批量抽票任务当前只支持 LivePocket");
        }
        if (StrUtil.isBlank(bo.getSourceUrl()) || !bo.getSourceUrl().contains("/t/")) {
            throw new ServiceException("批量抽票任务只支持 /t/... 集合链接");
        }
        if (CollUtil.isEmpty(bo.getAccountIds())) {
            throw new ServiceException("请至少选择一个账号");
        }
        if (CollUtil.isEmpty(bo.getItems())) {
            throw new ServiceException("请至少选择一个活动并完成配置");
        }
        for (TicketLotteryBatchTaskItemBo item : bo.getItems()) {
            if (item == null) {
                continue;
            }
            if (StrUtil.isBlank(item.getEventUrl()) || !item.getEventUrl().contains("/e/")) {
                throw new ServiceException("批量抽票活动必须是具体的 /e/... 活动链接");
            }
            if (StrUtil.isBlank(item.getReceptionId())) {
                throw new ServiceException("活动 " + StrUtil.blankToDefault(item.getEventTitle(), item.getEventUrl()) + " 缺少受付标识");
            }
            List<TicketLotteryEventSessionBo> selectedSessions = ObjectUtil.defaultIfNull(item.getSelectedSessions(), List.of());
            if (CollUtil.isEmpty(selectedSessions)) {
                throw new ServiceException("活动 " + StrUtil.blankToDefault(item.getEventTitle(), item.getEventUrl()) + " 尚未选择抽选场次");
            }
            if (selectedSessions.stream().anyMatch(session -> StrUtil.isBlank(session.getReceptionId()))) {
                throw new ServiceException("活动 " + StrUtil.blankToDefault(item.getEventTitle(), item.getEventUrl()) + " 的当前场次缺少受付标识，请重新解析活动");
            }
            boolean mixedReception = selectedSessions.stream()
                .map(session -> StrUtil.blankToDefault(session.getReceptionId(), "").trim())
                .anyMatch(receptionId -> !Objects.equals(receptionId, item.getReceptionId()));
            if (mixedReception) {
                throw new ServiceException("活动 " + StrUtil.blankToDefault(item.getEventTitle(), item.getEventUrl()) + " 只能选择同一受付下的场次");
            }
            if (selectedSessions.stream().anyMatch(session -> !StrUtil.contains(StrUtil.blankToDefault(session.getSalesType(), ""), "抽選"))) {
                throw new ServiceException("活动 " + StrUtil.blankToDefault(item.getEventTitle(), item.getEventUrl()) + " 只允许选择抽选受付");
            }
            List<TicketLotteryBatchTaskItemScheduleBo> schedules = ObjectUtil.defaultIfNull(item.getSchedules(), List.of());
            if (CollUtil.isEmpty(schedules)) {
                throw new ServiceException("活动 " + StrUtil.blankToDefault(item.getEventTitle(), item.getEventUrl()) + " 尚未配置分时段");
            }
            Set<String> allowedSessionIds = selectedSessions.stream()
                .map(TicketLotteryEventSessionBo::getSessionId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
            for (TicketLotteryBatchTaskItemScheduleBo schedule : schedules) {
                if (schedule == null || schedule.getScheduledTime() == null) {
                    throw new ServiceException("活动 " + StrUtil.blankToDefault(item.getEventTitle(), item.getEventUrl()) + " 的分时段执行时间不能为空");
                }
                if (schedule.getAccountCount() == null || schedule.getAccountCount() <= 0) {
                    throw new ServiceException("活动 " + StrUtil.blankToDefault(item.getEventTitle(), item.getEventUrl()) + " 的分时段账号数量必须大于0");
                }
                if (StrUtil.isBlank(schedule.getSessionId()) || !allowedSessionIds.contains(schedule.getSessionId())) {
                    throw new ServiceException("活动 " + StrUtil.blankToDefault(item.getEventTitle(), item.getEventUrl()) + " 的分时段引用了未选择的场次");
                }
            }
        }
    }

    private void assertLotteryBatchTaskEditable(TicketLotteryBatchTask task) {
        if (task == null) {
            throw new ServiceException("批量抽票任务不存在");
        }
        if (!LOTTERY_BATCH_TASK_EDITABLE_STATUSES.contains(StrUtil.blankToDefault(task.getTaskStatus(), ""))) {
            throw new ServiceException("当前任务状态不允许编辑");
        }
    }

    private void replaceLotteryBatchTaskChildren(TicketLotteryBatchTask task, TicketLotteryBatchTaskBo bo) {
        Long batchTaskId = task.getBatchTaskId();
        lotteryBatchTaskAccountMapper.deleteByBatchTaskIdsPhysical(List.of(batchTaskId));
        lotteryBatchTaskItemScheduleMapper.deleteByBatchTaskIdsPhysical(List.of(batchTaskId));
        lotteryBatchTaskItemMapper.deleteByBatchTaskIdsPhysical(List.of(batchTaskId));

        List<TicketLotteryBatchTaskAccount> bindings = ObjectUtil.defaultIfNull(bo.getAccountIds(), List.<Long>of()).stream()
            .filter(Objects::nonNull)
            .distinct()
            .map(accountId -> {
                TicketLotteryBatchTaskAccount binding = new TicketLotteryBatchTaskAccount();
                binding.setBatchTaskId(batchTaskId);
                binding.setAccountId(accountId);
                return binding;
            })
            .toList();
        if (CollUtil.isNotEmpty(bindings)) {
            lotteryBatchTaskAccountMapper.insertBatch(bindings);
        }

        JSONObject taskOptions = JSONUtil.parseObj(parseTaskOptionsSafe(task.getTaskOptions()));
        taskOptions.set("mode", "lottery_batch");
        taskOptions.set("sourceUrl", task.getSourceUrl());
        taskOptions.set("activityCount", CollUtil.size(bo.getItems()));
        task.setTaskOptions(taskOptions.toString());
        lotteryBatchTaskMapper.updateById(task);

        for (TicketLotteryBatchTaskItemBo itemBo : bo.getItems()) {
            TicketLotteryBatchTaskItem item = MapstructUtils.convert(itemBo, TicketLotteryBatchTaskItem.class);
            item.setBatchTaskId(batchTaskId);
            item.setItemStatus("draft");
            item.setSelectedSessionsJson(JSONUtil.toJsonStr(
                ObjectUtil.defaultIfNull(itemBo.getSelectedSessions(), List.<TicketLotteryEventSessionBo>of()).stream()
                    .map(this::normalizeLotterySessionBo)
                    .toList()
            ));
            lotteryBatchTaskItemMapper.insert(item);

            List<TicketLotteryBatchTaskItemSchedule> schedules = ObjectUtil.defaultIfNull(itemBo.getSchedules(), List.<TicketLotteryBatchTaskItemScheduleBo>of()).stream()
                .map(scheduleBo -> {
                    TicketLotteryBatchTaskItemSchedule schedule = MapstructUtils.convert(scheduleBo, TicketLotteryBatchTaskItemSchedule.class);
                    schedule.setBatchItemId(item.getBatchItemId());
                    schedule.setScheduleStatus("pending");
                    schedule.setDispatchedTime(null);
                    schedule.setFinishedTime(null);
                    schedule.setResultMessage("等待执行");
                    return schedule;
                })
                .toList();
            if (CollUtil.isNotEmpty(schedules)) {
                lotteryBatchTaskItemScheduleMapper.insertBatch(schedules);
            }
        }
    }

    private Map<String, Object> parseTaskOptionsSafe(String text) {
        return TicketOrderFlowSupport.parseTaskOptions(StrUtil.blankToDefault(text, "{}"));
    }

    private Map<String, Object> normalizeLotterySessionBo(TicketLotteryEventSessionBo session) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sessionId", session.getSessionId());
        item.put("sessionLabel", StrUtil.blankToDefault(session.getSessionLabel(), session.getSessionId()));
        item.put("eventUrl", session.getEventUrl());
        item.put("receptionId", session.getReceptionId());
        item.put("ticketId", session.getTicketId());
        item.put("ticketField", session.getTicketField());
        item.put("receptionTitle", session.getReceptionTitle());
        item.put("salesType", session.getSalesType());
        item.put("notes", session.getNotes());
        return item;
    }

    private void enrichLotteryBatchTasks(List<TicketLotteryBatchTaskVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, TicketPlatformConfig> platformMap = loadMap(
            rows.stream().map(TicketLotteryBatchTaskVo::getPlatformId).filter(Objects::nonNull).toList(),
            platformMapper::selectByIds,
            TicketPlatformConfig::getPlatformId
        );
        Map<Long, List<Long>> accountIdsMap = lotteryBatchTaskAccountMapper.selectList(new LambdaQueryWrapper<TicketLotteryBatchTaskAccount>()
                .in(TicketLotteryBatchTaskAccount::getBatchTaskId, rows.stream().map(TicketLotteryBatchTaskVo::getBatchTaskId).toList()))
            .stream()
            .collect(Collectors.groupingBy(TicketLotteryBatchTaskAccount::getBatchTaskId, LinkedHashMap::new,
                Collectors.mapping(TicketLotteryBatchTaskAccount::getAccountId, Collectors.toList())));
        Map<Long, List<TicketLotteryBatchTaskItemVo>> itemMap = lotteryBatchTaskItemMapper.selectVoList(new LambdaQueryWrapper<TicketLotteryBatchTaskItem>()
                .in(TicketLotteryBatchTaskItem::getBatchTaskId, rows.stream().map(TicketLotteryBatchTaskVo::getBatchTaskId).toList())
                .orderByAsc(TicketLotteryBatchTaskItem::getBatchItemId))
            .stream()
            .collect(Collectors.groupingBy(TicketLotteryBatchTaskItemVo::getBatchTaskId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, Map<String, Integer>> executionSummaryMap = buildLotteryBatchTaskExecutionSummaryMap(rows);
        for (TicketLotteryBatchTaskVo row : rows) {
            TicketPlatformConfig platform = platformMap.get(row.getPlatformId());
            if (platform != null) {
                row.setPlatformName(platform.getPlatformName());
            }
            List<Long> accountIds = accountIdsMap.getOrDefault(row.getBatchTaskId(), List.of());
            row.setAccountIds(accountIds);
            row.setBoundAccountCount(accountIds.size());
            List<TicketLotteryBatchTaskItemVo> items = new ArrayList<>(itemMap.getOrDefault(row.getBatchTaskId(), List.of()));
            enrichLotteryBatchTaskItems(items);
            row.setItems(items);
            row.setExecutionSummary(executionSummaryMap.getOrDefault(row.getBatchTaskId(), Map.of("total", 0)));
        }
    }

    private void enrichLotteryBatchTaskItems(List<TicketLotteryBatchTaskItemVo> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Map<Long, List<TicketLotteryBatchTaskItemScheduleVo>> scheduleMap = lotteryBatchTaskItemScheduleMapper.selectVoList(
                new LambdaQueryWrapper<TicketLotteryBatchTaskItemSchedule>()
                    .in(TicketLotteryBatchTaskItemSchedule::getBatchItemId, rows.stream().map(TicketLotteryBatchTaskItemVo::getBatchItemId).toList())
                    .orderByAsc(TicketLotteryBatchTaskItemSchedule::getScheduledTime)
                    .orderByAsc(TicketLotteryBatchTaskItemSchedule::getScheduleId)
            ).stream()
            .collect(Collectors.groupingBy(TicketLotteryBatchTaskItemScheduleVo::getBatchItemId, LinkedHashMap::new, Collectors.toList()));
        for (TicketLotteryBatchTaskItemVo row : rows) {
            row.setSelectedSessions(parseLotterySessionVos(row.getSelectedSessionsJson()));
            row.setSchedules(new ArrayList<>(scheduleMap.getOrDefault(row.getBatchItemId(), List.of())));
        }
    }

    private Map<String, Integer> buildLotteryBatchTaskExecutionSummary(Long batchTaskId, Long scheduleVersion) {
        List<String> orderedStatuses = List.of("queued", "running", "submitted", "pending_payment", "paid", "blocked", "failed", "cancelled");
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("total", 0);
        orderedStatuses.forEach(status -> summary.put(status, 0));
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .select(TicketOrderExecution::getExecutionId, TicketOrderExecution::getAccountId, TicketOrderExecution::getLotteryScheduleId,
                TicketOrderExecution::getBatchItemId, TicketOrderExecution::getExecutionStatus)
            .eq(TicketOrderExecution::getBatchTaskId, batchTaskId)
            .eq(TicketOrderExecution::getScheduleVersion, defaultScheduleVersion(scheduleVersion))
            .orderByAsc(TicketOrderExecution::getExecutionId));
        effectiveLotteryExecutions(executions).forEach(execution -> countExecutionStatus(summary, execution));
        return summary;
    }

    private Map<Long, Map<String, Integer>> buildLotteryBatchTaskExecutionSummaryMap(List<TicketLotteryBatchTaskVo> tasks) {
        if (CollUtil.isEmpty(tasks)) {
            return Map.of();
        }
        Map<Long, Long> versionMap = tasks.stream()
            .filter(item -> item.getBatchTaskId() != null)
            .collect(Collectors.toMap(TicketLotteryBatchTaskVo::getBatchTaskId, item -> defaultScheduleVersion(item.getScheduleVersion()), (left, right) -> left, LinkedHashMap::new));
        List<Long> batchTaskIds = new ArrayList<>(versionMap.keySet());
        Map<Long, Map<String, Integer>> summaryMap = new HashMap<>();
        Map<Long, List<TicketOrderExecution>> executionMap = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
                .select(TicketOrderExecution::getExecutionId, TicketOrderExecution::getBatchTaskId, TicketOrderExecution::getBatchItemId,
                    TicketOrderExecution::getAccountId, TicketOrderExecution::getLotteryScheduleId, TicketOrderExecution::getExecutionStatus,
                    TicketOrderExecution::getScheduleVersion)
                .in(TicketOrderExecution::getBatchTaskId, batchTaskIds))
            .stream()
            .filter(execution -> {
                Long batchTaskId = execution.getBatchTaskId();
                return batchTaskId != null && Objects.equals(defaultScheduleVersion(execution.getScheduleVersion()), versionMap.get(batchTaskId));
            })
            .collect(Collectors.groupingBy(TicketOrderExecution::getBatchTaskId, LinkedHashMap::new, Collectors.toList()));
        executionMap.forEach((batchTaskId, executions) -> {
            Map<String, Integer> summary = summaryMap.computeIfAbsent(batchTaskId, key -> emptyExecutionSummary());
            effectiveLotteryExecutions(executions).forEach(execution -> countExecutionStatus(summary, execution));
        });
        batchTaskIds.forEach(batchTaskId -> summaryMap.computeIfAbsent(batchTaskId, key -> emptyExecutionSummary()));
        return summaryMap;
    }

    private List<TicketSaleTaskProcessStepVo> buildLotteryBatchTaskProcessSteps(TicketLotteryBatchTaskVo task,
                                                                                List<TicketLotteryBatchTaskItemVo> items,
                                                                                Map<String, Integer> executionSummary) {
        List<TicketSaleTaskProcessStepVo> steps = new ArrayList<>();
        int accountCount = ObjectUtil.defaultIfNull(task.getBoundAccountCount(), 0);
        int itemCount = CollUtil.size(items);
        int scheduleCount = items.stream().map(TicketLotteryBatchTaskItemVo::getSchedules).filter(Objects::nonNull).mapToInt(List::size).sum();
        int executionCount = executionSummary.getOrDefault("total", 0);
        addProcessStep(steps, "source_url", "输入集合链接", StrUtil.blankToDefault(task.getSourceUrl(), "-"), StrUtil.isNotBlank(task.getSourceUrl()), task.getCreateTime());
        addProcessStep(steps, "activities", "选择活动", itemCount > 0 ? "已选择 " + itemCount + " 个活动" : "尚未选择活动", itemCount > 0, task.getCreateTime());
        addProcessStep(steps, "accounts", "统一选择账号", accountCount > 0 ? "已选择 " + accountCount + " 个账号" : "尚未绑定账号", accountCount > 0, task.getCreateTime());
        addProcessStep(steps, "config", "逐活动配置受付和场次", itemCount > 0 ? "已完成 " + itemCount + " 个活动配置" : "尚未完成活动配置", itemCount > 0, task.getCreateTime());
        addProcessStep(steps, "schedules", "保存分时段计划", scheduleCount > 0 ? "已配置 " + scheduleCount + " 个活动时段" : "尚未配置时段", scheduleCount > 0, task.getCreateTime());
        addProcessStep(steps, "executions", "生成执行记录", executionCount > 0 ? "已生成 " + executionCount + " 条执行记录" : "等待触发后生成执行记录", executionCount > 0, task.getUpdateTime());
        return steps;
    }

    private List<TicketManagedAccount> loadLotteryBatchTaskAccounts(TicketLotteryBatchTask task) {
        if (task == null || task.getBatchTaskId() == null) {
            return List.of();
        }
        List<Long> accountIds = lotteryBatchTaskAccountMapper.selectList(new LambdaQueryWrapper<TicketLotteryBatchTaskAccount>()
                .select(TicketLotteryBatchTaskAccount::getAccountId)
                .eq(TicketLotteryBatchTaskAccount::getBatchTaskId, task.getBatchTaskId())
                .orderByAsc(TicketLotteryBatchTaskAccount::getBindingId))
            .stream()
            .map(TicketLotteryBatchTaskAccount::getAccountId)
            .filter(Objects::nonNull)
            .toList();
        if (CollUtil.isEmpty(accountIds)) {
            return List.of();
        }
        Map<Long, TicketManagedAccount> accountMap = loadMap(accountIds, accountMapper::selectByIds, TicketManagedAccount::getAccountId);
        List<TicketManagedAccount> accounts = new ArrayList<>();
        for (Long accountId : accountIds) {
            TicketManagedAccount account = accountMap.get(accountId);
            if (account != null) {
                accounts.add(account);
            }
        }
        return accounts;
    }

    private void planLotteryBatchTaskSchedule(TicketLotteryBatchTask task, String triggerSource, boolean executeNow) {
        List<TicketManagedAccount> accounts = loadLotteryBatchTaskAccounts(task);
        if (CollUtil.isEmpty(accounts)) {
            throw new ServiceException("批量抽票任务没有可执行的账号");
        }
        List<TicketLotteryBatchTaskItem> items = lotteryBatchTaskItemMapper.selectList(new LambdaQueryWrapper<TicketLotteryBatchTaskItem>()
            .eq(TicketLotteryBatchTaskItem::getBatchTaskId, task.getBatchTaskId())
            .orderByAsc(TicketLotteryBatchTaskItem::getBatchItemId));
        if (CollUtil.isEmpty(items)) {
            throw new ServiceException("批量抽票任务没有活动项");
        }
        List<TicketOrderExecution> executions = new ArrayList<>();
        Date now = new Date();
        for (TicketLotteryBatchTaskItem item : items) {
            List<TicketLotteryEventSessionVo> selectedSessions = parseLotterySessionVos(item.getSelectedSessionsJson());
            Map<String, TicketLotteryEventSessionVo> sessionMap = selectedSessions.stream()
                .collect(Collectors.toMap(TicketLotteryEventSessionVo::getSessionId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
            List<TicketLotteryBatchTaskItemSchedule> schedules = lotteryBatchTaskItemScheduleMapper.selectList(new LambdaQueryWrapper<TicketLotteryBatchTaskItemSchedule>()
                .eq(TicketLotteryBatchTaskItemSchedule::getBatchItemId, item.getBatchItemId())
                .orderByAsc(TicketLotteryBatchTaskItemSchedule::getScheduledTime)
                .orderByAsc(TicketLotteryBatchTaskItemSchedule::getScheduleId));
            int requiredAccounts = schedules.stream()
                .map(TicketLotteryBatchTaskItemSchedule::getAccountCount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
            if (accounts.size() < requiredAccounts) {
                throw new ServiceException("活动 " + StrUtil.blankToDefault(item.getEventTitle(), item.getEventUrl()) + " 需要 " + requiredAccounts + " 个账号，当前只有 " + accounts.size() + " 个");
            }
            int accountIndex = 0;
            lotteryBatchTaskItemMapper.update(null, Wrappers.lambdaUpdate(TicketLotteryBatchTaskItem.class)
                .eq(TicketLotteryBatchTaskItem::getBatchItemId, item.getBatchItemId())
                .set(TicketLotteryBatchTaskItem::getItemStatus, "draft"));
            for (TicketLotteryBatchTaskItemSchedule schedule : schedules) {
                lotteryBatchTaskItemScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketLotteryBatchTaskItemSchedule.class)
                    .eq(TicketLotteryBatchTaskItemSchedule::getScheduleId, schedule.getScheduleId())
                    .set(TicketLotteryBatchTaskItemSchedule::getScheduleStatus, "pending")
                    .set(TicketLotteryBatchTaskItemSchedule::getDispatchedTime, null)
                    .set(TicketLotteryBatchTaskItemSchedule::getFinishedTime, null)
                    .set(TicketLotteryBatchTaskItemSchedule::getResultMessage, "等待执行"));
                TicketLotteryEventSessionVo session = sessionMap.get(schedule.getSessionId());
                if (session == null) {
                    throw new ServiceException("活动 " + StrUtil.blankToDefault(item.getEventTitle(), item.getEventUrl()) + " 的分时段缺少场次元数据，请重新保存任务");
                }
                for (int i = 0; i < ObjectUtil.defaultIfNull(schedule.getAccountCount(), 0); i++) {
                    TicketManagedAccount account = accounts.get(accountIndex++);
                    if (executeNow && hasPendingBatchExecution(task.getBatchTaskId(), schedule.getScheduleId(), account.getAccountId())) {
                        continue;
                    }
                    TicketOrderExecution execution = new TicketOrderExecution();
                    execution.setBatchTaskId(task.getBatchTaskId());
                    execution.setBatchItemId(item.getBatchItemId());
                    execution.setPlatformId(task.getPlatformId());
                    execution.setAccountId(account.getAccountId());
                    execution.setLotteryScheduleId(schedule.getScheduleId());
                    execution.setPurchaseType("lottery");
                    execution.setPurchaseQuantity(1);
                    String configSnapshot = buildLotteryBatchExecutionSnapshot(task, item, session);
                    execution.setConfigSnapshot(configSnapshot);
                    execution.setLotteryEventUrl(TicketOrderFlowSupport.normalizeLotteryEventUrl(item.getEventUrl()));
                    execution.setScheduleVersion(defaultScheduleVersion(task.getScheduleVersion()));
                    execution.setCurrentStep("queued");
                    execution.setStepStatus("queued");
                    execution.setStepTrace("[]");
                    execution.setPaymentStatus(TicketOrderFlowSupport.queuedPaymentStatus("lottery"));
                    execution.setExecutionStatus("queued");
                    execution.setResultMessage("等待 Redis 批量抽票队列调度");
                    execution.setAttemptCount(0);
                    orderExecutionMapper.insert(execution);
                    executions.add(execution);
                }
            }
        }
        task.setTaskStatus(executeNow ? "executing" : "draft");
        if (executeNow) {
            lotteryBatchTaskMapper.update(null, Wrappers.lambdaUpdate(TicketLotteryBatchTask.class)
                .eq(TicketLotteryBatchTask::getBatchTaskId, task.getBatchTaskId())
                .set(TicketLotteryBatchTask::getTaskStatus, "executing"));
        }
        if (CollUtil.isNotEmpty(executions)) {
            registerLotteryBatchDispatchAfterCommit(task, executions, executeNow);
        }
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("batchTaskId", task.getBatchTaskId());
        auditPayload.put("itemCount", items.size());
        auditPayload.put("accountCount", accounts.size());
        auditPayload.put("executionCount", executions.size());
        auditPayload.put("executeMode", executeNow ? "manual-now" : triggerSource);
        auditPayload.put("plannedAt", now);
        recordAudit("lotteryBatchTask", "schedule", "lotteryBatchTask", String.valueOf(task.getBatchTaskId()), "success", "批量抽票任务已排队", auditPayload);
    }

    private boolean hasPendingBatchExecution(Long batchTaskId, Long lotteryScheduleId, Long accountId) {
        if (batchTaskId == null || lotteryScheduleId == null || accountId == null) {
            return false;
        }
        return orderExecutionMapper.selectOne(new LambdaQueryWrapper<TicketOrderExecution>()
            .select(TicketOrderExecution::getExecutionId)
            .eq(TicketOrderExecution::getBatchTaskId, batchTaskId)
            .eq(TicketOrderExecution::getLotteryScheduleId, lotteryScheduleId)
            .eq(TicketOrderExecution::getAccountId, accountId)
            .in(TicketOrderExecution::getExecutionStatus, LOTTERY_RETRY_PENDING_STATUSES)
            .last("limit 1"), false) != null;
    }

    private String buildLotteryBatchExecutionSnapshot(TicketLotteryBatchTask task, TicketLotteryBatchTaskItem item, TicketLotteryEventSessionVo session) {
        JSONObject options = JSONUtil.parseObj(parseTaskOptionsSafe(task.getTaskOptions()));
        options.set("mode", "lottery_batch");
        options.set("sourceUrl", task.getSourceUrl());
        options.set("eventUrl", item.getEventUrl());
        options.set("eventTitle", item.getEventTitle());
        options.set("receptionId", item.getReceptionId());
        options.set("receptionTitle", item.getReceptionTitle());
        options.set("salesType", item.getSalesType());
        options.set("selectedSessions", JSONUtil.parseArray(item.getSelectedSessionsJson()));
        options.set("sessionId", session.getSessionId());
        options.set("sessionLabel", session.getSessionLabel());
        options.set("ticketId", session.getTicketId());
        options.set("ticketField", session.getTicketField());
        options.set("entryQuantity", 1);
        return options.toString();
    }

    private void refreshTaskStatus(Long batchTaskId) {
        if (batchTaskId == null) {
            return;
        }
        TicketLotteryBatchTask task = lotteryBatchTaskMapper.selectById(batchTaskId);
        if (task == null || "cancelled".equals(task.getTaskStatus())) {
            return;
        }
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getBatchTaskId, batchTaskId)
            .eq(TicketOrderExecution::getScheduleVersion, defaultScheduleVersion(task.getScheduleVersion()))
            .orderByAsc(TicketOrderExecution::getExecutionId));
        if (CollUtil.isEmpty(executions)) {
            return;
        }
        String nextStatus = calculateLotteryBatchTaskStatus(task.getTaskStatus(), effectiveLotteryExecutions(executions));
        if (!Objects.equals(task.getTaskStatus(), nextStatus)) {
            task.setTaskStatus(nextStatus);
            lotteryBatchTaskMapper.updateById(task);
        }
    }

    private void refreshItemStatus(Long batchItemId) {
        if (batchItemId == null) {
            return;
        }
        TicketLotteryBatchTaskItem item = lotteryBatchTaskItemMapper.selectById(batchItemId);
        if (item == null) {
            return;
        }
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getBatchItemId, batchItemId)
            .orderByAsc(TicketOrderExecution::getExecutionId));
        if (CollUtil.isEmpty(executions)) {
            return;
        }
        String nextStatus = calculateLotteryBatchTaskStatus(item.getItemStatus(), effectiveLotteryExecutions(executions));
        if (!Objects.equals(item.getItemStatus(), nextStatus)) {
            lotteryBatchTaskItemMapper.update(null, Wrappers.lambdaUpdate(TicketLotteryBatchTaskItem.class)
                .eq(TicketLotteryBatchTaskItem::getBatchItemId, batchItemId)
                .set(TicketLotteryBatchTaskItem::getItemStatus, nextStatus));
        }
    }

    private void refreshItemScheduleStatus(Long scheduleId) {
        if (scheduleId == null) {
            return;
        }
        List<TicketOrderExecution> rawExecutions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getLotteryScheduleId, scheduleId)
            .isNotNull(TicketOrderExecution::getBatchTaskId));
        if (CollUtil.isEmpty(rawExecutions)) {
            lotteryBatchTaskItemScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketLotteryBatchTaskItemSchedule.class)
                .eq(TicketLotteryBatchTaskItemSchedule::getScheduleId, scheduleId)
                .set(TicketLotteryBatchTaskItemSchedule::getScheduleStatus, "failed")
                .set(TicketLotteryBatchTaskItemSchedule::getFinishedTime, new Date())
                .set(TicketLotteryBatchTaskItemSchedule::getResultMessage, "没有抽票执行记录"));
            return;
        }
        List<TicketOrderExecution> executions = effectiveLotteryExecutions(rawExecutions);
        boolean hasRunning = executions.stream().anyMatch(item -> List.of("queued", "running").contains(item.getExecutionStatus()));
        if (hasRunning) {
            lotteryBatchTaskItemScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketLotteryBatchTaskItemSchedule.class)
                .eq(TicketLotteryBatchTaskItemSchedule::getScheduleId, scheduleId)
                .set(TicketLotteryBatchTaskItemSchedule::getScheduleStatus, "running")
                .set(TicketLotteryBatchTaskItemSchedule::getResultMessage, "抽票执行中"));
            return;
        }
        long successCount = executions.stream().filter(item -> LOTTERY_SUCCESS_STATUSES.contains(item.getExecutionStatus())).count();
        String status = successCount == executions.size() ? "completed" : successCount == 0 ? "failed" : "partial";
        lotteryBatchTaskItemScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketLotteryBatchTaskItemSchedule.class)
            .eq(TicketLotteryBatchTaskItemSchedule::getScheduleId, scheduleId)
            .set(TicketLotteryBatchTaskItemSchedule::getScheduleStatus, status)
            .set(TicketLotteryBatchTaskItemSchedule::getFinishedTime, new Date())
            .set(TicketLotteryBatchTaskItemSchedule::getResultMessage, "抽票完成：" + successCount + "/" + executions.size()));
    }

    private String calculateLotteryBatchTaskStatus(String currentStatus, List<TicketOrderExecution> executions) {
        boolean hasRunning = executions.stream().anyMatch(item -> EXECUTION_RUNNING_STATUSES.contains(item.getExecutionStatus()));
        if (hasRunning) {
            return "executing";
        }
        boolean allQueued = executions.stream().allMatch(item -> "queued".equals(item.getExecutionStatus()));
        if (allQueued) {
            return "draft".equals(currentStatus) ? "draft" : "executing";
        }
        boolean hasLotterySuccess = executions.stream().anyMatch(item -> LOTTERY_SUCCESS_STATUSES.contains(item.getExecutionStatus()));
        boolean allLotterySuccess = executions.stream().allMatch(item -> LOTTERY_SUCCESS_STATUSES.contains(item.getExecutionStatus()));
        boolean hasFailure = executions.stream().anyMatch(item -> EXECUTION_FAILURE_STATUSES.contains(item.getExecutionStatus()));
        boolean allBlocked = executions.stream().allMatch(item -> "blocked".equals(item.getExecutionStatus()));
        boolean allFailed = executions.stream().allMatch(item -> EXECUTION_FAILURE_STATUSES.contains(item.getExecutionStatus()));
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

    private void registerLotteryBatchDispatchAfterCommit(TicketLotteryBatchTask task, List<TicketOrderExecution> executions, boolean forceImmediate) {
        if (task == null || task.getBatchTaskId() == null || CollUtil.isEmpty(executions)) {
            return;
        }
        List<Long> executionIds = executions.stream()
            .map(TicketOrderExecution::getExecutionId)
            .filter(Objects::nonNull)
            .toList();
        Runnable dispatchAction = () -> scheduledExecutorService.execute(
            () -> enqueueLotteryBatchDispatches(task.getBatchTaskId(), defaultScheduleVersion(task.getScheduleVersion()), executionIds, forceImmediate)
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

    private void enqueueLotteryBatchDispatches(Long batchTaskId, Long scheduleVersion, List<Long> executionIds, boolean forceImmediate) {
        if (batchTaskId == null || CollUtil.isEmpty(executionIds)) {
            return;
        }
        TicketLotteryBatchTask task = lotteryBatchTaskMapper.selectById(batchTaskId);
        TicketPlatformConfig platform = task == null ? null : platformMapper.selectById(task.getPlatformId());
        if (task == null || platform == null) {
            log.warn("lottery batch dispatch skipped, batchTaskId={}, reason={}", batchTaskId, task == null ? "task missing" : "platform missing");
            return;
        }
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .in(TicketOrderExecution::getExecutionId, executionIds)
            .eq(TicketOrderExecution::getBatchTaskId, batchTaskId)
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .eq(TicketOrderExecution::getScheduleVersion, scheduleVersion)
            .orderByAsc(TicketOrderExecution::getLotteryScheduleId)
            .orderByAsc(TicketOrderExecution::getExecutionId));
        if (CollUtil.isEmpty(executions)) {
            return;
        }
        Map<Long, TicketManagedAccount> accountMap = loadMap(
            executions.stream().map(TicketOrderExecution::getAccountId).filter(Objects::nonNull).toList(),
            accountMapper::selectByIds,
            TicketManagedAccount::getAccountId
        );
        Map<Long, TicketLotteryBatchTaskItemSchedule> scheduleMap = loadMap(
            executions.stream().map(TicketOrderExecution::getLotteryScheduleId).filter(Objects::nonNull).toList(),
            lotteryBatchTaskItemScheduleMapper::selectByIds,
            TicketLotteryBatchTaskItemSchedule::getScheduleId
        );
        Map<String, List<TicketOrderExecution>> grouped = executions.stream().collect(Collectors.groupingBy(execution -> {
            TicketLotteryBatchTaskItemSchedule schedule = scheduleMap.get(execution.getLotteryScheduleId());
            long dispatchAt = forceImmediate ? 0L : (schedule == null || schedule.getScheduledTime() == null ? 0L : schedule.getScheduledTime().getTime());
            return execution.getAccountId() + ":" + dispatchAt;
        }, LinkedHashMap::new, Collectors.toList()));
        for (List<TicketOrderExecution> groupedExecutions : grouped.values()) {
            if (CollUtil.isEmpty(groupedExecutions)) {
                continue;
            }
            TicketManagedAccount account = accountMap.get(groupedExecutions.get(0).getAccountId());
            if (account == null) {
                groupedExecutions.forEach(execution -> markBatchExecutionFailed(execution, "抽票账号不存在"));
                continue;
            }
            try {
                enqueueLotteryBatchDispatchGroup(task, platform, account, groupedExecutions, scheduleMap, forceImmediate);
            } catch (Exception ex) {
                log.error("lottery batch dispatch enqueue failed, batchTaskId={}, accountId={}", batchTaskId, account.getAccountId(), ex);
                groupedExecutions.forEach(execution -> markBatchExecutionFailed(execution, "批量抽票入队失败: " + StrUtil.blankToDefault(ex.getMessage(), "unknown")));
            }
        }
        refreshTaskStatus(batchTaskId);
    }

    private void enqueueLotteryBatchDispatchGroup(TicketLotteryBatchTask task,
                                                  TicketPlatformConfig platform,
                                                  TicketManagedAccount account,
                                                  List<TicketOrderExecution> executions,
                                                  Map<Long, TicketLotteryBatchTaskItemSchedule> scheduleMap,
                                                  boolean forceImmediate) {
        if (task == null || platform == null || account == null || CollUtil.isEmpty(executions)) {
            return;
        }
        TicketLotteryBatchTaskItemSchedule firstSchedule = scheduleMap.get(executions.get(0).getLotteryScheduleId());
        long dispatchAt = forceImmediate ? 0L : (firstSchedule == null || firstSchedule.getScheduledTime() == null ? 0L : firstSchedule.getScheduledTime().getTime());
        String dispatchId = "batch-" + task.getBatchTaskId() + "-" + account.getAccountId() + "-" + dispatchAt + "-" + UUID.randomUUID().toString().replace("-", "");
        writeLotteryBatchDispatchJob(dispatchId, task, account, executions);
        if (dispatchAt > System.currentTimeMillis()) {
            ticketPythonStringRedisTemplate.opsForZSet().add(ticketPythonExecutorProperties.getDelayedZsetKey(), dispatchId, dispatchAt);
        } else {
            addLotteryReadyStream(dispatchId);
        }
        Date now = new Date();
        Set<Long> scheduleIds = executions.stream().map(TicketOrderExecution::getLotteryScheduleId).filter(Objects::nonNull).collect(Collectors.toSet());
        for (Long scheduleId : scheduleIds) {
            lotteryBatchTaskItemScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketLotteryBatchTaskItemSchedule.class)
                .eq(TicketLotteryBatchTaskItemSchedule::getScheduleId, scheduleId)
                .eq(TicketLotteryBatchTaskItemSchedule::getScheduleStatus, "pending")
                .set(TicketLotteryBatchTaskItemSchedule::getResultMessage, forceImmediate ? "立即执行，已写入 Python 批量抽票队列" : (dispatchAt > System.currentTimeMillis() ? "等待 Python 批量抽票队列调度" : "已写入 Python 批量抽票队列"))
                .set(dispatchAt <= System.currentTimeMillis(), TicketLotteryBatchTaskItemSchedule::getDispatchedTime, now));
        }
    }

    private void writeLotteryBatchDispatchJob(String dispatchId,
                                              TicketLotteryBatchTask task,
                                              TicketManagedAccount account,
                                              List<TicketOrderExecution> executions) {
        String password = resolveAccountPassword(account);
        JSONArray items = new JSONArray();
        for (TicketOrderExecution execution : executions) {
            JSONObject snapshot = JSONUtil.parseObj(StrUtil.blankToDefault(execution.getConfigSnapshot(), "{}"));
            JSONObject item = JSONUtil.createObj()
                .set("executionId", execution.getExecutionId())
                .set("batchItemId", execution.getBatchItemId())
                .set("scheduleId", execution.getLotteryScheduleId())
                .set("eventUrl", snapshot.getStr("eventUrl"))
                .set("eventTitle", snapshot.getStr("eventTitle"))
                .set("receptionId", snapshot.getStr("receptionId"))
                .set("receptionTitle", snapshot.getStr("receptionTitle"))
                .set("salesType", snapshot.getStr("salesType"))
                .set("ticketId", snapshot.getStr("ticketId"))
                .set("ticketField", snapshot.getStr("ticketField"))
                .set("sessionId", snapshot.getStr("sessionId"))
                .set("sessionLabel", snapshot.getStr("sessionLabel"))
                .set("purchaseQuantity", ObjectUtil.defaultIfNull(execution.getPurchaseQuantity(), 1))
                .set("taskOptions", snapshot.toString());
            items.add(item);
        }
        JSONObject payload = JSONUtil.createObj()
            .set("mode", "lottery_batch")
            .set("dispatchId", dispatchId)
            .set("batchTaskId", task.getBatchTaskId())
            .set("platformId", task.getPlatformId())
            .set("platformCode", "livepocket")
            .set("backendBaseUrl", requirePythonBackendBaseUrl())
            .set("accountId", account.getAccountId())
            .set("email", account.getEmail())
            .set("password", password)
            .set("platformPassword", password)
            .set("accountInfo", account.getAccountInfo())
            .set("loginReqData", account.getLoginReqData())
            .set("queuedAt", System.currentTimeMillis())
            .set("items", items);
        ticketPythonStringRedisTemplate.opsForValue().set(
            lotteryJobKey(dispatchId),
            payload.toString(),
            Duration.ofSeconds(Math.max(ticketPythonExecutorProperties.getJobTtlSeconds(), 60L))
        );
    }

    private void enrichBatchLotteryExecutionDisplay(TicketOrderExecutionVo row,
                                                    TicketLotteryBatchTaskItem batchItem,
                                                    TicketLotteryBatchTaskItemSchedule batchSchedule) {
        if (row == null || row.getBatchTaskId() == null) {
            return;
        }
        Map<String, Object> options = TicketOrderFlowSupport.parseTaskOptions(StrUtil.blankToDefault(row.getConfigSnapshot(), "{}"));
        row.setEventUrl(Convert.toStr(options.get("eventUrl")));
        row.setTicketEntryUrl(null);
        row.setEventTitle(StrUtil.blankToDefault(Convert.toStr(options.get("eventTitle")), batchItem == null ? null : batchItem.getEventTitle()));
        if (batchSchedule != null) {
            row.setLotterySessionLabel(StrUtil.blankToDefault(batchSchedule.getSessionLabel(), batchSchedule.getSessionId()));
            row.setLotteryScheduledTime(batchSchedule.getScheduledTime());
        }
        if (StrUtil.isBlank(row.getLotterySessionLabel())) {
            row.setLotterySessionLabel(StrUtil.blankToDefault(Convert.toStr(options.get("sessionLabel")), Convert.toStr(options.get("sessionId"))));
        }
    }

    private void invalidateQueuedBatchExecutions(Long batchTaskId, String message) {
        if (batchTaskId == null) {
            return;
        }
        Date now = new Date();
        orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getBatchTaskId, batchTaskId)
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .set(TicketOrderExecution::getExecutionStatus, "blocked")
            .set(TicketOrderExecution::getCurrentStep, "completed")
            .set(TicketOrderExecution::getStepStatus, "failed")
            .set(TicketOrderExecution::getResultMessage, message)
            .set(TicketOrderExecution::getExecutedAt, now));
    }

    private void markBatchExecutionFailed(TicketOrderExecution execution, String message) {
        if (execution == null || execution.getExecutionId() == null) {
            return;
        }
        Date now = new Date();
        orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getExecutionId, execution.getExecutionId())
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .set(TicketOrderExecution::getExecutionStatus, "failed")
            .set(TicketOrderExecution::getCurrentStep, "LOTTERY_ENTRY")
            .set(TicketOrderExecution::getStepStatus, "failed")
            .set(TicketOrderExecution::getResultMessage, message)
            .set(TicketOrderExecution::getExecutedAt, now)
            .set(TicketOrderExecution::getHeartbeatAt, now));
        refreshAfterExecution(execution);
    }

    private void addProcessStep(List<TicketSaleTaskProcessStepVo> steps, String stepKey, String title, String description, boolean finished, Date time) {
        TicketSaleTaskProcessStepVo step = new TicketSaleTaskProcessStepVo();
        step.setStepKey(stepKey);
        step.setTitle(title);
        step.setDescription(StrUtil.blankToDefault(description, "-"));
        step.setStatus(finished ? "finish" : "wait");
        step.setTime(time);
        steps.add(step);
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
            if (StrUtil.isBlank(sessionId)) {
                continue;
            }
            TicketLotteryEventSessionVo vo = new TicketLotteryEventSessionVo();
            vo.setSessionId(sessionId);
            vo.setSessionLabel(StrUtil.blankToDefault(object.getStr("sessionLabel"), sessionId));
            vo.setEventUrl(object.getStr("eventUrl"));
            vo.setReceptionId(object.getStr("receptionId"));
            vo.setTicketId(object.getStr("ticketId"));
            vo.setTicketField(object.getStr("ticketField"));
            vo.setReceptionTitle(object.getStr("receptionTitle"));
            vo.setSalesType(object.getStr("salesType"));
            vo.setNotes(object.getStr("notes"));
            sessions.add(vo);
        }
        return sessions;
    }

    private List<TicketOrderExecution> effectiveLotteryExecutions(List<TicketOrderExecution> executions) {
        if (CollUtil.isEmpty(executions)) {
            return executions;
        }
        Map<String, TicketOrderExecution> latest = new LinkedHashMap<>();
        List<TicketOrderExecution> fallback = new ArrayList<>();
        for (TicketOrderExecution execution : executions) {
            String key = lotteryExecutionRetryKey(execution);
            if (StrUtil.isBlank(key)) {
                fallback.add(execution);
                continue;
            }
            latest.put(key, execution);
        }
        List<TicketOrderExecution> result = new ArrayList<>(latest.values());
        result.addAll(fallback);
        return result;
    }

    private String lotteryExecutionRetryKey(TicketOrderExecution execution) {
        if (execution == null || execution.getLotteryScheduleId() == null || execution.getAccountId() == null) {
            return "";
        }
        return execution.getLotteryScheduleId() + ":" + execution.getAccountId();
    }

    private void countExecutionStatus(Map<String, Integer> summary, TicketOrderExecution execution) {
        if (summary == null || execution == null) {
            return;
        }
        summary.merge("total", 1, Integer::sum);
        String status = StrUtil.blankToDefault(execution.getExecutionStatus(), "unknown");
        if ("timeout".equals(status)) {
            summary.merge("failed", 1, Integer::sum);
        }
        summary.merge(status, 1, Integer::sum);
    }

    private Map<String, Integer> emptyExecutionSummary() {
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("total", 0);
        return summary;
    }

    private String requirePythonBackendBaseUrl() {
        if (StrUtil.isBlank(ticketPythonExecutorProperties.getBackendBaseUrl())) {
            throw new ServiceException("未配置 Java 外部账号接口地址");
        }
        return ticketPythonExecutorProperties.getBackendBaseUrl();
    }

    private RecordId addLotteryReadyStream(String executionId) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("executionId", String.valueOf(executionId));
        message.put("enqueuedAt", String.valueOf(System.currentTimeMillis()));
        return ticketPythonStringRedisTemplate.opsForStream().add(
            StreamRecords.mapBacked(message).withStreamKey(ticketPythonExecutorProperties.getReadyQueueKey())
        );
    }

    private String lotteryJobKey(String executionId) {
        return ticketPythonExecutorProperties.getJobKeyPrefix() + executionId;
    }

    private String resolveAccountPassword(TicketManagedAccount account) {
        if (account == null || StrUtil.isBlank(account.getAccountInfo())) {
            return "";
        }
        try {
            JSONObject accountInfo = JSONUtil.parseObj(account.getAccountInfo());
            return StrUtil.blankToDefault(accountInfo.getStr("platformPassword"), accountInfo.getStr("password"));
        } catch (Exception ex) {
            log.warn("resolve account password failed, accountId={}", account.getAccountId(), ex);
            return "";
        }
    }

    private Long nextScheduleVersion(Long currentVersion) {
        return defaultScheduleVersion(currentVersion) + 1L;
    }

    private Long defaultScheduleVersion(Long currentVersion) {
        return currentVersion == null || currentVersion <= 0 ? 1L : currentVersion;
    }

    private <T, K> Map<K, T> loadMap(List<K> ids, Function<List<K>, List<T>> loader, Function<T, K> keyMapper) {
        if (CollUtil.isEmpty(ids)) {
            return Map.of();
        }
        List<K> normalizedIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (CollUtil.isEmpty(normalizedIds)) {
            return Map.of();
        }
        List<T> list = loader.apply(normalizedIds);
        if (CollUtil.isEmpty(list)) {
            return Map.of();
        }
        Map<K, T> map = new LinkedHashMap<>();
        for (T item : list) {
            K key = keyMapper.apply(item);
            if (key != null) {
                map.put(key, item);
            }
        }
        return map;
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
