package org.dromara.ticket.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.config.TicketOrderExecutorProperties;
import org.dromara.ticket.domain.dto.TicketOrderDispatchRequest;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class TicketOrderExecutorClient {

    private final TicketOrderExecutorProperties properties;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final HttpClient httpClient = HttpClient.newBuilder().build();

    public void dispatch(TicketOrderDispatchRequest payload) {
        String mode = StringUtils.defaultIfBlank(properties.getMode(), "redis");
        if ("http".equalsIgnoreCase(mode)) {
            dispatchByHttp(payload);
            return;
        }
        dispatchByRedis(payload);
    }

    public void dispatchByRedis(TicketOrderDispatchRequest payload) {
        validatePayload(payload);
        try {
            String jobKey = properties.getJobKeyPrefix() + payload.getExecutionId();
            String body = objectMapper.writeValueAsString(payload);
            stringRedisTemplate.opsForValue().set(jobKey, body, Duration.ofSeconds(properties.getJobTtlSeconds()));

            long dispatchAt = resolveDispatchEpochMillis(payload);
            long now = System.currentTimeMillis();
            if (dispatchAt > now) {
                stringRedisTemplate.opsForZSet().add(
                    properties.getDelayedZsetKey(),
                    String.valueOf(payload.getExecutionId()),
                    dispatchAt
                );
                log.info("ticket order queued in delayed zset, executionId={}, taskId={}, dispatchAt={}", payload.getExecutionId(), payload.getTaskId(), dispatchAt);
                return;
            }

            addToReadyStream(payload.getExecutionId());
            log.info("ticket order pushed to ready stream, executionId={}, taskId={}", payload.getExecutionId(), payload.getTaskId());
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("ticket order redis dispatch failed, executionId={}, taskId={}", payload.getExecutionId(), payload.getTaskId(), ex);
            throw new ServiceException("Redis 抢购任务入队异常: " + ex.getMessage());
        }
    }

    public void dispatchByHttp(TicketOrderDispatchRequest payload) {
        if (StringUtils.isBlank(properties.getBaseUrl())) {
            throw new ServiceException("未配置 Go 执行器地址");
        }
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + properties.getDispatchPath()))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ServiceException("Go 执行器调度失败: HTTP " + response.statusCode());
            }
            log.info("ticket order dispatched by http, executionId={}, taskId={}, status={}", payload.getExecutionId(), payload.getTaskId(), response.statusCode());
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("ticket order http dispatch failed, executionId={}, taskId={}", payload.getExecutionId(), payload.getTaskId(), ex);
            throw new ServiceException("Go 执行器调度异常: " + ex.getMessage());
        }
    }

    public RecordId addToReadyStream(Long executionId) {
        Map<String, String> message = new HashMap<>(2);
        message.put("executionId", String.valueOf(executionId));
        message.put("enqueuedAt", String.valueOf(System.currentTimeMillis()));
        return stringRedisTemplate.opsForStream().add(
            StreamRecords.mapBacked(message).withStreamKey(properties.getStreamKey())
        );
    }

    public void removeDelayedExecution(Long executionId) {
        if (executionId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().remove(properties.getDelayedZsetKey(), String.valueOf(executionId));
    }

    private void validatePayload(TicketOrderDispatchRequest payload) {
        if (payload == null || payload.getExecutionId() == null) {
            throw new ServiceException("抢购任务缺少 executionId");
        }
    }

    private long resolveDispatchEpochMillis(TicketOrderDispatchRequest payload) {
        long now = System.currentTimeMillis();
        Long scheduledAt = payload.getScheduledTime() == null ? null : payload.getScheduledTime().getTime();
        Long warmupAt = payload.getWarmupTime() == null ? null : payload.getWarmupTime().getTime();
        if (warmupAt != null && warmupAt > now && (scheduledAt == null || warmupAt < scheduledAt)) {
            return warmupAt;
        }
        if (scheduledAt != null && scheduledAt > now) {
            long leadMs = Math.max(properties.getAutoWarmupLeadMs(), 0L);
            return Math.max(now, scheduledAt - leadMs);
        }
        return 0L;
    }
}
