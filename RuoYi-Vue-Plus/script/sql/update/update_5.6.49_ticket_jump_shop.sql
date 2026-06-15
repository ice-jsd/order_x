SET NAMES utf8mb4;

-- Jump Shop 平台接入

INSERT INTO `ticket_platform_config` (
  `platform_id`, `tenant_id`, `platform_code`, `platform_name`, `adapter_type`, `environment`, `enabled`,
  `supports_batch_register`, `supports_batch_login`, `supports_sms`, `supports_email`, `supports_phone_identity`,
  `callback_url`, `order_submit_url`, `callback_secret_mask`, `registration_template`, `login_strategy`, `remark`,
  `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`
)
SELECT
  COALESCE(MAX(`platform_id`), 9000000) + 1,
  '000000',
  'jump-shop',
  'Jump Shop',
  'jump-shop-online',
  'prod',
  1,
  1,
  1,
  0,
  1,
  0,
  NULL,
  'https://jumpshop-benelic.com/checkout',
  NULL,
  '{"channel":"email","identity":"random-jp-profile","captcha":["hcaptcha","recaptcha"],"locale":"ja-JP"}',
  '{"mode":"python-browser","submit":"shopify-checkout","payment":"credit_card","3ds":"fail"}',
  'Jump Shop Shopify 抢购平台，由 Python 浏览器执行注册、登录、加购与信用卡结账；命中 3DS 直接失败',
  103,
  1,
  NOW(),
  1,
  NOW(),
  0
FROM `ticket_platform_config`
WHERE NOT EXISTS (
  SELECT 1
  FROM (
    SELECT `platform_id`
    FROM `ticket_platform_config`
    WHERE `platform_code` = 'jump-shop'
      AND `del_flag` = 0
  ) AS `existing_jump_shop`
);

UPDATE `ticket_platform_config`
SET `platform_name` = 'Jump Shop',
    `adapter_type` = 'jump-shop-online',
    `environment` = 'prod',
    `enabled` = 1,
    `supports_batch_register` = 1,
    `supports_batch_login` = 1,
    `supports_sms` = 0,
    `supports_email` = 1,
    `supports_phone_identity` = 0,
    `order_submit_url` = 'https://jumpshop-benelic.com/checkout',
    `registration_template` = '{"channel":"email","identity":"random-jp-profile","captcha":["hcaptcha","recaptcha"],"locale":"ja-JP"}',
    `login_strategy` = '{"mode":"python-browser","submit":"shopify-checkout","payment":"credit_card","3ds":"fail"}',
    `remark` = 'Jump Shop Shopify 抢购平台，由 Python 浏览器执行注册、登录、加购与信用卡结账；命中 3DS 直接失败',
    `update_by` = 1,
    `update_time` = NOW()
WHERE `platform_code` = 'jump-shop'
  AND `del_flag` = 0;

