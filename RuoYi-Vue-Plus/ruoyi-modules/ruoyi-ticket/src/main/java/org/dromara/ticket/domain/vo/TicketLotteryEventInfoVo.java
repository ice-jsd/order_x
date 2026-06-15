package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class TicketLotteryEventInfoVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long recordId;
    private Long platformId;
    private String eventUrl;
    private String ticketEntryUrl;
    private String eventTitle;
    private String entryStartTime;
    private String entryEndTime;
    private List<TicketLotteryEventSessionVo> sessions;
    private String rawSummary;
    private Boolean cacheHit;
    private String parsedAt;
    private String parseStatus;
    private String parseRequestId;
    private String parseMessage;
}
