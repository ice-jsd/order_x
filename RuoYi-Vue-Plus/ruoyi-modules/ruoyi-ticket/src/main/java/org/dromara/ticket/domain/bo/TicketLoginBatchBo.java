package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketLoginBatch;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketLoginBatch.class, reverseConvertGenerate = false)
public class TicketLoginBatchBo extends BaseEntity {

    private Long batchId;
    private Long platformId;
    private String batchNo;
    private String batchStatus;
}
