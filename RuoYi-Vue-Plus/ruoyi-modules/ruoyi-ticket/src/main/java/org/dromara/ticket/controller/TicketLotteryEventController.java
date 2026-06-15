package org.dromara.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.ticket.config.TicketPythonExecutorProperties;
import org.dromara.ticket.domain.TicketLotteryEventParseRecord;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.bo.TicketLotteryEventParseBo;
import org.dromara.ticket.domain.vo.TicketLotteryEventInfoVo;
import org.dromara.ticket.domain.vo.TicketLotteryEventSessionVo;
import org.dromara.ticket.mapper.TicketLotteryEventParseRecordMapper;
import org.dromara.ticket.mapper.TicketPlatformConfigMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Validated
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket/lottery-event")
public class TicketLotteryEventController {

    private final TicketPlatformConfigMapper platformMapper;
    private final TicketLotteryEventParseRecordMapper parseRecordMapper;
    private final TicketPythonExecutorProperties lotteryExecutorProperties;
    @Qualifier("ticketPythonStringRedisTemplate")
    private final StringRedisTemplate ticketPythonStringRedisTemplate;

    @SaCheckPermission("ticket:saleTask:add")
    @PostMapping("/parse")
    public R<TicketLotteryEventInfoVo> parse(@Validated @RequestBody TicketLotteryEventParseBo bo) {
        if (!lotteryExecutorProperties.isEnabled()) {
            throw new ServiceException("Python 抽票执行器未启用");
        }
        TicketPlatformConfig platform = platformMapper.selectById(bo.getPlatformId());
        if (platform == null) {
            throw new ServiceException("平台不存在");
        }
        String adapterType = platform.getAdapterType() == null ? platform.getPlatformCode() : platform.getAdapterType();
        if (!"livepocket".equalsIgnoreCase(adapterType) && !"hands-form".equalsIgnoreCase(adapterType)) {
            throw new ServiceException("当前平台暂不支持该抽票链接解析");
        }
        return R.ok(queueParseRecord(platform, bo.getEventUrl()));
    }

    @SaCheckPermission("ticket:saleTask:add")
    @GetMapping("/parse/{recordId}")
    public R<TicketLotteryEventInfoVo> parseDetail(@PathVariable Long recordId) {
        TicketLotteryEventParseRecord record = parseRecordMapper.selectById(recordId);
        if (record == null) {
            throw new ServiceException("解析记录不存在");
        }
        return R.ok(toEventInfo(record));
    }

    @SaCheckPermission("ticket:saleTask:add")
    @GetMapping("/history")
    public R<List<TicketLotteryEventInfoVo>> history(@RequestParam(required = false) Long platformId) {
        List<TicketLotteryEventParseRecord> records;
        try {
            records = parseRecordMapper.selectList(
                Wrappers.lambdaQuery(TicketLotteryEventParseRecord.class)
                    .select(
                        TicketLotteryEventParseRecord::getRecordId,
                        TicketLotteryEventParseRecord::getPlatformId,
                        TicketLotteryEventParseRecord::getEventUrl,
                        TicketLotteryEventParseRecord::getTicketEntryUrl,
                        TicketLotteryEventParseRecord::getEventTitle,
                        TicketLotteryEventParseRecord::getEntryStartTime,
                        TicketLotteryEventParseRecord::getEntryEndTime,
                        TicketLotteryEventParseRecord::getParseStatus,
                        TicketLotteryEventParseRecord::getParseRequestId,
                        TicketLotteryEventParseRecord::getParseMessage,
                        TicketLotteryEventParseRecord::getUpdateTime,
                        TicketLotteryEventParseRecord::getCreateTime
                    )
                    .eq(platformId != null, TicketLotteryEventParseRecord::getPlatformId, platformId)
                    .eq(TicketLotteryEventParseRecord::getParseStatus, "completed")
                    .orderByDesc(TicketLotteryEventParseRecord::getUpdateTime)
                    .orderByDesc(TicketLotteryEventParseRecord::getCreateTime)
                    .last("LIMIT 50")
            );
        } catch (RuntimeException ex) {
            if (!isLotteryParseRecordSchemaMissing(ex)) {
                throw ex;
            }
            log.warn("lottery event parse history skipped because table schema is not upgraded: {}", rootMessage(ex));
            return R.ok(List.of());
        }
        return R.ok(records.stream().map(this::toEventHistorySummary).collect(Collectors.toList()));
    }

    private boolean isLotteryParseRecordSchemaMissing(Throwable ex) {
        String message = rootMessage(ex);
        return message != null
            && message.contains("ticket_lottery_event_parse_record")
            && (message.contains("ticket_entry_url")
                || message.contains("parse_status")
                || message.contains("parse_request_id")
                || message.contains("parse_message"));
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? ex.getMessage() : current.getMessage();
    }

