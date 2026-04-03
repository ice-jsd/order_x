package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketLoginBatch;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketLoginBatch.class)
public class TicketLoginBatchVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long batchId;
    private String tenantId;
    private Long platformId;
    private String batchNo;
    private String batchStatus;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    private String resultSummary;
    private Date executedAt;
    private String platformName;
}
