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
@TableName("ticket_registration_batch_detail")
public class TicketRegistrationBatchDetail extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "detail_id")
    private Long detailId;

    private Long batchId;
    private Long phoneId;
    private Long platformId;
    private String executeStatus;
    private String resultMessage;
    private Long accountId;
    private String accountNo;
    private Date executedAt;

    @TableLogic
    private Long delFlag;
}
