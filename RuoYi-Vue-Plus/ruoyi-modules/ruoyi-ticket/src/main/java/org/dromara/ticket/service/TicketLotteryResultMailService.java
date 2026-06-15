package org.dromara.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.domain.TicketMailRecord;
import org.dromara.ticket.domain.TicketOrderExecution;
import org.dromara.ticket.domain.TicketSaleTask;
import org.dromara.ticket.mapper.TicketOrderExecutionMapper;
import org.dromara.ticket.mapper.TicketSaleTaskMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketLotteryResultMailService {

    private static final String PARSE_TYPE_LOTTERY_SELECTED = "lottery_selected";
    private static final String PARSE_TYPE_PURCHASE_COMPLETED = "purchase_completed";
    private static final String LOTTERY_RESULT_SELECTED = "selected";
    private static final String PAYMENT_WAITING_RESULT = "lottery_waiting_result";
    private static final List<String> PURCHASE_MAIL_SKIP_STATUSES = List.of("paid", "cancelled", "blocked");
    private static final List<String> EXECUTION_RUNNING_STATUSES = List.of("queued", "running");
    private static final List<String> EXECUTION_SUCCESS_STATUSES = List.of("submitted", "pending_payment", "paid", "completed");
    private static final List<String> EXECUTION_FAILURE_STATUSES = List.of("failed", "blocked", "timeout", "cancelled");

    private final TicketOrderExecutionMapper orderExecutionMapper;
    private final TicketSaleTaskMapper saleTaskMapper;

    public void processSelectedMail(TicketMailRecord record) {
        if (record == null || !PARSE_TYPE_LOTTERY_SELECTED.equals(record.getParseType())
            || !LOTTERY_RESULT_SELECTED.equals(record.getLotteryResultStatus())
            || StringUtils.isBlank(record.getLotteryApplicationNo())) {
            return;
        }

        TicketOrderExecution execution = orderExecutionMapper.selectOne(
            new LambdaQueryWrapper<TicketOrderExecution>()
                .eq(TicketOrderExecution::getPurchaseType, "lottery")
                .eq(TicketOrderExecution::getOrderNo, record.getLotteryApplicationNo())
                .orderByDesc(TicketOrderExecution::getExecutionId)
                .last("limit 1"),
            false
        );
        if (execution == null) {
            log.info("livepocket selected mail has no matched lottery execution, applicationNo={}, recordId={}",
                record.getLotteryApplicationNo(), record.getRecordId());
            return;
        }

        Date resultAt = record.getReceivedAt() == null ? new Date() : record.getReceivedAt();
        boolean paymentRequired = PAYMENT_WAITING_RESULT.equals(execution.getPaymentStatus());
        String resultMessage = paymentRequired ? "抽选已当选，待便利店支付" : "抽选已当选";
        int rows = orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getExecutionId, execution.getExecutionId())
            .set(TicketOrderExecution::getLotteryResultStatus, LOTTERY_RESULT_SELECTED)
            .set(TicketOrderExecution::getLotteryResultMailRecordId, record.getRecordId())
            .set(TicketOrderExecution::getLotteryResultAt, resultAt)
            .set(paymentRequired, TicketOrderExecution::getExecutionStatus, "pending_payment")
            .set(paymentRequired, TicketOrderExecution::getPaymentStatus, "offline_pending")
            .set(TicketOrderExecution::getResultMessage, resultMessage));
        if (rows > 0) {
            if (paymentRequired && execution.getTaskId() != null) {
                saleTaskMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTask.class)
                    .eq(TicketSaleTask::getTaskId, execution.getTaskId())
                    .in(TicketSaleTask::getTaskStatus, "completed", "executing", "draft", "partial")
                    .set(TicketSaleTask::getTaskStatus, "pending_payment"));
            }
            log.info("livepocket selected mail marked lottery execution, applicationNo={}, executionId={}, recordId={}",
                record.getLotteryApplicationNo(), execution.getExecutionId(), record.getRecordId());
        }
    }

    public void processPurchaseCompletedMail(TicketMailRecord record) {
        if (record == null || !PARSE_TYPE_PURCHASE_COMPLETED.equals(record.getParseType())
            || StringUtils.isBlank(record.getLotteryApplicationNo())) {
            return;
        }

        List<TicketOrderExecution> candidates = orderExecutionMapper.selectList(
            new LambdaQueryWrapper<TicketOrderExecution>()
                .eq(TicketOrderExecution::getOrderNo, record.getLotteryApplicationNo())
                .orderByDesc(TicketOrderExecution::getExecutionId)
        );
        TicketOrderExecution execution = matchOrderExecution(record, candidates);
        if (execution == null) {
            log.info("livepocket purchase completed mail has no unique matched execution, applicationNo={}, accountId={}, recordId={}, candidates={}",
                record.getLotteryApplicationNo(), record.getAccountId(), record.getRecordId(), candidates == null ? 0 : candidates.size());
            return;
        }
        if (PURCHASE_MAIL_SKIP_STATUSES.contains(execution.getExecutionStatus())) {
            return;
        }

        Date resultAt = record.getReceivedAt() == null ? new Date() : record.getReceivedAt();
        boolean isLottery = "lottery".equals(execution.getPurchaseType());
        int rows = orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
            .eq(TicketOrderExecution::getExecutionId, execution.getExecutionId())
            .notIn(TicketOrderExecution::getExecutionStatus, PURCHASE_MAIL_SKIP_STATUSES)
            .set(TicketOrderExecution::getExecutionStatus, "paid")
            .set(TicketOrderExecution::getPaymentStatus, "paid")
            .set(TicketOrderExecution::getCurrentStep, "completed")
            .set(TicketOrderExecution::getStepStatus, "success")
            .set(TicketOrderExecution::getResultMessage, "购入完成邮件确认")
            .set(TicketOrderExecution::getExecutedAt, resultAt)
            .set(TicketOrderExecution::getHeartbeatAt, resultAt)
            .set(isLottery, TicketOrderExecution::getLotteryResultStatus, LOTTERY_RESULT_SELECTED)
            .set(isLottery, TicketOrderExecution::getLotteryResultMailRecordId, record.getRecordId())
            .set(isLottery, TicketOrderExecution::getLotteryResultAt, resultAt));
        if (rows > 0) {
            refreshSaleTaskStatus(execution.getTaskId());
            log.info("livepocket purchase completed mail marked execution paid, applicationNo={}, executionId={}, recordId={}",
                record.getLotteryApplicationNo(), execution.getExecutionId(), record.getRecordId());
        }
    }

    private TicketOrderExecution matchOrderExecution(TicketMailRecord record, List<TicketOrderExecution> candidates) {
        if (record == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (record.getAccountId() != null) {
            List<TicketOrderExecution> matchedByAccount = candidates.stream()
                .filter(item -> Objects.equals(item.getAccountId(), record.getAccountId()))
                .toList();
            if (matchedByAccount.size() == 1) {
                return matchedByAccount.get(0);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private void refreshSaleTaskStatus(Long taskId) {
        if (taskId == null) {
            return;
        }
        TicketSaleTask task = saleTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .eq(TicketOrderExecution::getTaskId, taskId));
        if (executions == null || executions.isEmpty()) {
            return;
        }
        String status = calculateSaleTaskStatus(task, executions);
        saleTaskMapper.update(null, Wrappers.lambdaUpdate(TicketSaleTask.class)
            .eq(TicketSaleTask::getTaskId, taskId)
            .set(TicketSaleTask::getTaskStatus, status)
            .set(TicketSaleTask::getLastExecutedTime, new Date()));
    }

    private String calculateSaleTaskStatus(TicketSaleTask task, List<TicketOrderExecution> executions) {
        boolean hasRunning = executions.stream().anyMatch(item -> EXECUTION_RUNNING_STATUSES.contains(item.getExecutionStatus()));
        if (hasRunning) {
            boolean allQueued = executions.stream().allMatch(item -> "queued".equals(item.getExecutionStatus()));
            return allQueued && "draft".equals(task.getTaskStatus()) ? "draft" : "executing";
        }
        boolean hasSuccess = executions.stream().anyMatch(item -> EXECUTION_SUCCESS_STATUSES.contains(item.getExecutionStatus()));
        boolean allSuccess = executions.stream().allMatch(item -> EXECUTION_SUCCESS_STATUSES.contains(item.getExecutionStatus()));
        boolean hasFailure = executions.stream().anyMatch(item -> EXECUTION_FAILURE_STATUSES.contains(item.getExecutionStatus()));
        boolean allBlocked = executions.stream().allMatch(item -> "blocked".equals(item.getExecutionStatus()));
        boolean allFailed = executions.stream().allMatch(item -> EXECUTION_FAILURE_STATUSES.contains(item.getExecutionStatus()));
        if (allBlocked) {
            return "blocked";
        }
        if (allFailed) {
            return "failed";
        }
        if (allSuccess) {
            return "completed";
        }
        if (hasSuccess && hasFailure) {
            return "partial";
        }
        if (hasSuccess) {
            return "partial";
        }
        return "failed";
    }
}
