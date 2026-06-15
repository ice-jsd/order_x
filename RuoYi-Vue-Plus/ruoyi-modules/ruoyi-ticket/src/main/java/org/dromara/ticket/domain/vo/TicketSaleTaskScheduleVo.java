package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketSaleTaskSchedule;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketSaleTaskSchedule.class)
public class TicketSaleTaskScheduleVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long scheduleId;
    private Long taskId;
    private Date scheduledTime;
    private String sessionId;
    private String sessionLabel;
    private Integer accountCount;
    private String scheduleStatus;
    private Date dispatchedTime;
    private Date finishedTime;
    private String resultMessage;
}
