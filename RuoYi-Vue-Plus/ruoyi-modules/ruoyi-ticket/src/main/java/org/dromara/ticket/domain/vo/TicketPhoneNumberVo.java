package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketPhoneNumber;

import java.io.Serial;
import java.io.Serializable;

@Data
@AutoMapper(target = TicketPhoneNumber.class)
public class TicketPhoneNumberVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long phoneId;
    private String tenantId;
    private String phoneNumber;
    private String countryCode;
    private String supplier;
    private String status;
    private String note;
    private String createTime;
    private Integer registeredPlatformCount;
    private Integer loggedInPlatformCount;
}