    private TicketLotteryEventInfoVo queueParseRecord(TicketPlatformConfig platform, String eventUrl) {
        String normalizedEventUrl = normalizeParseEventUrl(platform, eventUrl);
        String ticketEntryUrl = resolveParseTicketEntryUrl(platform, eventUrl);
        TicketLotteryEventParseRecord record = selectParseRecord(platform.getPlatformId(), normalizedEventUrl);
        String requestId = IdUtil.fastSimpleUUID();
        if (record == null) {
            record = new TicketLotteryEventParseRecord();
            record.setPlatformId(platform.getPlatformId());
            record.setEventUrl(normalizedEventUrl);
        }
        if (!ticketEntryUrl.isBlank()) {
            record.setTicketEntryUrl(ticketEntryUrl);
        }
        record.setParseStatus("queued");
        record.setParseRequestId(requestId);
        record.setParseMessage("活动解析已排队");
        if (record.getRecordId() == null) {
            try {
                parseRecordMapper.insert(record);
            } catch (RuntimeException ex) {
                if (!isDuplicateParseRecordKey(ex)) {
                    throw ex;
                }
                record = selectParseRecord(platform.getPlatformId(), normalizedEventUrl);
                if (record == null) {
                    throw ex;
                }
                if (!ticketEntryUrl.isBlank()) {
                    record.setTicketEntryUrl(ticketEntryUrl);
                }
                record.setParseStatus("queued");
                record.setParseRequestId(requestId);
                record.setParseMessage("活动解析已排队");
                parseRecordMapper.updateById(record);
            }
        } else {
            parseRecordMapper.updateById(record);
        }
        try {
            enqueueParseJob(platform, record, requestId);
        } catch (Exception ex) {
            record.setParseStatus("failed");
            record.setParseMessage("活动解析入队失败: " + ex.getMessage());
            parseRecordMapper.updateById(record);
            throw new ServiceException(record.getParseMessage());
        }
        return toEventInfo(record);
    }

    private TicketLotteryEventParseRecord selectParseRecord(Long platformId, String eventUrl) {
        return parseRecordMapper.selectOne(
            Wrappers.lambdaQuery(TicketLotteryEventParseRecord.class)
                .eq(TicketLotteryEventParseRecord::getPlatformId, platformId)
                .eq(TicketLotteryEventParseRecord::getEventUrl, eventUrl)
                .last("LIMIT 1")
        );
    }

    private boolean isDuplicateParseRecordKey(Throwable ex) {
        String message = rootMessage(ex);
        return message != null
            && message.contains("Duplicate entry")
            && message.contains("uk_ticket_lottery_parse_record");
    }

    private String normalizeParseEventUrl(TicketPlatformConfig platform, String rawUrl) {
        String url = trimUrl(rawUrl);
        if ("livepocket".equalsIgnoreCase(resolveAdapterType(platform)) && isLivePocketUrl(url)) {
            return normalizeLivePocketEventUrl(url);
        }
        return url;
    }

    private String resolveParseTicketEntryUrl(TicketPlatformConfig platform, String rawUrl) {
        String url = trimUrl(rawUrl);
        if ("livepocket".equalsIgnoreCase(resolveAdapterType(platform)) && isLivePocketTicketsUrl(url)) {
            return url;
        }
        return "";
    }

    private String resolveAdapterType(TicketPlatformConfig platform) {
        return platform.getAdapterType() == null ? platform.getPlatformCode() : platform.getAdapterType();
    }

    private boolean isLivePocketUrl(String url) {
        return url.contains("livepocket.jp/e/");
    }

    private boolean isLivePocketTicketsUrl(String url) {
        return isLivePocketUrl(url) && url.contains("/receptions/") && url.contains("/tickets");
    }

    private String normalizeLivePocketEventUrl(String rawUrl) {
        String url = trimUrl(rawUrl);
        try {
            URI uri = URI.create(url);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String marker = "/e/";
            int markerIndex = path.indexOf(marker);
            if (markerIndex < 0) {
                return url;
            }
            String eventPart = path.substring(markerIndex + marker.length());
            int slashIndex = eventPart.indexOf('/');
            String eventCode = slashIndex >= 0 ? eventPart.substring(0, slashIndex) : eventPart;
            if (eventCode.isBlank()) {
                return url;
            }
            String scheme = uri.getScheme() == null || uri.getScheme().isBlank() ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null || uri.getHost().isBlank() ? "livepocket.jp" : uri.getHost().toLowerCase(Locale.ROOT);
            return scheme + "://" + host + "/e/" + eventCode;
        } catch (Exception ex) {
            String marker = "livepocket.jp/e/";
            int markerIndex = url.indexOf(marker);
            if (markerIndex < 0) {
                return url;
            }
            String eventPart = url.substring(markerIndex + marker.length()).split("[/?#]", 2)[0];
            return eventPart.isBlank() ? url : "https://livepocket.jp/e/" + eventPart;
        }
    }

