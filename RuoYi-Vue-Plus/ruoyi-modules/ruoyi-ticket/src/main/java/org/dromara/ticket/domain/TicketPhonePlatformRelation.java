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
@TableName("ticket_phone_platform_relation")
public class TicketPhonePlatformRelation extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "relation_id")
    private Long relationId;

    private Long phoneId;
    private Long platformId;
    private Long accountId;
    private String status;
    private String lastError;
    private Date lastOperateTime;

    @TableLogic
    private Long delFlag;
}
