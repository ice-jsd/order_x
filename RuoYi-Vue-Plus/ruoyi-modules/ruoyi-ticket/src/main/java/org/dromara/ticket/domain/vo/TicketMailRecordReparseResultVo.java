package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
public class TicketMailRecordReparseResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private boolean started;
    private boolean running;
    private String message;
    private long totalScanned;
    private long updatedCount;
    private long unknownToAppliedCount;
    private long unknownToSelectedCount;
    private long unknownToRejectedCount;
    private long unknownToPurchaseCompletedCount;
    private long failedCount;
    private Date startedAt;
    private Date finishedAt;
}
