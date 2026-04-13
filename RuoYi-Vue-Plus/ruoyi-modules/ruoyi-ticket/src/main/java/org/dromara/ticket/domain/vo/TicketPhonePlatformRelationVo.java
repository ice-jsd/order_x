package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketPhonePlatformRelation;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketPhonePlatformRelation.class)
public class TicketPhonePlatformRelationVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long relationId;
    private String tenantId;
    private Long phoneId;
    private Long platformId;
    private Long accountId;
    private String status;
    private String lastError;
    private Date lastOperateTime;
    private String phoneNumber;
    private String platformName;
    private String email;
}
