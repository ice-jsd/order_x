package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketLotteryBatchTask;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@AutoMapper(target = TicketLotteryBatchTask.class)
public class TicketLotteryBatchTaskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long batchTaskId;
    private String tenantId;
    private Long platformId;
    private Date createTime;
    private Date updateTime;
    private String taskName;
    private String taskStatus;
    private String sourceUrl;
    private Long scheduleVersion;
    private String taskOptions;
    private String remark;
    private String platformName;
    private List<Long> accountIds;
    private Integer boundAccountCount;
    private List<TicketLotteryBatchTaskItemVo> items;
    private Map<String, Integer> executionSummary;
}
