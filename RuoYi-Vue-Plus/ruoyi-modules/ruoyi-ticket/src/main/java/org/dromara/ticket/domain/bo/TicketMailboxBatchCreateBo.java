package org.dromara.ticket.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TicketMailboxBatchCreateBo extends BaseEntity {

    @NotNull(message = "创建数量不能为空")
    @Min(value = 1, message = "创建数量不能小于 1")
    @Max(value = 500, message = "单次最多创建 500 个邮箱")
    private Integer count;
}