CREATE TABLE IF NOT EXISTS `ticket_jump_shop_profile` (
  `profile_id` bigint(20) NOT NULL COMMENT '资料主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `profile_name` varchar(120) NOT NULL COMMENT '资料名称',
  `enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用',
  `last_name` varchar(120) DEFAULT NULL COMMENT '收件姓',
  `first_name` varchar(120) DEFAULT NULL COMMENT '收件名',
  `phone` varchar(64) DEFAULT NULL COMMENT '联系电话',
  `postal_code` varchar(32) DEFAULT NULL COMMENT '邮编',
  `province` varchar(120) DEFAULT NULL COMMENT '都道府县',
  `city` varchar(120) DEFAULT NULL COMMENT '城市',
  `address1` varchar(255) DEFAULT NULL COMMENT '地址1',
  `address2` varchar(255) DEFAULT NULL COMMENT '地址2',
  `country_code` varchar(32) DEFAULT 'JP' COMMENT '国家代码',
  `billing_same_as_shipping` tinyint(1) DEFAULT 1 COMMENT '账单地址与收件地址一致',
  `billing_last_name` varchar(120) DEFAULT NULL COMMENT '账单姓',
  `billing_first_name` varchar(120) DEFAULT NULL COMMENT '账单名',
  `billing_phone` varchar(64) DEFAULT NULL COMMENT '账单电话',
  `billing_postal_code` varchar(32) DEFAULT NULL COMMENT '账单邮编',
  `billing_province` varchar(120) DEFAULT NULL COMMENT '账单都道府县',
  `billing_city` varchar(120) DEFAULT NULL COMMENT '账单城市',
  `billing_address1` varchar(255) DEFAULT NULL COMMENT '账单地址1',
  `billing_address2` varchar(255) DEFAULT NULL COMMENT '账单地址2',
  `billing_country_code` varchar(32) DEFAULT 'JP' COMMENT '账单国家代码',
  `card_holder_name` varchar(160) DEFAULT NULL COMMENT '持卡人',
  `card_number` varchar(512) DEFAULT NULL COMMENT '卡号(加密)',
  `exp_month` varchar(8) DEFAULT NULL COMMENT '有效月',
  `exp_year` varchar(8) DEFAULT NULL COMMENT '有效年',
  `cvv` varchar(256) DEFAULT NULL COMMENT 'CVV(加密)',
  `issue_month` varchar(8) DEFAULT NULL COMMENT 'Issue Month',
  `issue_year` varchar(8) DEFAULT NULL COMMENT 'Issue Year',
  `issue_number` varchar(256) DEFAULT NULL COMMENT 'Issue Number(加密)',
  `remark` varchar(1000) DEFAULT NULL COMMENT '备注',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`profile_id`),
  KEY `idx_ticket_jump_shop_profile_enabled` (`enabled`),
  KEY `idx_ticket_jump_shop_profile_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Jump Shop 结算资料';

INSERT INTO `sys_menu`
(
  `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`,
  `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`,
  `create_time`, `update_by`, `update_time`, `remark`
)
SELECT 20016, 'Jump Shop 资料', 20000, 11, 'jump-shop-profile', 'ticket/jump-shop-profile/index', '', 1, 0, 'C', '0', '0',
       'ticket:jumpShopProfile:list', 'credit-card', 103, 1, NOW(), NULL, NULL, 'Jump Shop 结算资料页'
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20016);

UPDATE `sys_menu`
SET `menu_name` = 'Jump Shop 资料',
    `parent_id` = 20000,
    `order_num` = 11,
    `path` = 'jump-shop-profile',
    `component` = 'ticket/jump-shop-profile/index',
    `menu_type` = 'C',
    `visible` = '0',
    `status` = '0',
    `perms` = 'ticket:jumpShopProfile:list',
    `icon` = 'credit-card',
    `remark` = 'Jump Shop 结算资料页'
WHERE `menu_id` = 20016;

INSERT INTO `sys_menu`
(
  `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`,
  `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`,
  `create_time`, `update_by`, `update_time`, `remark`
)
SELECT 20910, 'Jump Shop 资料查询', 20016, 1, '', '', '', 1, 0, 'F', '0', '0',
       'ticket:jumpShopProfile:query', '#', 103, 1, NOW(), NULL, NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20910);

INSERT INTO `sys_menu`
(
  `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`,
  `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`,
  `create_time`, `update_by`, `update_time`, `remark`
)
SELECT 20911, 'Jump Shop 资料新增', 20016, 2, '', '', '', 1, 0, 'F', '0', '0',
       'ticket:jumpShopProfile:add', '#', 103, 1, NOW(), NULL, NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20911);

INSERT INTO `sys_menu`
(
  `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`,
  `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`,
  `create_time`, `update_by`, `update_time`, `remark`
)
SELECT 20912, 'Jump Shop 资料编辑', 20016, 3, '', '', '', 1, 0, 'F', '0', '0',
       'ticket:jumpShopProfile:edit', '#', 103, 1, NOW(), NULL, NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20912);

INSERT INTO `sys_menu`
(
  `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`,
  `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`,
  `create_time`, `update_by`, `update_time`, `remark`
)
SELECT 20913, 'Jump Shop 资料删除', 20016, 4, '', '', '', 1, 0, 'F', '0', '0',
       'ticket:jumpShopProfile:remove', '#', 103, 1, NOW(), NULL, NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20913);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20016
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20016)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20016);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20910
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20910)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20910);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20911
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20911)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20911);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20912
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20912)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20912);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20913
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20913)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20913);
