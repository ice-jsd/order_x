package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketLotteryBatchTask;

import java.util.List;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketLotteryBatchTask.class, reverseConvertGenerate = false)
public class TicketLotteryBatchTaskBo extends BaseEntity {

    private Long batchTaskId;
    private Long platformId;
    private String taskName;
    private String taskStatus;
    private String sourceUrl;
    private String taskOptions;
    private String remark;
    private List<Long> accountIds;
    private List<TicketLotteryBatchTaskItemBo> items;
}
