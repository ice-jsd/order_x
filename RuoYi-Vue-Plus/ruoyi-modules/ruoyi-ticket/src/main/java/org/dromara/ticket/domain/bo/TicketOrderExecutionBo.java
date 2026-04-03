package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketOrderExecution;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketOrderExecution.class, reverseConvertGenerate = false)
public class TicketOrderExecutionBo extends BaseEntity {

    private Long executionId;
    private Long taskId;
    private Long platformId;
    private Long accountId;
    private String orderNo;
    private String executionStatus;
}
