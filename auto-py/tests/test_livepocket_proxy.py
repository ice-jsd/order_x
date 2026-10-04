import os
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from livepocket_proxy import LivePocketProxyPool, parse_proxy_string  # noqa: E402


class LivePocketProxyTests(unittest.TestCase):
    def test_proxy_url_escapes_credentials(self):
        proxy = parse_proxy_string(
            "http://user%40example.com:p%3A%40ss@gate.decodo.com:7000",
            provider="decodo",
            region="JP",
        )

        self.assertIsNotNone(proxy)
        self.assertEqual(proxy.username, "user@example.com")
        self.assertEqual(proxy.password, "p:@ss")
        self.assertEqual(
            proxy.requests_url(),
            "http://user%40example.com:p%3A%40ss@gate.decodo.com:7000",
        )

    def test_decodo_builds_independent_japan_sticky_sessions(self):
        env = {
            "LIVEPOCKET_PROXY_PROVIDER": "decodo",
            "LIVEPOCKET_PROXY_REGION": "JP",
            "LIVEPOCKET_PROXY_GATEWAY": "gate.decodo.com:7000",
            "LIVEPOCKET_PROXY_USERNAME": "proxy-user",
            "LIVEPOCKET_PROXY_PASSWORD": "proxy-password",
            "LIVEPOCKET_PROXY_TARGET_APPROVED": "true",
            "LIVEPOCKET_PROXY_SESSION_MINUTES": "90",
            "LIVEPOCKET_PROXY_STATIC": "",
        }
        with patch.dict(os.environ, env, clear=False):
            pool = LivePocketProxyPool()
            proxies = pool._build_decodo_proxies(2, Mock())

        self.assertEqual(len(proxies), 2)
        self.assertEqual({proxy.host for proxy in proxies}, {"gate.decodo.com"})
        self.assertEqual({proxy.port for proxy in proxies}, {"7000"})
        self.assertTrue(all(proxy.provider == "decodo" for proxy in proxies))
        self.assertTrue(all("-country-jp-" in proxy.username for proxy in proxies))
        self.assertTrue(all(proxy.username.endswith("-sessionduration-90") for proxy in proxies))
        self.assertEqual(len({proxy.username for proxy in proxies}), 2)

    def test_decodo_requires_proxy_credentials(self):
        env = {
            "LIVEPOCKET_PROXY_PROVIDER": "decodo",
            "LIVEPOCKET_PROXY_USERNAME": "",
            "LIVEPOCKET_PROXY_PASSWORD": "",
            "LIVEPOCKET_PROXY_TARGET_APPROVED": "true",
            "LIVEPOCKET_PROXY_STATIC": "",
        }
        logger = Mock()
        with patch.dict(os.environ, env, clear=False):
            pool = LivePocketProxyPool()
            proxies = pool._build_decodo_proxies(1, logger)

        self.assertEqual(proxies, [])
        logger.warning.assert_called_once()

    def test_decodo_requires_target_approval(self):
        env = {
            "LIVEPOCKET_PROXY_PROVIDER": "decodo",
            "LIVEPOCKET_PROXY_USERNAME": "proxy-user",
            "LIVEPOCKET_PROXY_PASSWORD": "proxy-password",
            "LIVEPOCKET_PROXY_TARGET_APPROVED": "false",
            "LIVEPOCKET_PROXY_STATIC": "",
        }
        logger = Mock()
        with patch.dict(os.environ, env, clear=False):
            pool = LivePocketProxyPool()
            proxies = pool._build_decodo_proxies(1, logger)

        self.assertEqual(proxies, [])
        logger.warning.assert_called_once()


if __name__ == "__main__":
    unittest.main()
