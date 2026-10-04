package org.dromara.ticket.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.config.TicketPythonExecutorProperties;
import org.dromara.ticket.service.TicketPythonQueueHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class TicketPythonQueueWorker {

    private final TicketPythonExecutorProperties ticketPythonExecutorProperties;
    private final TicketPythonQueueHandler queueHandler;
    @Qualifier("ticketPythonStringRedisTemplate")
    private final StringRedisTemplate ticketPythonStringRedisTemplate;

    @Scheduled(initialDelay = 8000L, fixedDelayString = "${ticket.python-executor.delayed-poll-interval-ms:1000}")
    public void promoteDueLotterySchedules() {
        try {
            if (!ticketPythonExecutorProperties.isEnabled()) {
                return;
            }
            int batchSize = Math.max(ticketPythonExecutorProperties.getDelayedPromoteBatchSize(), 1);
            Set<String> executionIds = ticketPythonStringRedisTemplate.opsForZSet().rangeByScore(
                ticketPythonExecutorProperties.getDelayedZsetKey(),
                0,
                System.currentTimeMillis(),
                0,
                batchSize
            );
            if (CollUtil.isEmpty(executionIds)) {
                return;
            }
            for (String executionId : executionIds) {
                promoteDueLotteryExecution(executionId);
            }
        } catch (Exception ex) {
            log.error("promote due lottery schedules failed", ex);
        }
    }

    private void promoteDueLotteryExecution(String executionId) {
        Long id = Convert.toLong(executionId, null);
        if (id == null) {
            ticketPythonStringRedisTemplate.opsForZSet().remove(ticketPythonExecutorProperties.getDelayedZsetKey(), executionId);
            log.warn("invalid lottery delayed execution id removed, executionId={}", executionId);
            return;
        }
        Long removed = ticketPythonStringRedisTemplate.opsForZSet().remove(
            ticketPythonExecutorProperties.getDelayedZsetKey(),
            executionId
        );
        if (removed == null || removed <= 0) {
            return;
        }
        try {
            queueHandler.promoteLotteryExecution(id);
        } catch (Exception ex) {
            long retryDelayMs = Math.max(ticketPythonExecutorProperties.getDelayedPromoteRetryDelayMs(), 1000);
            long retryAt = System.currentTimeMillis() + retryDelayMs;
            try {
                ticketPythonStringRedisTemplate.opsForZSet().add(
                    ticketPythonExecutorProperties.getDelayedZsetKey(),
                    executionId,
                    retryAt
                );
                log.error(
                    "promote lottery delayed execution failed and requeued, executionId={}, retryAt={}",
                    executionId,
                    retryAt,
                    ex
                );
            } catch (Exception requeueEx) {
                ex.addSuppressed(requeueEx);
                log.error(
                    "promote lottery delayed execution failed and requeue also failed, executionId={}",
                    executionId,
                    ex
                );
            }
        }
    }

    @Scheduled(initialDelay = 9000L, fixedDelayString = "${ticket.python-executor.queue-poll-interval-ms:1000}")
    public void consumeLotteryScheduleQueue() {
        consumeLivePocketRegisterResultStream();
        consumeLivePocketLoginResultStream();
        consumeLotteryResultStream();
        consumeLotteryEventParseResultStream();
    }

    private void consumeLivePocketRegisterResultStream() {
        consumeResultStream(
            ticketPythonExecutorProperties.getRegisterResultStreamKey(),
            ticketPythonExecutorProperties.getRegisterResultLastIdKey(),
            "livepocket register",
            queueHandler::applyLivePocketRegisterResult
        );
    }

    private void consumeLivePocketLoginResultStream() {
        consumeResultStream(
            ticketPythonExecutorProperties.getLoginResultStreamKey(),
            ticketPythonExecutorProperties.getLoginResultLastIdKey(),
            "livepocket login",
            queueHandler::applyLivePocketLoginResult
        );
    }

    private void consumeLotteryResultStream() {
        consumeResultStream(
            ticketPythonExecutorProperties.getResultStreamKey(),
            ticketPythonExecutorProperties.getResultLastIdKey(),
            "lottery",
            queueHandler::applyLotteryResult
        );
    }

    private void consumeLotteryEventParseResultStream() {
        consumeResultStream(
            ticketPythonExecutorProperties.getEventParseResultStreamKey(),
            ticketPythonExecutorProperties.getEventParseResultLastIdKey(),
            "lottery event parse",
            queueHandler::applyLotteryEventParseResult
        );
    }

    private void consumeResultStream(String streamKey,
                                     String lastIdKey,
                                     String actionName,
                                     ResultEventHandler eventHandler) {
        try {
            if (!ticketPythonExecutorProperties.isEnabled()) {
                return;
            }
            int batchSize = Math.max(ticketPythonExecutorProperties.getQueueConsumeBatchSize(), 1);
            String lastId = StringUtils.defaultIfBlank(
                ticketPythonStringRedisTemplate.opsForValue().get(lastIdKey),
                "0-0"
            );
            List<MapRecord<String, Object, Object>> records = readResultRecords(streamKey, lastId, batchSize);
            if (CollUtil.isEmpty(records)) {
                return;
            }
            String newestId = lastId;
            for (MapRecord<String, Object, Object> record : records) {
                eventHandler.handle(record.getValue());
                newestId = record.getId().getValue();
            }
            ticketPythonStringRedisTemplate.opsForValue().set(lastIdKey, newestId);
        } catch (Exception ex) {
            log.error("consume {} result stream failed", actionName, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<MapRecord<String, Object, Object>> readResultRecords(String streamKey, String lastId, int batchSize) {
        StreamOperations<String, Object, Object> streamOperations = ticketPythonStringRedisTemplate.opsForStream();
        return streamOperations.read(
            StreamReadOptions.empty().count(batchSize),
            StreamOffset.create(streamKey, ReadOffset.from(lastId))
        );
    }

    @FunctionalInterface
    private interface ResultEventHandler {
        void handle(Map<Object, Object> fields);
    }
}
