package org.dromara.ticket.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.adapter.TicketOrderFlowSupport;
import org.dromara.ticket.config.TicketHandsFormExtensionProperties;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketOrderExecution;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.TicketSaleTask;
import org.dromara.ticket.domain.TicketSaleTaskSchedule;
import org.dromara.ticket.domain.bo.TicketHandsFormExtensionClaimBo;
import org.dromara.ticket.domain.bo.TicketHandsFormExtensionHeartbeatBo;
import org.dromara.ticket.domain.bo.TicketHandsFormExtensionReportBo;
import org.dromara.ticket.domain.vo.TicketHandsFormExtensionTaskVo;
import org.dromara.ticket.domain.vo.TicketLotteryEventSessionVo;
import org.dromara.ticket.mapper.TicketManagedAccountMapper;
import org.dromara.ticket.mapper.TicketOrderExecutionMapper;
import org.dromara.ticket.mapper.TicketPlatformConfigMapper;
import org.dromara.ticket.mapper.TicketSaleTaskMapper;
import org.dromara.ticket.mapper.TicketSaleTaskScheduleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketHandsFormExtensionService {

    private static final String HANDS_FORM_PLATFORM_CODE = "hands-form";
    private static final String HANDS_EXTENSION_WAITING_MESSAGE = "等待 Hands Chrome 扩展领取";
    private static final String HANDS_EXTENSION_RUNNING_MESSAGE = "Hands Chrome 扩展处理中";
    private static final List<String> LOTTERY_SUCCESS_STATUSES = List.of("submitted", "pending_payment", "paid");
    private static final List<String> LOTTERY_RUNNING_STATUSES = List.of("queued", "running");
    private static final List<String> LOTTERY_FAILURE_STATUSES = List.of("failed", "blocked", "timeout", "cancelled");
    private static final int AUDIT_MESSAGE_MAX_LENGTH = 500;

    private final TicketHandsFormExtensionProperties properties;
    private final TicketOrderExecutionMapper orderExecutionMapper;
    private final TicketSaleTaskMapper saleTaskMapper;
    private final TicketSaleTaskScheduleMapper saleTaskScheduleMapper;
    private final TicketPlatformConfigMapper platformMapper;
    private final TicketManagedAccountMapper accountMapper;

    @Transactional(rollbackFor = Exception.class)
    public TicketHandsFormExtensionTaskVo claimNextHandsFormExtensionTask(TicketHandsFormExtensionClaimBo bo) {
        if (!properties.isEnabled()) {
            throw new ServiceException("Hands Form 扩展执行未启用");
        }
        String workerId = StringUtils.trim(bo.getWorkerId());
        if (StringUtils.isBlank(workerId)) {
            throw new ServiceException("workerId不能为空");
        }
        List<TicketOrderExecution> candidates = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getPurchaseType, "lottery")
            .eq(TicketOrderExecution::getExecutionStatus, "queued")
            .eq(TicketOrderExecution::getResultMessage, HANDS_EXTENSION_WAITING_MESSAGE)
            .isNull(TicketOrderExecution::getBatchTaskId)
            .orderByAsc(TicketOrderExecution::getExecutionId)
            .last("limit 50"));
        Date now = new Date();
        for (TicketOrderExecution execution : candidates) {
            TicketSaleTask task = saleTaskMapper.selectById(execution.getTaskId());
            TicketPlatformConfig platform = platformMapper.selectById(execution.getPlatformId());
            TicketManagedAccount account = accountMapper.selectById(execution.getAccountId());
            TicketSaleTaskSchedule schedule = saleTaskScheduleMapper.selectById(execution.getLotteryScheduleId());
            if (task == null || platform == null || account == null || schedule == null) {
                markLotteryExecutionFailed(execution.getExecutionId(), "Hands 扩展任务依赖数据缺失");
                continue;
            }
            if (!isHandsFormPlatform(platform)) {
                continue;
            }
            int claimed = orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
                .eq(TicketOrderExecution::getExecutionId, execution.getExecutionId())
                .eq(TicketOrderExecution::getExecutionStatus, "queued")
                .eq(TicketOrderExecution::getResultMessage, HANDS_EXTENSION_WAITING_MESSAGE)
                .set(TicketOrderExecution::getExecutionStatus, "running")
                .set(TicketOrderExecution::getCurrentStep, "LOTTERY_ENTRY")
                .set(TicketOrderExecution::getStepStatus, "running")
                .set(TicketOrderExecution::getWorkerId, workerId)
                .set(TicketOrderExecution::getStartedAt, now)
                .set(TicketOrderExecution::getHeartbeatAt, now)
                .set(TicketOrderExecution::getResultMessage, HANDS_EXTENSION_RUNNING_MESSAGE));
            if (claimed <= 0) {
                continue;
            }
            TicketHandsFormExtensionTaskVo taskVo = buildTaskVo(execution, task, schedule, platform, account, workerId);
            if (StringUtils.isAnyBlank(taskVo.getEventUrl(), taskVo.getEmail(), taskVo.getFullName(), taskVo.getFurigana())) {
                markLotteryExecutionFailed(execution.getExecutionId(), "Hands 扩展任务缺少必要表单字段");
                continue;
            }
            return taskVo;
        }
        return null;
    }

    public R<Void> heartbeatHandsFormExtensionTask(TicketHandsFormExtensionHeartbeatBo bo) {
        Date now = new Date();
        int updated = orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getExecutionId, bo.getExecutionId())
            .eq(TicketOrderExecution::getExecutionStatus, "running")
            .eq(TicketOrderExecution::getWorkerId, StringUtils.trim(bo.getWorkerId()))
            .set(TicketOrderExecution::getHeartbeatAt, now));
        if (updated <= 0) {
            return R.fail("任务不存在或已被其他扩展实例接管");
        }
        return R.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    public R<Void> reportHandsFormExtensionTask(TicketHandsFormExtensionReportBo bo) {
        Long executionId = bo.getExecutionId();
        TicketOrderExecution execution = orderExecutionMapper.selectById(executionId);
        if (execution == null) {
            return R.fail("执行记录不存在");
        }
        if (!"running".equals(execution.getExecutionStatus())) {
            return R.fail("执行记录状态不是running");
        }
        if (!StringUtils.equals(StringUtils.defaultString(execution.getWorkerId()), StringUtils.trim(bo.getWorkerId()))) {
            return R.fail("workerId不匹配");
        }
        Date now = new Date();
        boolean success = Boolean.TRUE.equals(bo.getSuccess());
        String finalStatus = success ? "submitted" : "failed";
        String message = StringUtils.defaultIfBlank(bo.getMessage(), success ? "Hands Chrome 扩展提交成功" : "Hands Chrome 扩展提交失败");
        JSONObject raw = JSONUtil.createObj();
        raw.set("source", "hands-form-extension");
        raw.set("executionId", executionId);
        raw.set("workerId", StringUtils.trim(bo.getWorkerId()));
        raw.set("success", success);
        raw.set("message", message);
        raw.set("orderId", StringUtils.trim(bo.getOrderId()));
        raw.set("resultUrl", StringUtils.trim(bo.getResultUrl()));
        if (StringUtils.isNotBlank(bo.getRawResult())) {
            raw.set("payload", bo.getRawResult());
        }
        JSONObject trace = JSONUtil.createObj()
            .set("step", "LOTTERY_ENTRY")
            .set("status", success ? "success" : "failed")
            .set("message", message)
            .set("time", DateUtil.formatDateTime(now))
            .set("rawStatus", finalStatus)
            .set("source", "hands-form-extension");
        int updated = orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getExecutionId, executionId)
            .eq(TicketOrderExecution::getExecutionStatus, "running")
            .eq(TicketOrderExecution::getWorkerId, StringUtils.trim(bo.getWorkerId()))
            .set(TicketOrderExecution::getExecutionStatus, finalStatus)
            .set(StringUtils.isNotBlank(bo.getOrderId()), TicketOrderExecution::getOrderNo, StringUtils.trim(bo.getOrderId()))
            .set(TicketOrderExecution::getCurrentStep, "LOTTERY_ENTRY")
            .set(TicketOrderExecution::getStepStatus, success ? "success" : "failed")
            .set(TicketOrderExecution::getPaymentStatus, "not_required")
            .set(TicketOrderExecution::getResultMessage, fitAuditText(message, AUDIT_MESSAGE_MAX_LENGTH))
            .set(TicketOrderExecution::getRawResult, raw.toString())
            .set(TicketOrderExecution::getStepTrace, JSONUtil.toJsonStr(List.of(trace)))
            .set(TicketOrderExecution::getExecutedAt, now)
            .set(TicketOrderExecution::getHeartbeatAt, now));
        if (updated <= 0) {
            return R.fail("执行结果写入失败");
        }
        if (execution.getLotteryScheduleId() != null) {
            refreshLotteryScheduleStatus(execution.getLotteryScheduleId());
        }
        if (execution.getTaskId() != null) {
            refreshSaleTaskStatus(execution.getTaskId());
        }
        return R.ok();
    }

    private TicketHandsFormExtensionTaskVo buildTaskVo(TicketOrderExecution execution,
                                                       TicketSaleTask task,
                                                       TicketSaleTaskSchedule schedule,
                                                       TicketPlatformConfig platform,
                                                       TicketManagedAccount account,
                                                       String workerId) {
        TicketHandsFormExtensionTaskVo vo = new TicketHandsFormExtensionTaskVo();
        vo.setExecutionId(execution.getExecutionId());
        vo.setTaskId(task.getTaskId());
        vo.setScheduleId(schedule.getScheduleId());
        vo.setPlatformId(platform.getPlatformId());
        vo.setAccountId(account.getAccountId());
        vo.setPlatformCode(platform.getPlatformCode());
        vo.setPlatformName(platform.getPlatformName());
        vo.setTaskName(task.getTaskName());
        vo.setSessionId(schedule.getSessionId());
        vo.setSessionLabel(schedule.getSessionLabel());
        vo.setEmail(account.getEmail());
        vo.setAccountInfo(account.getAccountInfo());
        vo.setWorkerId(workerId);
        JSONObject accountInfo = parseAccountInfoObject(account.getAccountInfo());
        String fullName = StringUtils.defaultIfBlank(accountInfo.getStr("fullName"), "");
        if (StringUtils.isBlank(fullName)) {
            fullName = StringUtils.trimToEmpty(accountInfo.getStr("familyName")) + StringUtils.trimToEmpty(accountInfo.getStr("givenName"));
        }
        vo.setFullName(fullName);
        vo.setFurigana(accountInfo.getStr("furigana"));
        TicketLotteryEventSessionVo selectedSession = findSelectedLotterySession(task.getTaskOptions(), schedule.getSessionId());
        String eventUrl = selectedSession != null ? selectedSession.getEventUrl() : null;
        if (StringUtils.isBlank(eventUrl)) {
            Map<String, Object> taskOptions = TicketOrderFlowSupport.parseTaskOptions(task.getTaskOptions());
            eventUrl = StringUtils.defaultIfBlank(Convert.toStr(taskOptions.get("eventUrl")), Convert.toStr(taskOptions.get("lotteryEntryUrl")));
        }
        vo.setEventUrl(eventUrl);
        return vo;
    }

    private JSONObject parseAccountInfoObject(String accountInfoText) {
        if (StringUtils.isBlank(accountInfoText) || !JSONUtil.isTypeJSON(accountInfoText)) {
            return JSONUtil.createObj();
        }
        try {
            return JSONUtil.parseObj(accountInfoText);
        } catch (Exception ex) {
            log.warn("parse hands account info failed: {}", accountInfoText, ex);
            return JSONUtil.createObj();
        }
    }

    private TicketLotteryEventSessionVo findSelectedLotterySession(String taskOptionsText, String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }
        Map<String, Object> taskOptions = TicketOrderFlowSupport.parseTaskOptions(taskOptionsText);
        return parseLotterySessionVos(taskOptions.get("selectedSessions")).stream()
            .filter(item -> sessionId.equals(item.getSessionId()))
            .findFirst()
            .orElse(null);
    }

    private List<TicketLotteryEventSessionVo> parseLotterySessionVos(Object value) {
        if (ObjectUtil.isNull(value)) {
            return List.of();
        }
        try {
            JSONArray array;
            if (value instanceof JSONArray jsonArray) {
                array = jsonArray;
            } else if (value instanceof String text && JSONUtil.isTypeJSONArray(text)) {
                array = JSONUtil.parseArray(text);
            } else {
                array = JSONUtil.parseArray(value);
            }
            List<TicketLotteryEventSessionVo> sessions = new ArrayList<>();
            for (Object item : array) {
                JSONObject obj = JSONUtil.parseObj(item);
                TicketLotteryEventSessionVo session = new TicketLotteryEventSessionVo();
                session.setSessionId(obj.getStr("sessionId"));
                session.setSessionLabel(obj.getStr("sessionLabel"));
                session.setEventUrl(obj.getStr("eventUrl"));
                session.setReceptionId(obj.getStr("receptionId"));
                session.setTicketId(obj.getStr("ticketId"));
                session.setTicketField(obj.getStr("ticketField"));
                session.setReceptionTitle(obj.getStr("receptionTitle"));
                session.setSalesType(obj.getStr("salesType"));
                session.setNotes(obj.getStr("notes"));
                sessions.add(session);
            }
            return sessions;
        } catch (Exception ex) {
            log.warn("parse lottery sessions failed: {}", value, ex);
            return List.of();
        }
    }

    private void refreshLotteryScheduleStatus(Long scheduleId) {
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getLotteryScheduleId, scheduleId));
        if (CollUtil.isEmpty(executions)) {
            saleTaskScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTaskSchedule.class)
                .eq(TicketSaleTaskSchedule::getScheduleId, scheduleId)
                .set(TicketSaleTaskSchedule::getScheduleStatus, "failed")
                .set(TicketSaleTaskSchedule::getFinishedTime, new Date())
                .set(TicketSaleTaskSchedule::getResultMessage, "没有抽票执行记录"));
            return;
        }
        boolean hasRunning = executions.stream().anyMatch(item -> LOTTERY_RUNNING_STATUSES.contains(item.getExecutionStatus()));
        if (hasRunning) {
            saleTaskScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTaskSchedule.class)
                .eq(TicketSaleTaskSchedule::getScheduleId, scheduleId)
                .set(TicketSaleTaskSchedule::getScheduleStatus, "running")
                .set(TicketSaleTaskSchedule::getResultMessage, "抽票执行中"));
            return;
        }
        long successCount = executions.stream().filter(item -> LOTTERY_SUCCESS_STATUSES.contains(item.getExecutionStatus())).count();
        String status = successCount == executions.size() ? "completed" : successCount == 0 ? "failed" : "partial";
        String message = "抽票完成：" + successCount + "/" + executions.size();
        saleTaskScheduleMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTaskSchedule.class)
            .eq(TicketSaleTaskSchedule::getScheduleId, scheduleId)
            .set(TicketSaleTaskSchedule::getScheduleStatus, status)
            .set(TicketSaleTaskSchedule::getFinishedTime, new Date())
            .set(TicketSaleTaskSchedule::getResultMessage, message));
    }

    private void refreshSaleTaskStatus(Long taskId) {
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getTaskId, taskId)
            .orderByAsc(TicketOrderExecution::getExecutionId));
        if (CollUtil.isEmpty(executions)) {
            return;
        }
        if (executions.stream().anyMatch(item -> LOTTERY_RUNNING_STATUSES.contains(item.getExecutionStatus()))) {
            saleTaskMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTask.class)
                .eq(TicketSaleTask::getTaskId, taskId)
                .set(TicketSaleTask::getTaskStatus, "executing")
                .set(TicketSaleTask::getLastExecutedTime, new Date()));
            return;
        }
        long successCount = executions.stream().filter(item -> LOTTERY_SUCCESS_STATUSES.contains(item.getExecutionStatus())).count();
        long failureCount = executions.stream().filter(item -> LOTTERY_FAILURE_STATUSES.contains(item.getExecutionStatus())).count();
        String status;
        if (successCount > 0 && failureCount > 0) {
            status = "partial";
        } else if (successCount > 0) {
            status = "completed";
        } else if (failureCount > 0) {
            status = "failed";
        } else {
            status = "draft";
        }
        saleTaskMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTask.class)
            .eq(TicketSaleTask::getTaskId, taskId)
            .set(TicketSaleTask::getTaskStatus, status)
            .set(TicketSaleTask::getLastExecutedTime, new Date()));
    }

    private void markLotteryExecutionFailed(Long executionId, String message) {
        Date now = new Date();
        orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getExecutionId, executionId)
            .set(TicketOrderExecution::getExecutionStatus, "failed")
            .set(TicketOrderExecution::getCurrentStep, "LOTTERY_ENTRY")
            .set(TicketOrderExecution::getStepStatus, "failed")
            .set(TicketOrderExecution::getResultMessage, fitAuditText(message, AUDIT_MESSAGE_MAX_LENGTH))
            .set(TicketOrderExecution::getExecutedAt, now)
            .set(TicketOrderExecution::getHeartbeatAt, now));
    }

    private boolean isHandsFormPlatform(TicketPlatformConfig platform) {
        if (platform == null || StringUtils.isBlank(HANDS_FORM_PLATFORM_CODE)) {
            return false;
        }
        String currentCode = StringUtils.defaultIfBlank(platform.getAdapterType(), platform.getPlatformCode());
        return HANDS_FORM_PLATFORM_CODE.equalsIgnoreCase(currentCode)
            || HANDS_FORM_PLATFORM_CODE.equalsIgnoreCase(platform.getPlatformCode());
    }

    private String fitAuditText(String value, int maxLength) {
        String text = StringUtils.defaultString(value);
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
