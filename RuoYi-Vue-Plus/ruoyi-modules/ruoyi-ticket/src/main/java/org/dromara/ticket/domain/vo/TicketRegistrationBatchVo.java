package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketRegistrationBatch;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketRegistrationBatch.class)
public class TicketRegistrationBatchVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long batchId;
    private String tenantId;
    private Long platformId;
    private String batchNo;
    private String batchStatus;
    private Integer totalCount;
    private Integer successCount;
    private Integer skippedCount;
    private Integer failedCount;
    private String resultSummary;
    private Date executedAt;
    private String platformName;
}
