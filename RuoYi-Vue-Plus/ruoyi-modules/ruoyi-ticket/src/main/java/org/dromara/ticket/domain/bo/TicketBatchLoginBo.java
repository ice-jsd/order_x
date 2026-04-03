package org.dromara.ticket.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.util.List;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TicketBatchLoginBo extends BaseEntity {

    private List<Long> accountIds;
    private String loginStatus;
}
