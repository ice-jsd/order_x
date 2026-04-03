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
@TableName("ticket_audit_event")
public class TicketAuditEvent extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "audit_id")
    private Long auditId;

    private String moduleName;
    private String actionType;
    private String businessType;
    private String businessKey;
    private String auditStatus;
    private String message;
    private String payload;
    private Date eventTime;

    @TableLogic
    private Long delFlag;
}
