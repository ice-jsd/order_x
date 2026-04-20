package org.dromara.ticket.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_order_execution")
public class TicketOrderExecution extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "execution_id")
    private Long executionId;

    private Long taskId;
    private Long platformId;
    private Long accountId;
    private String purchaseType;
    private Integer purchaseQuantity;
    private String configSnapshot;
    private Long scheduleVersion;
    private String currentStep;
    private String stepStatus;
    private String stepTrace;
    private String paymentStatus;
    private String orderNo;
    private String executionStatus;
    private String resultMessage;
    private String rawResult;
    private String workerId;
    private Integer attemptCount;
    private Date heartbeatAt;
    private Date startedAt;
    private Date executedAt;

    @TableLogic
    private Long delFlag;
}
