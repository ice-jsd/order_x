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
@TableName("ticket_login_batch")
public class TicketLoginBatch extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "batch_id")
    private Long batchId;

    private Long platformId;
    private String batchNo;
    private String batchStatus;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    private String resultSummary;
    private Date executedAt;

    @TableLogic
    private Long delFlag;
}
