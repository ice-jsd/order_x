package org.dromara.ticket.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.adapter.TicketOrderFlowSupport;
import org.dromara.ticket.domain.TicketOrderExecution;
import org.dromara.ticket.domain.TicketSaleTask;
import org.dromara.ticket.mapper.TicketOrderExecutionMapper;
import org.dromara.ticket.mapper.TicketSaleTaskMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketLotteryLinkOccupancyService {

    private static final Set<String> OCCUPIED_EXECUTION_STATUSES = Set.of("submitted", "pending_payment", "paid", "completed");
    private static final Comparator<TicketOrderExecution> OCCUPANCY_ORDER = Comparator
        .comparing(TicketOrderExecution::getExecutedAt, Comparator.nullsLast(Date::compareTo))
        .thenComparing(TicketOrderExecution::getStartedAt, Comparator.nullsLast(Date::compareTo))
        .thenComparing(TicketOrderExecution::getExecutionId, Comparator.nullsLast(Long::compareTo))
        .reversed();

    private final TicketOrderExecutionMapper orderExecutionMapper;
    private final TicketSaleTaskMapper saleTaskMapper;

    public Map<Long, OccupancyInfo> query(Collection<Long> accountIds, Collection<Long> platformIds, String eventUrl, Long excludeTaskId) {
        String normalizedEventUrl = TicketOrderFlowSupport.normalizeLotteryEventUrl(eventUrl);
        List<Long> targetAccountIds = (accountIds == null ? List.<Long>of() : accountIds).stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        List<Long> targetPlatformIds = (platformIds == null ? List.<Long>of() : platformIds).stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (StringUtils.isBlank(normalizedEventUrl) || CollUtil.isEmpty(targetAccountIds)) {
            return Map.of();
        }

        List<TicketOrderExecution> indexedExecutions = sortExecutions(orderExecutionMapper.selectLotteryOccupanciesByEvent(
            targetAccountIds,
            targetPlatformIds,
            normalizedEventUrl,
            excludeTaskId
        ));
        Map<Long, OccupancyInfo> occupiedMap = new HashMap<>(buildMap(indexedExecutions));

        List<Long> fallbackAccountIds = targetAccountIds.stream()
            .filter(accountId -> !occupiedMap.containsKey(accountId))
            .toList();
        if (CollUtil.isEmpty(fallbackAccountIds)) {
            return occupiedMap;
        }

        List<TicketOrderExecution> fallbackExecutions = new ArrayList<>();
        fallbackExecutions.addAll(orderExecutionMapper.selectLotteryOccupanciesWithBlankEventUrl(
            fallbackAccountIds,
            targetPlatformIds,
            excludeTaskId
        ));
        fallbackExecutions.addAll(orderExecutionMapper.selectLotteryOccupanciesWithNullEventUrl(
            fallbackAccountIds,
            targetPlatformIds,
            excludeTaskId
        ));
        occupiedMap.putAll(querySnapshotFallback(sortExecutions(fallbackExecutions), normalizedEventUrl));
        return occupiedMap;
    }

    private List<TicketOrderExecution> sortExecutions(List<TicketOrderExecution> executions) {
        if (CollUtil.isEmpty(executions)) {
            return List.of();
        }
        return executions.stream()
            .filter(Objects::nonNull)
            .sorted(OCCUPANCY_ORDER)
            .toList();
    }

    private Map<Long, OccupancyInfo> buildMap(List<TicketOrderExecution> executions) {
        if (CollUtil.isEmpty(executions)) {
            return Map.of();
        }
        Map<Long, TicketSaleTask> taskMap = loadTaskMap(executions);
        Map<Long, OccupancyInfo> occupiedMap = new HashMap<>();
        for (TicketOrderExecution execution : executions) {
            Long accountId = execution.getAccountId();
            if (accountId == null || occupiedMap.containsKey(accountId)) {
                continue;
            }
            occupiedMap.put(accountId, toInfo(execution, taskMap.get(execution.getTaskId())));
        }
        return occupiedMap;
    }

    private Map<Long, OccupancyInfo> querySnapshotFallback(List<TicketOrderExecution> executions, String normalizedEventUrl) {
        if (CollUtil.isEmpty(executions)) {
            return Map.of();
        }
        Map<Long, TicketSaleTask> taskMap = loadTaskMap(executions);
        Map<Long, OccupancyInfo> occupiedMap = new HashMap<>();
        for (TicketOrderExecution execution : executions) {
            Long accountId = execution.getAccountId();
            if (accountId == null || occupiedMap.containsKey(accountId)) {
                continue;
            }
            TicketSaleTask task = taskMap.get(execution.getTaskId());
            String sourceOptions = StringUtils.defaultIfBlank(execution.getConfigSnapshot(), task == null ? null : task.getTaskOptions());
            if (!normalizedEventUrl.equals(TicketOrderFlowSupport.resolveLotteryEventUrlFromTaskOptions(sourceOptions))) {
                continue;
            }
            backfill(execution.getExecutionId(), normalizedEventUrl);
            occupiedMap.put(accountId, toInfo(execution, task));
        }
        return occupiedMap;
    }

    private Map<Long, TicketSaleTask> loadTaskMap(List<TicketOrderExecution> executions) {
        List<Long> taskIds = executions.stream()
            .map(TicketOrderExecution::getTaskId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (CollUtil.isEmpty(taskIds)) {
            return Map.of();
        }
        return saleTaskMapper.selectByIds(taskIds).stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(TicketSaleTask::getTaskId, Function.identity(), (left, right) -> left));
    }

    private OccupancyInfo toInfo(TicketOrderExecution execution, TicketSaleTask task) {
        return new OccupancyInfo(
            execution.getTaskId(),
            task == null ? null : task.getTaskName(),
            ObjectUtil.defaultIfNull(execution.getExecutedAt(), execution.getStartedAt()),
            execution.getExecutionStatus()
        );
    }

    private void backfill(Long executionId, String normalizedEventUrl) {
        if (ObjectUtil.isNull(executionId) || StringUtils.isBlank(normalizedEventUrl)) {
            return;
        }
        try {
            orderExecutionMapper.update(null, Wrappers.lambdaUpdate(TicketOrderExecution.class)
                .eq(TicketOrderExecution::getExecutionId, executionId)
                .and(wrapper -> wrapper.isNull(TicketOrderExecution::getLotteryEventUrl).or().eq(TicketOrderExecution::getLotteryEventUrl, ""))
                .set(TicketOrderExecution::getLotteryEventUrl, normalizedEventUrl));
        } catch (Exception ex) {
            log.warn("backfill lottery_event_url failed, executionId={}, lotteryEventUrl={}", executionId, normalizedEventUrl, ex);
        }
    }

    public record OccupancyInfo(Long taskId, String taskName, Date occupiedAt, String executionStatus) {
    }
}