    private String trimUrl(String rawUrl) {
        return rawUrl == null ? "" : rawUrl.trim().split("[?#]", 2)[0];
    }

    private void enqueueParseJob(TicketPlatformConfig platform, TicketLotteryEventParseRecord record, String requestId) {
        String adapterType = platform.getAdapterType() == null ? platform.getPlatformCode() : platform.getAdapterType();
        JSONObject payload = JSONUtil.createObj()
            .set("recordId", record.getRecordId())
            .set("requestId", requestId)
            .set("platformId", record.getPlatformId())
            .set("platformCode", adapterType)
            .set("sourcePlatformCode", platform.getPlatformCode())
            .set("eventUrl", record.getEventUrl())
            .set("queuedAt", System.currentTimeMillis());
        runRedisQueueWriteWithRetry("活动解析入队", () -> {
            ticketPythonStringRedisTemplate.opsForValue().set(
                lotteryExecutorProperties.getEventParseJobKeyPrefix() + requestId,
                payload.toString(),
                Duration.ofSeconds(Math.max(lotteryExecutorProperties.getEventParseJobTtlSeconds(), 60L))
            );
            Map<String, String> message = new LinkedHashMap<>();
            message.put("requestId", requestId);
            message.put("recordId", String.valueOf(record.getRecordId()));
            message.put("enqueuedAt", String.valueOf(System.currentTimeMillis()));
            ticketPythonStringRedisTemplate.opsForStream().add(
                StreamRecords.mapBacked(message).withStreamKey(lotteryExecutorProperties.getEventParseReadyStreamKey())
            );
        });
    }

    private void runRedisQueueWriteWithRetry(String operationName, Runnable action) {
        int maxAttempts = 3;
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                action.run();
                if (attempt > 1) {
                    log.info("{} Redis 写入重试成功: attempt={}", operationName, attempt);
                }
                return;
            } catch (RuntimeException ex) {
                lastException = ex;
                if (attempt >= maxAttempts || !isRedisConnectionAcquireFailure(ex)) {
                    throw ex;
                }
                log.warn("{} Redis 连接池繁忙，准备重试: attempt={}/{}, message={}",
                    operationName, attempt, maxAttempts, rootMessage(ex));
                sleepQuietly(250L * attempt);
            }
        }
        throw lastException;
    }

    private boolean isRedisConnectionAcquireFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null && current.getCause() != current) {
            String message = current.getMessage();
            if (message != null && (message.contains("Unable to acquire connection")
                || message.contains("Redis connection")
                || message.contains("CancellationException"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ServiceException("活动解析入队重试被中断");
        }
    }

    private TicketLotteryEventInfoVo toEventInfo(TicketLotteryEventParseRecord record) {
        TicketLotteryEventInfoVo vo = new TicketLotteryEventInfoVo();
        vo.setRecordId(record.getRecordId());
        vo.setPlatformId(record.getPlatformId());
        vo.setEventUrl(record.getEventUrl());
        vo.setTicketEntryUrl(record.getTicketEntryUrl());
        vo.setEventTitle(record.getEventTitle());
        vo.setEntryStartTime(record.getEntryStartTime());
        vo.setEntryEndTime(record.getEntryEndTime());
        vo.setSessions(JsonUtils.parseArray(record.getSessionsJson(), TicketLotteryEventSessionVo.class));
        vo.setRawSummary(record.getRawSummary());
        vo.setCacheHit(false);
        vo.setParsedAt(record.getUpdateTime() != null ? record.getUpdateTime().toString() : null);
        vo.setParseStatus(record.getParseStatus());
        vo.setParseRequestId(record.getParseRequestId());
        vo.setParseMessage(record.getParseMessage());
        return vo;
    }

    private TicketLotteryEventInfoVo toEventHistorySummary(TicketLotteryEventParseRecord record) {
        TicketLotteryEventInfoVo vo = new TicketLotteryEventInfoVo();
        vo.setRecordId(record.getRecordId());
        vo.setPlatformId(record.getPlatformId());
        vo.setEventUrl(record.getEventUrl());
        vo.setTicketEntryUrl(record.getTicketEntryUrl());
        vo.setEventTitle(record.getEventTitle());
        vo.setEntryStartTime(record.getEntryStartTime());
        vo.setEntryEndTime(record.getEntryEndTime());
        vo.setSessions(List.of());
        vo.setCacheHit(false);
        vo.setParsedAt(record.getUpdateTime() != null ? record.getUpdateTime().toString() : null);
        vo.setParseStatus(record.getParseStatus());
        vo.setParseRequestId(record.getParseRequestId());
        vo.setParseMessage(record.getParseMessage());
        return vo;
    }
}
