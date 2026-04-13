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
@TableName("ticket_sale_task_account")
public class TicketSaleTaskAccount extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "binding_id")
    private Long bindingId;

    private Long taskId;
    private Long accountId;

    @TableLogic
    private Long delFlag;
}
