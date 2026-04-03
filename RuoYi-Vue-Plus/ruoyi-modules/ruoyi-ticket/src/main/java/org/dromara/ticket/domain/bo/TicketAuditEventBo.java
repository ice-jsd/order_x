package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketAuditEvent;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketAuditEvent.class, reverseConvertGenerate = false)
public class TicketAuditEventBo extends BaseEntity {

    private Long auditId;
    private String moduleName;
    private String actionType;
    private String businessType;
    private String businessKey;
    private String auditStatus;
}
