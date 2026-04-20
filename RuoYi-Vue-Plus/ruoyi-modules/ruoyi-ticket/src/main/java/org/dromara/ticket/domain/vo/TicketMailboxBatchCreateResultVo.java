package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class TicketMailboxBatchCreateResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private int requestedCount;
    private int successCount;
    private int failedCount;
    private int attemptCount;
    private List<String> createdEmails = new ArrayList<>();
    private List<String> failedMessages = new ArrayList<>();
}
