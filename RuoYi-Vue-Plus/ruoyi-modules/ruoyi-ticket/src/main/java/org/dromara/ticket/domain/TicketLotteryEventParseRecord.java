package org.dromara.ticket.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_lottery_event_parse_record")
public class TicketLotteryEventParseRecord extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "record_id")
    private Long recordId;

    private Long platformId;
    private String eventUrl;
    private String ticketEntryUrl;
    private String eventTitle;
    private String entryStartTime;
    private String entryEndTime;
    private String sessionsJson;
    private String rawSummary;
    private String parseStatus;
    private String parseRequestId;
    private String parseMessage;

    @TableLogic
    private Long delFlag;
}
