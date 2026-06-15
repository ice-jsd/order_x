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
@TableName("ticket_mail_record")
public class TicketMailRecord extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "record_id")
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

    @TableLogic
    private Long delFlag;
}
