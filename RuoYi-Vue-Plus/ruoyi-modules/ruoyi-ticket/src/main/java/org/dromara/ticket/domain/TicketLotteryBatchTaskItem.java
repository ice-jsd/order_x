package org.dromara.ticket.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_lottery_batch_task_item")
public class TicketLotteryBatchTaskItem extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "batch_item_id")
    private Long batchItemId;

    private Long batchTaskId;
    private String eventUrl;
    private String eventTitle;
    private String receptionId;
    private String receptionTitle;
    private String salesType;
    private String selectedSessionsJson;
    private String itemStatus;

    @TableLogic
    private Long delFlag;
}
