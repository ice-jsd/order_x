package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketMailRecord;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketMailRecord.class)
public class TicketMailRecordVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long recordId;
    private Long mailboxId;
    private Long accountId;
    private String email;
    private String username;
    private String folderName;
    private String messageId;
    private String subject;
    private String fromAddress;
    private Date receivedAt;
    private String bodyExcerpt;
    private String bodyContent;
    private String parseType;
    private String verifyCode;
    private String activationUrl;
    private String lotteryApplicationNo;
    private String lotteryResultStatus;
    private Boolean parsed;
    private String readSource;
    private Date syncTime;
}
