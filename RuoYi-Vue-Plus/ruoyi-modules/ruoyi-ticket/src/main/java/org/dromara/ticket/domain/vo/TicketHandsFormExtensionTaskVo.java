package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TicketHandsFormExtensionTaskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long executionId;
    private Long taskId;
    private Long scheduleId;
    private Long platformId;
    private Long accountId;
    private String platformCode;
    private String platformName;
    private String taskName;
    private String eventUrl;
    private String sessionId;
    private String sessionLabel;
    private String email;
    private String fullName;
    private String furigana;
    private String accountInfo;
    private String workerId;
}
