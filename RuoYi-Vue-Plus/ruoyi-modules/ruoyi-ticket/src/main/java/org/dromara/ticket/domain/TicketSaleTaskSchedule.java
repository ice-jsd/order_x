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
@TableName("ticket_sale_task_schedule")
public class TicketSaleTaskSchedule extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "schedule_id")
    private Long scheduleId;

    private Long taskId;
    private Date scheduledTime;
    private String sessionId;
    private String sessionLabel;
    private Integer accountCount;
    private String scheduleStatus;
    private Date dispatchedTime;
    private Date finishedTime;
    private String resultMessage;

    @TableLogic
    private Long delFlag;
}
