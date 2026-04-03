package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketEventConfig;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketEventConfig.class)
public class TicketEventConfigVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long eventId;
    private String tenantId;
    private Long platformId;
    private String eventCode;
    private String eventName;
    private Date saleTime;
    private String eventStatus;
    private String inventoryPolicy;
    private String remark;
    private String platformName;
}
