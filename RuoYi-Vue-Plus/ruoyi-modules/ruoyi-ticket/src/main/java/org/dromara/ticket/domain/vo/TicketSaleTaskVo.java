package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketSaleTask;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketSaleTask.class)
public class TicketSaleTaskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long taskId;
    private String tenantId;
    private Long platformId;
    private Long eventId;
    private String taskName;
    private String taskMode;
    private String taskStatus;
    private Date warmupTime;
    private Date scheduledTime;
    private Date lastExecutedTime;
    private String ruleConfig;
    private String remark;
    private String platformName;
    private String eventName;
}
