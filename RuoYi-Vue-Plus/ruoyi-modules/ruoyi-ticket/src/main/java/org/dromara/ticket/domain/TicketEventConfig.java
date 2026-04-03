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
@TableName("ticket_event_config")
public class TicketEventConfig extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "event_id")
    private Long eventId;

    private Long platformId;
    private String eventCode;
    private String eventName;
    private Date saleTime;
    private String eventStatus;
    private String inventoryPolicy;
    private String remark;

    @TableLogic
    private Long delFlag;
}
