package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class TicketPhoneBulkImportResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private int totalCount;
    private int importedCount;
    private int skippedCount;
    private List<String> skippedNumbers;
}
