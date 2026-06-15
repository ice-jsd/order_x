package org.dromara.ticket.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TicketManagedAccountLastNameBo {

    @NotBlank(message = "姓不能为空")
    private String lastName;
}
