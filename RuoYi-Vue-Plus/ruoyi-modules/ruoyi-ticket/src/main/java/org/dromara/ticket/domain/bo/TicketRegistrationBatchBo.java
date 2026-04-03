package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketRegistrationBatch;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketRegistrationBatch.class, reverseConvertGenerate = false)
public class TicketRegistrationBatchBo extends BaseEntity {

    private Long batchId;
    private Long platformId;
    private String batchNo;
    private String batchStatus;
}
