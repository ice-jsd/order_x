package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class TicketLotteryBatchTaskProcessVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private TicketLotteryBatchTaskVo task;
    private List<TicketSaleTaskProcessStepVo> createSteps;
    private Map<String, Integer> executionSummary;
    private List<TicketLotteryBatchTaskItemVo> items;
}
