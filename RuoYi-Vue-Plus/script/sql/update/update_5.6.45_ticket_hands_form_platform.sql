-- Hands Form 平台配置同步
-- 目的：
-- 1. 统一历史平台编码 handsform -> hands-form
-- 2. 确保线上 ticket_platform_config 存在 hands-form，并同步平台能力字段

UPDATE `ticket_platform_config`
SET `platform_code` = 'hands-form',
    `adapter_type` = 'hands-form',
    `platform_name` = 'Hands Form',
    `update_by` = 1,
    `update_time` = NOW()
WHERE `platform_code` = 'handsform'
  AND `del_flag` = 0
  AND NOT EXISTS (
    SELECT 1
    FROM (
      SELECT `platform_id`
      FROM `ticket_platform_config`
      WHERE `platform_code` = 'hands-form'
        AND `del_flag` = 0
    ) AS `existing_hands_form`
  );

INSERT INTO `ticket_platform_config` (
  `platform_id`, `tenant_id`, `platform_code`, `platform_name`, `adapter_type`, `environment`, `enabled`,
  `supports_batch_register`, `supports_batch_login`, `supports_sms`, `supports_email`, `supports_phone_identity`,
  `callback_url`, `order_submit_url`, `callback_secret_mask`, `registration_template`, `login_strategy`, `remark`,
  `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`
)
SELECT
  COALESCE(MAX(`platform_id`), 9000000) + 1,
  '000000',
  'hands-form',
  'Hands Form',
  'hands-form',
  'prod',
  1,
  0,
  0,
  0,
  1,
  0,
  NULL,
  'https://event.hands.net/',
  NULL,
  '{"channel":"email","identity":"form_profile","locale":"ja-JP"}',
  '{"mode":"chrome-extension","submit":"page-driven","notes":"python-parse-only"}',
  'Hands 公开表单抽选平台，由 Chrome 扩展提交，Python 仅负责活动解析',
  103,
  1,
  NOW(),
  1,
  NOW(),
  0
FROM `ticket_platform_config`
WHERE 1 = 1
  AND NOT EXISTS (
  SELECT 1
  FROM (
    SELECT `platform_id`
    FROM `ticket_platform_config`
    WHERE `platform_code` = 'hands-form'
      AND `del_flag` = 0
  ) AS `existing_hands_form`
);

UPDATE `ticket_platform_config`
SET `platform_name` = 'Hands Form',
    `adapter_type` = 'hands-form',
    `environment` = 'prod',
    `enabled` = 1,
    `supports_batch_register` = 0,
    `supports_batch_login` = 0,
    `supports_sms` = 0,
    `supports_email` = 1,
    `supports_phone_identity` = 0,
    `order_submit_url` = 'https://event.hands.net/',
    `registration_template` = '{"channel":"email","identity":"form_profile","locale":"ja-JP"}',
    `login_strategy` = '{"mode":"chrome-extension","submit":"page-driven","notes":"python-parse-only"}',
    `remark` = 'Hands 公开表单抽选平台，由 Chrome 扩展提交，Python 仅负责活动解析',
    `update_by` = 1,
    `update_time` = NOW()
WHERE `platform_code` = 'hands-form'
  AND `del_flag` = 0;
