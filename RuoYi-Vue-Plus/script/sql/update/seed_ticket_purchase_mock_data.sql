-- 商品抢购 Mock 联调数据

DELETE FROM `ticket_mock_platform_order`
WHERE `task_id` IN (9500011, 9500012, 9500013, 9500014);

DELETE FROM `ticket_order_execution`
WHERE `task_id` IN (9500011, 9500012, 9500013, 9500014);

DELETE FROM `ticket_sale_task_account`
WHERE `binding_id` IN (9600011, 9600012, 9600013, 9600014)
   OR `task_id` IN (9500011, 9500012, 9500013, 9500014);

DELETE FROM `ticket_sale_task`
WHERE `task_id` IN (9500011, 9500012, 9500013, 9500014)
   OR `task_name` IN ('Mock 直下配送线上支付', 'Mock 直下门店付款', 'Mock 加购配送线上支付', 'Mock 加购门店付款');

DELETE FROM `ticket_phone_platform_relation`
WHERE `relation_id` IN (9400011, 9400012, 9400013, 9400014);

DELETE FROM `ticket_managed_account`
WHERE `account_id` IN (9300011, 9300012, 9300013, 9300014)
   OR `email` IN (
      'mock-direct-online-001@test.local',
      'mock-direct-pickup-cod-002@test.local',
      'mock-cart-online-003@test.local',
      'mock-cart-pickup-cod-004@test.local'
   );

DELETE FROM `ticket_phone_number`
WHERE `phone_id` IN (9200011, 9200012, 9200013, 9200014)
   OR `phone_number` IN ('09022340011', '09022340012', '09022340013', '09022340014');

DELETE FROM `ticket_platform_config`
WHERE `platform_id` IN (9100011, 9100012, 9100013, 9100014)
   OR `platform_code` IN (
      'mock-direct-online',
      'mock-direct-pickup-cod',
      'mock-cart-online',
      'mock-cart-pickup-cod'
   );

INSERT INTO `ticket_platform_config` (
  `platform_id`, `tenant_id`, `platform_code`, `platform_name`, `adapter_type`, `environment`, `enabled`,
  `supports_batch_register`, `supports_batch_login`, `supports_sms`, `supports_email`, `supports_phone_identity`,
  `callback_url`, `order_submit_url`, `callback_secret_mask`, `registration_template`, `login_strategy`, `remark`,
  `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`
) VALUES
  (9100011, '000000', 'mock-direct-online', 'Mock 直下配送线上支付', 'mock', 'sandbox', 1, 0, 0, 0, 1, 1,
   NULL, 'http://127.0.0.1:8080/ticket/mock-platform/order/mock-direct-online', 'mock-direct-online',
   '{"channel":"email"}', '{"flow":"direct_order"}', '直下单 + 配送 + 线上支付', 103, 1, NOW(), 1, NOW(), 0),
  (9100012, '000000', 'mock-direct-pickup-cod', 'Mock 直下门店付款', 'mock', 'sandbox', 1, 0, 0, 0, 1, 1,
   NULL, 'http://127.0.0.1:8080/ticket/mock-platform/order/mock-direct-pickup-cod', 'mock-direct-pickup-cod',
   '{"channel":"email"}', '{"flow":"direct_order"}', '直下单 + 门店自提 + 门店付款', 103, 1, NOW(), 1, NOW(), 0),
  (9100013, '000000', 'mock-cart-online', 'Mock 加购配送线上支付', 'mock', 'sandbox', 1, 0, 0, 0, 1, 1,
   NULL, 'http://127.0.0.1:8080/ticket/mock-platform/order/mock-cart-online', 'mock-cart-online',
   '{"channel":"email"}', '{"flow":"cart_checkout"}', '加购结算 + 配送 + 线上支付', 103, 1, NOW(), 1, NOW(), 0),
  (9100014, '000000', 'mock-cart-pickup-cod', 'Mock 加购门店付款', 'mock', 'sandbox', 1, 0, 0, 0, 1, 1,
   NULL, 'http://127.0.0.1:8080/ticket/mock-platform/order/mock-cart-pickup-cod', 'mock-cart-pickup-cod',
   '{"channel":"email"}', '{"flow":"cart_checkout"}', '加购结算 + 门店自提 + 门店付款', 103, 1, NOW(), 1, NOW(), 0);

INSERT INTO `ticket_phone_number` (
  `phone_id`, `tenant_id`, `phone_number`, `country_code`, `supplier`, `status`, `note`,
  `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`
) VALUES
  (9200011, '000000', '09022340011', '+81', 'mock-supplier-a', 'available', 'Mock 直下配送线上支付号码', 103, 1, NOW(), 1, NOW(), 0),
  (9200012, '000000', '09022340012', '+81', 'mock-supplier-b', 'available', 'Mock 直下门店付款号码', 103, 1, NOW(), 1, NOW(), 0),
  (9200013, '000000', '09022340013', '+81', 'mock-supplier-c', 'available', 'Mock 加购配送线上支付号码', 103, 1, NOW(), 1, NOW(), 0),
  (9200014, '000000', '09022340014', '+81', 'mock-supplier-d', 'available', 'Mock 加购门店付款号码', 103, 1, NOW(), 1, NOW(), 0);

