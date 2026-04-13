package org.dromara.ticket.adapter;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class TicketOrderFlowStep implements Serializable {

    private String stepType;
    private String stepCode;
    private String currentStep;
    private String label;
    private Map<String, Object> options = new LinkedHashMap<>();
}
