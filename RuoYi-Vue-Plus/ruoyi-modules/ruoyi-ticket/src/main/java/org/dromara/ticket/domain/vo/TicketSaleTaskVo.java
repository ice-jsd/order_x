package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketSaleTask;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
@AutoMapper(target = TicketSaleTask.class)
public class TicketSaleTaskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long taskId;
    private String tenantId;
    private Long platformId;
    private String taskName;
    private String taskStatus;
    private String purchaseType;
    private String configSchemaKey;
    private Date warmupTime;
    private Date scheduledTime;
    private Date lastExecutedTime;
    private Integer purchaseQuantity;
    private String taskOptions;
    private String remark;
    private String platformName;
    private List<Long> accountIds;
    private Integer boundAccountCount;
    private String accountEmails;
}
