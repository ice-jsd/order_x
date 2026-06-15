-- 将 PC 端 token 不活跃超时从 30 分钟调整为 1 小时
update sys_client
set active_timeout = 3600
where client_id = 'e5cd7e4891bf95d1d19206ce24a7b32e'
  and client_key = 'pc'
  and active_timeout = 1800;
