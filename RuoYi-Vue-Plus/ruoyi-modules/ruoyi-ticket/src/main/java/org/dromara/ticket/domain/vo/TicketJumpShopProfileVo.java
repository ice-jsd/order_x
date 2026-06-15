package org.dromara.ticket.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
public class TicketJumpShopProfileVo extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long profileId;
    private String profileName;
    private Boolean enabled;
    private String lastName;
    private String firstName;
    private String phone;
    private String postalCode;
    private String province;
    private String city;
    private String address1;
    private String address2;
    private String countryCode;
    private Boolean billingSameAsShipping;
    private String billingLastName;
    private String billingFirstName;
    private String billingPhone;
    private String billingPostalCode;
    private String billingProvince;
    private String billingCity;
    private String billingAddress1;
    private String billingAddress2;
    private String billingCountryCode;
    private String cardHolderName;
    private String cardNumberMasked;
    private Boolean cardNumberConfigured;
    private String expMonth;
    private String expYear;
    private Boolean cvvConfigured;
    private String issueMonth;
    private String issueYear;
    private String issueNumberMasked;
    private String remark;
}
