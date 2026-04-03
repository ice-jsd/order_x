package org.dromara.ticket.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TicketPhoneBulkImportBo extends BaseEntity {

    private String supplier;
    private String countryCode;
    private String status;
    private String note;
    private String numbers;
}
