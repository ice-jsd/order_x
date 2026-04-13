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
@TableName("ticket_sale_task")
public class TicketSaleTask extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "task_id")
    private Long taskId;

    private Long platformId;
    private String productId;
    private String taskName;
    private String taskStatus;
    private Long scheduleVersion;
    private String orderFlowType;
    private String fulfillmentType;
    private String paymentMode;
    private Date warmupTime;
    private Date scheduledTime;
    private Date lastExecutedTime;
    private Integer purchaseQuantity;
    private String taskOptions;
    private String remark;

    @TableLogic
    private Long delFlag;
}
