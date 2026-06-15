package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketMailForwardRecord;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketMailForwardRecord.class)
public class TicketMailForwardRecordVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long forwardId;
    private String tenantId;
    private Long recordId;
    private Long mailboxId;
    private String sourceEmail;
    private String sourceMessageId;
    private String mailSubject;
    private String targetEmail;
    private String senderFrom;
    private String forwardMessageId;
    private String sendStatus;
    private String errorMessage;
    private String forwardSubject;
    private String forwardContent;
    private String operatorName;
    private Long createBy;
    private Date createTime;
    private Date sentAt;
}
