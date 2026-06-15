package org.dromara.ticket.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.encrypt.annotation.EncryptField;
import org.dromara.common.encrypt.enumd.AlgorithmType;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_jump_shop_profile")
public class TicketJumpShopProfile extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "profile_id")
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
    @EncryptField(algorithm = AlgorithmType.AES, password = "10rfylhtccpuyke5")
    private String cardNumber;
    private String expMonth;
    private String expYear;
    @EncryptField(algorithm = AlgorithmType.AES, password = "10rfylhtccpuyke5")
    private String cvv;
    private String issueMonth;
    private String issueYear;
    @EncryptField(algorithm = AlgorithmType.AES, password = "10rfylhtccpuyke5")
    private String issueNumber;
    private String remark;

    @TableLogic
    private Long delFlag;
}
