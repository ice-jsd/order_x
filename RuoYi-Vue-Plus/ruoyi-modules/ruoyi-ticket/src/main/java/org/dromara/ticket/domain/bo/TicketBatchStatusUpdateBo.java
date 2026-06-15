package org.dromara.ticket.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

@Data
@EqualsAndHashCode(callSuper = true)
public class TicketBatchStatusUpdateBo extends BaseEntity {

    @NotBlank(message = "批次状态不能为空")
    private String batchStatus;

    private String remark;
}
