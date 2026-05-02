-- 账号登录请求上下文：与注册/手机号请求上下文 req_data 分离

SET @login_req_data_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_managed_account'
    AND column_name = 'login_req_data'
);

SET @login_req_data_sql := IF(
  @login_req_data_exists = 0,
  'ALTER TABLE `ticket_managed_account` ADD COLUMN `login_req_data` longtext COMMENT ''登录请求上下文(JSON)'' AFTER `req_data`',
  'SELECT 1'
);
PREPARE stmt_login_req_data FROM @login_req_data_sql;
EXECUTE stmt_login_req_data;
DEALLOCATE PREPARE stmt_login_req_data;
