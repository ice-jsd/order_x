package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.util.Map;

@Data
public class TicketLivePocketQuestionnairePreviewVo {

    private Map<String, Object> questionnaireConfig;
    private String confirmUrl;
    private String requestUrl;
    private String resultUrl;
    private String eventId;
    private String reserveId;
    private String sessionId;
    private String sessionLabel;
    private String authSource;
    private String loginReqData;
    private Integer questionCount;
    private String rawResult;
}
