package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketOrderExecution;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketOrderExecution.class)
public class TicketOrderExecutionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long executionId;
    private String tenantId;
    private Long taskId;
    private Long platformId;
    private Long accountId;
    private String orderNo;
    private String executionStatus;
    private String resultMessage;
    private Date executedAt;
    private String platformName;
    private String accountNo;
    private String taskName;
}
