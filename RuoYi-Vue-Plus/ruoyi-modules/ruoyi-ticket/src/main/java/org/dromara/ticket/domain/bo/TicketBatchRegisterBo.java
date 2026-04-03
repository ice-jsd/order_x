package org.dromara.ticket.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.util.List;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TicketBatchRegisterBo extends BaseEntity {

    private List<Long> phoneIds;
    private String supplier;
    private String countryCode;
    private String status;
}
