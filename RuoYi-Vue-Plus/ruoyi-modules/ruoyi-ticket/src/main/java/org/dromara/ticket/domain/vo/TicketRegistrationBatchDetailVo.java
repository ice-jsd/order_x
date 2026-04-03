package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketRegistrationBatchDetail;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketRegistrationBatchDetail.class)
public class TicketRegistrationBatchDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long detailId;
    private String tenantId;
    private Long batchId;
    private Long phoneId;
    private Long platformId;
    private String executeStatus;
    private String resultMessage;
    private Long accountId;
    private String accountNo;
    private Date executedAt;
    private String phoneNumber;
    private String platformName;
}
