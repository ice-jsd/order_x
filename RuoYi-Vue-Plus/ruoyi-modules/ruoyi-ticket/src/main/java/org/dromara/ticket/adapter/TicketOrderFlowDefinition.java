package org.dromara.ticket.adapter;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class TicketOrderFlowDefinition implements Serializable {

    private String purchaseType;
    private String configSchemaKey;
    private List<TicketOrderFlowStep> steps = new ArrayList<>();
}
