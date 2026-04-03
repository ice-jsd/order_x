package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketSaleTask;

import java.util.Date;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketSaleTask.class, reverseConvertGenerate = false)
public class TicketSaleTaskBo extends BaseEntity {

    private Long taskId;
    private Long platformId;
    private Long eventId;
    private String taskName;
    private String taskMode;
    private String taskStatus;
    private Date warmupTime;
    private Date scheduledTime;
    private String ruleConfig;
    private String remark;
}
