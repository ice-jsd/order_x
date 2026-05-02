package org.dromara.ticket.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
public class TicketExternalRegisterAccountVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long accountId;
    private String email;
    private String password;
    private String confirmPassword;
    private String familyName;
    private String givenName;
    private String gender;
    private Integer birthYear;
    private Integer birthMonth;
    private Integer birthDay;
    private String countryRegion;
    private String phoneNumber;
    private String residence;
    private String language;
    private Date phoneLeaseExpireTime;
    private Integer phoneCountdownSeconds;
}
