package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketEventConfig;

import java.util.Date;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketEventConfig.class, reverseConvertGenerate = false)
public class TicketEventConfigBo extends BaseEntity {

    private Long eventId;
    private Long platformId;
    private String eventCode;
    private String eventName;
    private Date saleTime;
    private String eventStatus;
    private String inventoryPolicy;
    private String remark;
}
