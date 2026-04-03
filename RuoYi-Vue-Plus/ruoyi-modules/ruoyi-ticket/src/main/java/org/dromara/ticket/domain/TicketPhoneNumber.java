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
@TableName("ticket_phone_number")
public class TicketPhoneNumber extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "phone_id")
    private Long phoneId;

    private String phoneNumber;
    private String countryCode;
    private String supplier;
    private String status;
    private String note;

    @TableLogic
    private Long delFlag;
}
