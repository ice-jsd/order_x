package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.dromara.ticket.domain.TicketSaleTaskSchedule;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@NoArgsConstructor
@AutoMapper(target = TicketSaleTaskSchedule.class, reverseConvertGenerate = false)
public class TicketSaleTaskScheduleBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long scheduleId;
    private Long taskId;
    private Date scheduledTime;
    private String sessionId;
    private String sessionLabel;
    private Integer accountCount;
    private String scheduleStatus;
    private String resultMessage;
}
