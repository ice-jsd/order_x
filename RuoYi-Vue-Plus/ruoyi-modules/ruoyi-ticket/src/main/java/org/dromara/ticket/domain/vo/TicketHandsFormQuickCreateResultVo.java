package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class TicketHandsFormQuickCreateResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private int requestedCount;
    private int successCount;
    private int failedCount;
    private List<Long> createdAccountIds = new ArrayList<>();
    private List<String> createdEmails = new ArrayList<>();
    private List<String> failedMessages = new ArrayList<>();
}
