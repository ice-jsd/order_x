package org.dromara.ticket.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TicketManagedAccountCreateBo extends BaseEntity {

    private Long platformId;
    private Long phoneId;
    private String email;
    private String accountInfo;
    private String reqData;
}
