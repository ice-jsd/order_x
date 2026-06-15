package org.dromara.ticket.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_mail_forward_record")
public class TicketMailForwardRecord extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "forward_id")
    private Long forwardId;

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
    private Date sentAt;

    @TableLogic
    private Long delFlag;
}
