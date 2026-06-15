package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketLotteryEventParseRecord;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketLotteryEventParseRecord.class)
public class TicketLotteryEventParseRecordVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long recordId;
    private Long platformId;
    private String eventUrl;
    private String eventTitle;
    private String entryStartTime;
    private String entryEndTime;
    private String sessionsJson;
    private String rawSummary;
    private Date createTime;
    private Date updateTime;
}
