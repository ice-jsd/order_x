package org.dromara.ticket.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ticket.domain.TicketSaleTask;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@AutoMapper(target = TicketSaleTask.class)
public class TicketSaleTaskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long taskId;
    private String tenantId;
    private Long platformId;
    private Date createTime;
    private Date updateTime;
    private String taskName;
    private String taskStatus;
    private String purchaseType;
    private String configSchemaKey;
    private Long scheduleVersion;
    private Date warmupTime;
    private Date scheduledTime;
    private Date lastExecutedTime;
    private Integer purchaseQuantity;
    private String eventTitle;
    private String taskOptions;
    private String lotteryEventUrl;
    private List<TicketLotteryEventSessionVo> selectedSessions;
    private String remark;
    private String platformName;
    private List<Long> accountIds;
    private Integer boundAccountCount;
    private String accountEmails;
    private Integer lotteryScheduleCount;
    private List<TicketSaleTaskScheduleVo> lotterySchedules;
    private Map<String, Integer> executionSummary;
}
