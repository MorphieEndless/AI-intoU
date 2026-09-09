"""Deployment regression tests. All Docker/HTTP commands are stubs; no service is started."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'server').mkdir()
        (self.root / 'deploy').mkdir()
        (self.root / 'bin').mkdir()
        shutil.copy(ROOT / 'deploy/setup-server.sh', self.root / 'deploy')
        shutil.copy(ROOT / 'server/.env.example', self.root / 'server')
        self.env = dict(os.environ, PATH=f"{self.root / 'bin'}:{os.environ['PATH']}",
                        TEST_LOG=str(self.root / 'commands'))
        self.stub('docker', 'echo "$*" >> "$TEST_LOG"\nexit "${DOCKER_EXIT:-0}"')
        self.stub('curl', 'exit "${CURL_EXIT:-0}"')
        self.stub('sleep', 'exit 0')

    def stub(self, name, body):
        path = self.root / 'bin' / name
        path.write_text('#!/bin/sh\n' + body + '\n')
        path.chmod(0o755)

    def run_setup(self):
        return subprocess.run(['bash', str(self.root / 'deploy/setup-server.sh')],
                              env=self.env, text=True, capture_output=True, timeout=10)

    def config(self):
        return dict(line.split('=', 1) for line in (self.root / 'server/.env').read_text().splitlines()
                    if line and not line.startswith('#') and '=' in line)

    def test_new_install_safe_defaults_and_permissions(self):
        result = self.run_setup()
        self.assertEqual(0, result.returncode, result.stderr)
        cfg = self.config()
        self.assertEqual('true', cfg['SB_REQUIRE_MCP_AUTH'])
        self.assertEqual('false', cfg['SB_REGISTRATION_OPEN'])
        self.assertEqual('/data/signal_bridge.db', cfg['SB_DB_PATH'])
        self.assertEqual('/data/patterns', cfg['SB_PATTERNS_DIR'])
        self.assertEqual('127.0.0.1', cfg['SB_BIND_ADDRESS'])
        self.assertGreaterEqual(len(cfg['SB_STATIC_BEARER_TOKEN']), 32)
        self.assertEqual(64, len(cfg['SB_SECRET_KEY']))
        self.assertEqual(0o600, (self.root / 'server/.env').stat().st_mode & 0o777)
        self.assertIn('compose up -d --build', (self.root / 'commands').read_text())

    def test_rerun_preserves_config_without_printing_secrets(self):
        self.assertEqual(0, self.run_setup().returncode)
        path = self.root / 'server/.env'
        before = path.read_bytes()
        result = self.run_setup()
        self.assertEqual(0, result.returncode)
        self.assertEqual(before, path.read_bytes())
        self.assertNotIn(self.config()['SB_STATIC_BEARER_TOKEN'], result.stdout + result.stderr)
        self.assertNotIn(self.config()['SB_SECRET_KEY'], result.stdout + result.stderr)

    def test_missing_docker_daemon_does_not_create_env(self):
        self.env['DOCKER_EXIT'] = '1'
        self.assertNotEqual(0, self.run_setup().returncode)
        self.assertFalse((self.root / 'server/.env').exists())

    def test_health_failure_is_not_reported_as_success(self):
        self.env['CURL_EXIT'] = '7'
        result = self.run_setup()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('健康检查超时', result.stderr)
        self.assertNotIn('部署完成', result.stdout)

    def test_unsafe_existing_config_is_rejected_without_rebuild(self):
        self.assertEqual(0, self.run_setup().returncode)
        path = self.root / 'server/.env'
        path.write_text(path.read_text().replace('SB_REQUIRE_MCP_AUTH=true', 'SB_REQUIRE_MCP_AUTH=false'))
        before = path.read_bytes()
        (self.root / 'commands').write_text('')
        self.assertNotEqual(0, self.run_setup().returncode)
        self.assertEqual(before, path.read_bytes())
        self.assertNotIn('compose up', (self.root / 'commands').read_text())

    def test_old_data_path_requires_migration(self):
        self.assertEqual(0, self.run_setup().returncode)
        path = self.root / 'server/.env'
        path.write_text(path.read_text().replace('SB_DB_PATH=/data/signal_bridge.db', 'SB_DB_PATH=./signal_bridge.db'))
        (self.root / 'commands').write_text('')
        result = self.run_setup()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('迁移旧数据', result.stderr)
        self.assertNotIn('compose up', (self.root / 'commands').read_text())


if __name__ == '__main__':
    unittest.main()
