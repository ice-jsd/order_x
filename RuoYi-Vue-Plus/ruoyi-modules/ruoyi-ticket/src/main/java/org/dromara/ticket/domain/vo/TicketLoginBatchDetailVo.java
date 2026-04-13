package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketLoginBatchDetail;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@AutoMapper(target = TicketLoginBatchDetail.class)
public class TicketLoginBatchDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long detailId;
    private String tenantId;
    private Long batchId;
    private Long accountId;
    private Long platformId;
    private String executeStatus;
    private String resultMessage;
    private String reqData;
    private Date executedAt;
    private String email;
    private String accountInfo;
    private String phoneNumber;
    private String platformName;
}
