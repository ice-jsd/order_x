-- 基础演示数据
-- 仅覆盖：平台管理 / 号码池 / 账号池

DELETE FROM `ticket_phone_platform_relation`
WHERE `relation_id` IN (9400001, 9400002, 9400003, 9400004, 9400005, 9400006);

DELETE FROM `ticket_managed_account`
WHERE `account_id` IN (9300001, 9300002, 9300003, 9300004, 9300005)
   OR `account_no` IN ('ACC-DEMO-001', 'ACC-DEMO-002', 'ACC-DEMO-003', 'ACC-DEMO-004', 'ACC-DEMO-005');

DELETE FROM `ticket_phone_number`
WHERE `phone_id` IN (9200001, 9200002, 9200003, 9200004, 9200005, 9200006, 9200007, 9200008)
   OR `phone_number` IN ('09012340001', '09012340002', '09012340003', '09012340004', '09012340005', '09012340006', '09012340007', '09012340008');

DELETE FROM `ticket_platform_config`
WHERE `platform_id` IN (9100001, 9100002, 9100003)
   OR `platform_code` IN ('jp-ticket-mesh', 'live-pass-jp', 'theater-gate');

INSERT INTO `ticket_platform_config` (
  `platform_id`, `tenant_id`, `platform_code`, `platform_name`, `adapter_type`, `environment`, `enabled`,
  `supports_batch_register`, `supports_batch_login`, `supports_sms`, `supports_email`, `supports_phone_identity`,
  `callback_url`, `callback_secret_mask`, `registration_template`, `login_strategy`, `remark`,
  `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`
) VALUES
  (9100001, '000000', 'jp-ticket-mesh', 'JP Ticket Mesh', 'mock', 'sandbox', 1, 1, 1, 1, 1, 1,
   'https://callback.demo.local/ticket/mesh', 'mesh-****-sig', '{"channel":"sms","locale":"ja-JP"}', '{"warmupMinutes":30,"retry":2}', '日本主力测试平台', 103, 1, NOW(), 1, NOW(), 0),
  (9100002, '000000', 'live-pass-jp', 'Live Pass Japan', 'mock', 'prod', 1, 1, 1, 0, 1, 1,
   'https://callback.demo.local/ticket/live-pass', 'live-****-sig', '{"channel":"email","locale":"ja-JP"}', '{"warmupMinutes":20,"retry":1}', '演出票务正式环境模拟平台', 103, 1, NOW(), 1, NOW(), 0),
  (9100003, '000000', 'theater-gate', 'Theater Gate', 'mock', 'sandbox', 0, 1, 1, 1, 1, 1,
   'https://callback.demo.local/ticket/theater', 'gate-****-sig', '{"channel":"mixed","locale":"ja-JP"}', '{"warmupMinutes":15,"retry":3}', '停用中的剧场平台示例', 103, 1, NOW(), 1, NOW(), 0);

INSERT INTO `ticket_phone_number` (
  `phone_id`, `tenant_id`, `phone_number`, `country_code`, `supplier`, `status`, `note`,
  `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`
) VALUES
  (9200001, '000000', '09012340001', '+81', 'vendor-a', 'available', '主力号码，已登录 JP Ticket Mesh', 103, 1, NOW(), 1, NOW(), 0),
  (9200002, '000000', '09012340002', '+81', 'vendor-a', 'available', '已注册未登录 Live Pass', 103, 1, NOW(), 1, NOW(), 0),
  (9200003, '000000', '09012340003', '+81', 'vendor-b', 'available', '登录失败示例', 103, 1, NOW(), 1, NOW(), 0),
  (9200004, '000000', '09012340004', '+81', 'vendor-b', 'available', '尚未注册，可继续用于其他平台', 103, 1, NOW(), 1, NOW(), 0),
  (9200005, '000000', '09012340005', '+81', 'vendor-c', 'available', '注册失败示例', 103, 1, NOW(), 1, NOW(), 0),
  (9200006, '000000', '09012340006', '+81', 'vendor-c', 'disabled', '号码停用，但历史账号仍可见', 103, 1, NOW(), 1, NOW(), 0),
  (9200007, '000000', '09012340007', '+81', 'vendor-a', 'frozen', '冻结号码样例', 103, 1, NOW(), 1, NOW(), 0),
  (9200008, '000000', '09012340008', '+81', 'vendor-d', 'available', '跨平台复用号码', 103, 1, NOW(), 1, NOW(), 0);

INSERT INTO `ticket_managed_account` (
  `account_id`, `tenant_id`, `platform_id`, `phone_id`, `account_no`, `display_name`, `account_status`, `login_status`,
  `session_token`, `session_expire_time`, `last_login_time`, `last_error`,
  `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`
) VALUES
  (9300001, '000000', 9100001, 9200001, 'ACC-DEMO-001', 'mesh-main-001', 'registered', 'logged_in',
   'sess-demo-001', DATE_ADD(NOW(), INTERVAL 12 HOUR), DATE_SUB(NOW(), INTERVAL 15 MINUTE), NULL, 103, 1, NOW(), 1, NOW(), 0),
  (9300002, '000000', 9100002, 9200002, 'ACC-DEMO-002', 'live-ready-002', 'registered', 'offline',
   NULL, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NULL, 103, 1, NOW(), 1, NOW(), 0),
  (9300003, '000000', 9100001, 9200003, 'ACC-DEMO-003', 'mesh-risk-003', 'registered', 'login_failed',
   NULL, NULL, DATE_SUB(NOW(), INTERVAL 3 HOUR), '平台返回风控校验失败', 103, 1, NOW(), 1, NOW(), 0),
  (9300004, '000000', 9100003, 9200006, 'ACC-DEMO-004', 'gate-disabled-004', 'registered', 'offline',
   NULL, NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NULL, 103, 1, NOW(), 1, NOW(), 0),
  (9300005, '000000', 9100002, 9200008, 'ACC-DEMO-005', 'live-shared-005', 'registered', 'logged_in',
   'sess-demo-005', DATE_ADD(NOW(), INTERVAL 8 HOUR), DATE_SUB(NOW(), INTERVAL 5 MINUTE), NULL, 103, 1, NOW(), 1, NOW(), 0);

INSERT INTO `ticket_phone_platform_relation` (
  `relation_id`, `tenant_id`, `phone_id`, `platform_id`, `account_id`, `status`, `last_error`, `last_operate_time`,
  `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`
) VALUES
  (9400001, '000000', 9200001, 9100001, 9300001, 'logged_in', NULL, DATE_SUB(NOW(), INTERVAL 15 MINUTE), 103, 1, NOW(), 1, NOW(), 0),
  (9400002, '000000', 9200002, 9100002, 9300002, 'registered', NULL, DATE_SUB(NOW(), INTERVAL 1 HOUR), 103, 1, NOW(), 1, NOW(), 0),
  (9400003, '000000', 9200003, 9100001, 9300003, 'login_failed', '平台返回风控校验失败', DATE_SUB(NOW(), INTERVAL 3 HOUR), 103, 1, NOW(), 1, NOW(), 0),
  (9400004, '000000', 9200005, 9100002, NULL, 'register_failed', '邮箱验证超时', DATE_SUB(NOW(), INTERVAL 6 HOUR), 103, 1, NOW(), 1, NOW(), 0),
  (9400005, '000000', 9200006, 9100003, 9300004, 'registered', NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), 103, 1, NOW(), 1, NOW(), 0),
  (9400006, '000000', 9200008, 9100002, 9300005, 'logged_in', NULL, DATE_SUB(NOW(), INTERVAL 5 MINUTE), 103, 1, NOW(), 1, NOW(), 0);
