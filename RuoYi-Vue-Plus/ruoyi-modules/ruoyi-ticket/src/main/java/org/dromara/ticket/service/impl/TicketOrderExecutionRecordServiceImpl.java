package org.dromara.ticket.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.ticket.domain.TicketOrderExecution;
import org.dromara.ticket.mapper.TicketOrderExecutionMapper;
import org.dromara.ticket.service.ITicketOpsService;
import org.dromara.ticket.service.ITicketOrderExecutionRecordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketOrderExecutionRecordServiceImpl implements ITicketOrderExecutionRecordService {

    private static final Set<String> DELETE_TERMINAL_STATUSES = Set.of("completed", "failed", "cancelled", "expired", "paid");

    private final TicketOrderExecutionMapper orderExecutionMapper;
    private final ITicketOpsService ticketOpsService;
    private final TicketLotteryBatchTaskService ticketLotteryBatchTaskService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeOrderExecutions(Long[] executionIds) {
        List<Long> ids = normalizeExecutionIds(executionIds);
        if (CollUtil.isEmpty(ids)) {
            return 0;
        }
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .in(TicketOrderExecution::getExecutionId, ids));
        if (CollUtil.isEmpty(executions)) {
            return 0;
        }
        List<TicketOrderExecution> nonTerminalExecutions = executions.stream()
            .filter(execution -> !isDeleteTerminal(execution.getExecutionStatus()))
            .toList();
        if (CollUtil.isNotEmpty(nonTerminalExecutions)) {
            String examples = nonTerminalExecutions.stream()
                .limit(10)
                .map(item -> item.getExecutionId() + "(" + StrUtil.blankToDefault(item.getExecutionStatus(), "-") + ")")
                .collect(Collectors.joining(", "));
            throw new ServiceException("仅支持删除已结束订单，以下记录仍未结束：" + examples);
        }
        int rows = logicDeleteOrderExecutions(ids);
        refreshAfterDeletion(executions);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanupOldOrderExecutions(Date cutoffTime, int batchSize) {
        if (cutoffTime == null) {
            return 0;
        }
        int limit = normalizeCleanupBatchSize(batchSize);
        List<TicketOrderExecution> executions = orderExecutionMapper.selectList(new LambdaQueryWrapper<TicketOrderExecution>()
            .select(TicketOrderExecution::getExecutionId,
                TicketOrderExecution::getTaskId,
                TicketOrderExecution::getBatchTaskId,
                TicketOrderExecution::getBatchItemId,
                TicketOrderExecution::getLotteryScheduleId)
            .in(TicketOrderExecution::getExecutionStatus, DELETE_TERMINAL_STATUSES)
            .apply("COALESCE(executed_at, update_time, create_time) < {0}", cutoffTime)
            .orderByAsc(TicketOrderExecution::getExecutedAt)
            .orderByAsc(TicketOrderExecution::getExecutionId)
            .last("LIMIT " + limit));
        if (CollUtil.isEmpty(executions)) {
            return 0;
        }
        int rows = logicDeleteOrderExecutions(executions.stream()
            .map(TicketOrderExecution::getExecutionId)
            .filter(Objects::nonNull)
            .toList());
        refreshAfterDeletion(executions);
        return rows;
    }

    private List<Long> normalizeExecutionIds(Long[] executionIds) {
        return Arrays.stream(Objects.requireNonNullElse(executionIds, new Long[0]))
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

    private boolean isDeleteTerminal(String executionStatus) {
        return DELETE_TERMINAL_STATUSES.contains(StrUtil.blankToDefault(executionStatus, "").toLowerCase(Locale.ROOT));
    }

    private int logicDeleteOrderExecutions(Collection<Long> executionIds) {
        if (CollUtil.isEmpty(executionIds)) {
            return 0;
        }
        return orderExecutionMapper.update(null, Wrappers.<TicketOrderExecution>lambdaUpdate()
            .in(TicketOrderExecution::getExecutionId, executionIds)
            .eq(TicketOrderExecution::getDelFlag, 0L)
            .setSql("del_flag = execution_id"));
    }

    private void refreshAfterDeletion(List<TicketOrderExecution> executions) {
        if (CollUtil.isEmpty(executions)) {
            return;
        }
        executions.stream()
            .map(TicketOrderExecution::getTaskId)
            .filter(Objects::nonNull)
            .distinct()
            .forEach(ticketOpsService::selectSaleTaskById);
        executions.stream()
            .filter(execution -> execution.getBatchTaskId() != null
                || execution.getBatchItemId() != null
                || execution.getLotteryScheduleId() != null)
            .forEach(ticketLotteryBatchTaskService::refreshAfterExecution);
    }
}
