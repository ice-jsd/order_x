package org.dromara.ticket.service.impl;

import cn.hutool.core.date.DateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ticket.config.TicketRecordCleanupProperties;
import org.dromara.ticket.service.ITicketMailboxAccountService;
import org.dromara.ticket.service.ITicketOrderExecutionRecordService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
@Slf4j
@RequiredArgsConstructor
public class TicketRecordCleanupService {

    private final TicketRecordCleanupProperties properties;
    private final ITicketMailboxAccountService mailboxAccountService;
    private final ITicketOrderExecutionRecordService orderExecutionRecordService;

    @Scheduled(cron = "${ticket.record-cleanup.cron:0 30 3 * * ?}")
    public void cleanupRecords() {
        if (!properties.isEnabled()) {
            return;
        }
        int batchSize = normalizeBatchSize(properties.getBatchSize());
        cleanupMailRecords(batchSize);
        cleanupOrderExecutions(batchSize);
    }

    private void cleanupMailRecords(int batchSize) {
        int retentionDays = properties.getMailRecordRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        Date cutoffTime = DateUtil.offsetDay(new Date(), -retentionDays);
        try {
            int deleted = cleanupInBatches(() -> mailboxAccountService.cleanupOldMailRecords(cutoffTime, batchSize), batchSize);
            log.info("ticket mail record cleanup completed, retentionDays={}, cutoffTime={}, deleted={}",
                retentionDays, DateUtil.formatDateTime(cutoffTime), deleted);
        } catch (Exception ex) {
            log.warn("ticket mail record cleanup failed, retentionDays={}, cutoffTime={}",
                retentionDays, DateUtil.formatDateTime(cutoffTime), ex);
        }
    }

    private void cleanupOrderExecutions(int batchSize) {
        int retentionDays = properties.getOrderExecutionRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        Date cutoffTime = DateUtil.offsetDay(new Date(), -retentionDays);
        try {
            int deleted = cleanupInBatches(() -> orderExecutionRecordService.cleanupOldOrderExecutions(cutoffTime, batchSize), batchSize);
            log.info("ticket order execution cleanup completed, retentionDays={}, cutoffTime={}, deleted={}",
                retentionDays, DateUtil.formatDateTime(cutoffTime), deleted);
        } catch (Exception ex) {
            log.warn("ticket order execution cleanup failed, retentionDays={}, cutoffTime={}",
                retentionDays, DateUtil.formatDateTime(cutoffTime), ex);
        }
    }

    private int cleanupInBatches(CleanupBatch cleanupBatch, int batchSize) {
        int total = 0;
        while (true) {
            int deleted = cleanupBatch.cleanup();
            total += deleted;
            if (deleted <= 0 || deleted < batchSize) {
                return total;
            }
        }
    }

    private int normalizeBatchSize(int batchSize) {
        if (batchSize <= 0) {
            return 1000;
        }
        return Math.min(batchSize, 5000);
    }

    @FunctionalInterface
    private interface CleanupBatch {
        int cleanup();
    }
}
