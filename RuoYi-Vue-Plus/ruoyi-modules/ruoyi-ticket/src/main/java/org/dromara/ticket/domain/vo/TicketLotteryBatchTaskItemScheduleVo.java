package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketLotteryBatchTaskItemSchedule;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketLotteryBatchTaskItemSchedule.class)
public class TicketLotteryBatchTaskItemScheduleVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long scheduleId;
    private Long batchItemId;
    private String sessionId;
    private String sessionLabel;
    private Date scheduledTime;
    private Integer accountCount;
    private String scheduleStatus;
    private Date dispatchedTime;
    private Date finishedTime;
    private String resultMessage;
}
