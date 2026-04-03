package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketPhoneNumber;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketPhoneNumber.class, reverseConvertGenerate = false)
public class TicketPhoneNumberBo extends BaseEntity {

    private Long phoneId;
    private String phoneNumber;
    private String countryCode;
    private String supplier;
    private String status;
    private String note;
    private Long platformId;
    private String relationStatus;
}
