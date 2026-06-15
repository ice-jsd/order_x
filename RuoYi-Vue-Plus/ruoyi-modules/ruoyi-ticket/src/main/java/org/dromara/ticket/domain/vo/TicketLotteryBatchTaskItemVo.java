package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketLotteryBatchTaskItem;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
@AutoMapper(target = TicketLotteryBatchTaskItem.class)
public class TicketLotteryBatchTaskItemVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long batchItemId;
    private Long batchTaskId;
    private String eventUrl;
    private String eventTitle;
    private String receptionId;
    private String receptionTitle;
    private String salesType;
    private String selectedSessionsJson;
    private String itemStatus;
    private List<TicketLotteryEventSessionVo> selectedSessions;
    private List<TicketLotteryBatchTaskItemScheduleVo> schedules;
}
