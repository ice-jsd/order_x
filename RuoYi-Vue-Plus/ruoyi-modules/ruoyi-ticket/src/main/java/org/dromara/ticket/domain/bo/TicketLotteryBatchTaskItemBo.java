package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.dromara.ticket.domain.TicketLotteryBatchTaskItem;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AutoMapper(target = TicketLotteryBatchTaskItem.class, reverseConvertGenerate = false)
public class TicketLotteryBatchTaskItemBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long batchItemId;
    private Long batchTaskId;
    private String eventUrl;
    private String eventTitle;
    private String receptionId;
    private String receptionTitle;
    private String salesType;
    private String itemStatus;
    private List<TicketLotteryEventSessionBo> selectedSessions;
    private List<TicketLotteryBatchTaskItemScheduleBo> schedules;
}
