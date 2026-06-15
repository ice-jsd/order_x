package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class TicketSelectableAccountIdsVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<Long> accountIds;
    private long totalCount;
    private long availableCount;
    private long occupiedCount;
}
