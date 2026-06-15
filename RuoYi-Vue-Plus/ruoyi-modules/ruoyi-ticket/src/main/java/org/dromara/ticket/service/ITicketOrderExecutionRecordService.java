package org.dromara.ticket.service;

import java.util.Date;

public interface ITicketOrderExecutionRecordService {

    int removeOrderExecutions(Long[] executionIds);

    int cleanupOldOrderExecutions(Date cutoffTime, int batchSize);
}
