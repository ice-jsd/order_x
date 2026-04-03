package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketAuditEvent;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketAuditEvent.class)
public class TicketAuditEventVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long auditId;
    private String tenantId;
    private String moduleName;
    private String actionType;
    private String businessType;
    private String businessKey;
    private String auditStatus;
    private String message;
    private String payload;
    private Date eventTime;
}
