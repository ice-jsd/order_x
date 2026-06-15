package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.dromara.ticket.domain.TicketLotteryBatchTaskItemSchedule;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@NoArgsConstructor
@AutoMapper(target = TicketLotteryBatchTaskItemSchedule.class, reverseConvertGenerate = false)
public class TicketLotteryBatchTaskItemScheduleBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long scheduleId;
    private Long batchItemId;
    private String sessionId;
    private String sessionLabel;
    private Date scheduledTime;
    private Integer accountCount;
    private String scheduleStatus;
    private String resultMessage;
}
