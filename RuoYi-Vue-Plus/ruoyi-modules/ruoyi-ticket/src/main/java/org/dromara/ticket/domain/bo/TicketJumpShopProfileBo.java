package org.dromara.ticket.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.ticket.domain.TicketJumpShopProfile;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TicketJumpShopProfile.class, reverseConvertGenerate = false)
public class TicketJumpShopProfileBo extends BaseEntity {

    private Long profileId;

    @NotBlank(message = "资料名称不能为空")
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
    private String cardNumber;
    private String expMonth;
    private String expYear;
    private String cvv;
    private String issueMonth;
    private String issueYear;
    private String issueNumber;
    private String remark;
}
