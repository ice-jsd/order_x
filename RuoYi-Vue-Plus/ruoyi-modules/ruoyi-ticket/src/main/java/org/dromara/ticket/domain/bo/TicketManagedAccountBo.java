package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketManagedAccount;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketManagedAccount.class, reverseConvertGenerate = false)
public class TicketManagedAccountBo extends BaseEntity {

    private Long accountId;
    private Long platformId;
    private Long phoneId;
    private String accountNo;
    private String displayName;
    private String accountStatus;
    private String loginStatus;
}