INSERT INTO `ticket_managed_account` (
  `account_id`, `tenant_id`, `platform_id`, `phone_id`, `email`, `account_info`, `req_data`, `login_req_data`, `account_status`, `login_status`,
  `last_login_time`, `last_error`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`
) VALUES
  (9300011, '000000', 9100011, 9200011, 'mock-direct-online-001@test.local',
   '{"nickname":"mock-direct-online","region":"JP","channel":"email"}',
   '{"sessionToken":"mock-session-001","channel":"email","browser":"mock"}',
   '{"sessionToken":"mock-session-001","channel":"email","browser":"mock"}',
   'registered', 'logged_in', DATE_SUB(NOW(), INTERVAL 2 MINUTE), NULL, 103, 1, NOW(), 1, NOW(), 0),
  (9300012, '000000', 9100012, 9200012, 'mock-direct-pickup-cod-002@test.local',
   '{"nickname":"mock-direct-pickup-cod","region":"JP","channel":"email"}',
   '{"sessionToken":"mock-session-002","channel":"email","browser":"mock"}',
   '{"sessionToken":"mock-session-002","channel":"email","browser":"mock"}',
   'registered', 'logged_in', DATE_SUB(NOW(), INTERVAL 3 MINUTE), NULL, 103, 1, NOW(), 1, NOW(), 0),
  (9300013, '000000', 9100013, 9200013, 'mock-cart-online-003@test.local',
   '{"nickname":"mock-cart-online","region":"JP","channel":"email"}',
   '{"sessionToken":"mock-session-003","channel":"email","browser":"mock"}',
   '{"sessionToken":"mock-session-003","channel":"email","browser":"mock"}',
   'registered', 'logged_in', DATE_SUB(NOW(), INTERVAL 4 MINUTE), NULL, 103, 1, NOW(), 1, NOW(), 0),
  (9300014, '000000', 9100014, 9200014, 'mock-cart-pickup-cod-004@test.local',
   '{"nickname":"mock-cart-pickup-cod","region":"JP","channel":"email"}',
   '{"sessionToken":"mock-session-004","channel":"email","browser":"mock"}',
   '{"sessionToken":"mock-session-004","channel":"email","browser":"mock"}',
   'registered', 'logged_in', DATE_SUB(NOW(), INTERVAL 5 MINUTE), NULL, 103, 1, NOW(), 1, NOW(), 0);

INSERT INTO `ticket_phone_platform_relation` (
  `relation_id`, `tenant_id`, `phone_id`, `platform_id`, `account_id`, `status`, `last_error`, `last_operate_time`,
  `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`
) VALUES
  (9400011, '000000', 9200011, 9100011, 9300011, 'logged_in', NULL, DATE_SUB(NOW(), INTERVAL 2 MINUTE), 103, 1, NOW(), 1, NOW(), 0),
  (9400012, '000000', 9200012, 9100012, 9300012, 'logged_in', NULL, DATE_SUB(NOW(), INTERVAL 3 MINUTE), 103, 1, NOW(), 1, NOW(), 0),
  (9400013, '000000', 9200013, 9100013, 9300013, 'logged_in', NULL, DATE_SUB(NOW(), INTERVAL 4 MINUTE), 103, 1, NOW(), 1, NOW(), 0),
  (9400014, '000000', 9200014, 9100014, 9300014, 'logged_in', NULL, DATE_SUB(NOW(), INTERVAL 5 MINUTE), 103, 1, NOW(), 1, NOW(), 0);

INSERT INTO `ticket_sale_task` (
  `task_id`, `tenant_id`, `platform_id`, `product_id`, `task_name`, `task_status`, `order_flow_type`, `fulfillment_type`, `payment_mode`,
  `warmup_time`, `scheduled_time`, `purchase_quantity`, `task_options`, `last_executed_time`, `remark`,
  `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`
) VALUES
  (9500011, '000000', 9100011, 'mock-sku-direct-online-001', 'Mock 直下配送线上支付', 'draft', 'direct_order', 'shipping', 'online',
   NULL, NULL, 2, '{"deliveryOption":"express","mockBehavior":""}', NULL, '测试直下配送线上支付', 103, 1, NOW(), 1, NOW(), 0),
  (9500012, '000000', 9100012, 'mock-sku-direct-pickup-cod-002', 'Mock 直下门店付款', 'draft', 'direct_order', 'pickup_store', 'cod_store',
   NULL, NULL, 1, '{"pickupStoreCode":"store-shibuya","mockBehavior":""}', NULL, '测试直下门店付款', 103, 1, NOW(), 1, NOW(), 0),
  (9500013, '000000', 9100013, 'mock-sku-cart-online-003', 'Mock 加购配送线上支付', 'draft', 'cart_checkout', 'shipping', 'online',
   NULL, NULL, 3, '{"deliveryOption":"priority","mockBehavior":""}', NULL, '测试加购配送线上支付', 103, 1, NOW(), 1, NOW(), 0),
  (9500014, '000000', 9100014, 'mock-sku-cart-pickup-cod-004', 'Mock 加购门店付款', 'draft', 'cart_checkout', 'pickup_store', 'cod_store',
   NULL, NULL, 1, '{"pickupStoreCode":"store-shinjuku","mockBehavior":""}', NULL, '测试加购门店付款', 103, 1, NOW(), 1, NOW(), 0);

INSERT INTO `ticket_sale_task_account` (
  `binding_id`, `tenant_id`, `task_id`, `account_id`,
  `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`
) VALUES
  (9600011, '000000', 9500011, 9300011, 103, 1, NOW(), 1, NOW(), 0),
  (9600012, '000000', 9500012, 9300012, 103, 1, NOW(), 1, NOW(), 0),
  (9600013, '000000', 9500013, 9300013, 103, 1, NOW(), 1, NOW(), 0),
  (9600014, '000000', 9500014, 9300014, 103, 1, NOW(), 1, NOW(), 0);
