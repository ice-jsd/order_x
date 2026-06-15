"""
统一票务 Python 宿主入口

职责：
- 对外暴露 HTTP 路由与 Redis Worker 启动入口
- 兼容现有 LivePocket / Hands Form / Jump Shop 平台接入

说明：
- 当前底层实现仍复用 livepocket_lottery.py 中已经沉淀的宿主类与运行时。
- 新代码应优先从本模块导入宿主入口，而不是继续直接依赖 livepocket_lottery.py。
"""

from livepocket_lottery import (
    LotteryRedisWorker,
    LotteryRedisWorkerConfig,
    LotteryRequestHandler,
    main,
    start_http_server,
)

__all__ = [
    "LotteryRedisWorker",
    "LotteryRedisWorkerConfig",
    "LotteryRequestHandler",
    "main",
    "start_http_server",
]
