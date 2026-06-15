package org.dromara.ticket.service;

import java.util.Map;

/**
 * Internal callback boundary for Python executor queue events.
 */
public interface TicketPythonQueueHandler {

    void promoteLotteryExecution(Long executionId);

    void applyLivePocketRegisterResult(Map<Object, Object> fields);

    void applyLivePocketLoginResult(Map<Object, Object> fields);

    void applyLotteryResult(Map<Object, Object> fields);

    void applyLotteryEventParseResult(Map<Object, Object> fields);
}
