#!/usr/bin/env python3
"""
A throwaway Memoh server for driving the app's login -> session list path
without a real deployment.

Implements only the endpoints that path touches:

    GET  /api/ping                    reachability probe
    POST /api/auth/login              bearer token
    GET  /api/bots                    the bot list
    GET  /api/bots/{id}/sessions      the session list

Serves HTTPS because ServerUrl refuses cleartext outright, so a debug build
must trust the certificate in `app/src/debug/res/raw/memoh_debug_ca.pem`.
That trust anchor exists only in debug builds.

    python3 tools/mock-server.py --port 8443
"""

import argparse
import json
import ssl
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BOTS = [
    {
        "id": "bot-1",
        "name": "assistant",
        "display_name": "研究助手",
        "is_active": True,
        "status": "running",
        "current_user_permissions": ["read", "write"],
    },
    {
        "id": "bot-2",
        "name": "writer",
        "display_name": "写作 Bot",
        "is_active": True,
        "status": "idle",
    },
]

_BOT1_TITLES = [
    "整理这周的调研笔记",
    "给部署脚本加健康检查",
    "把 CI 的缓存策略改回分层",
    "排查日志里偶发的 502",
    "把重试逻辑抽成一个策略",
    "对比三个向量库的召回率",
    "给 CLI 加一个 --dry-run",
    "把配置文件从 TOML 换成 YAML 的代价",
    "复盘上周的线上事故",
    "把告警阈值重新标定一遍",
    "给接口加幂等键",
    "整理数据库索引的冗余项",
    "写一份部署回滚手册",
    "把定时任务从 cron 迁到调度器",
    "评估要不要引入缓存层",
]

SESSIONS = {
    # Long enough to actually scroll, so the bar's collapse behaviour is
    # observable rather than assumed.
    "bot-1": [
        {
            "id": f"sess-{i}",
            "bot_id": "bot-1",
            "title": title,
            "type": "chat",
            "runtime_type": "model",
            "channel_type": "local",
            "updated_at": (
                "2026-09-29T20:10:00+08:00" if i < 4 else f"2026-09-{28 - i % 5:02d}T11:02:00+08:00"
            ),
        }
        for i, title in enumerate(_BOT1_TITLES)
    ],
    "bot-2": [
        {
            "id": "sess-3",
            "bot_id": "bot-2",
            "title": "周报初稿",
            "type": "chat",
            "runtime_type": "model",
            "channel_type": "telegram",
            "updated_at": "2026-09-29T09:30:00+08:00",
        }
    ],
}


WORKDIRS = {
    "bot-1": [
        {
            "id": "wd-1",
            "bot_id": "bot-1",
            "name": "memoh-android",
            "path": "/root/memoh-android",
            "archived": False,
            "created_at": "2026-09-20T10:00:00+08:00",
        },
        {
            "id": "wd-2",
            "bot_id": "bot-1",
            "name": "infra",
            "path": "/srv/infra",
            "archived": False,
            "created_at": "2026-09-18T10:00:00+08:00",
        },
    ],
    "bot-2": [],
}


AGENTS = {
    "bot-1": [
        {"id": "ag-1", "bot_id": "bot-1", "name": "Pi 原生", "runtime": "pi", "enabled": True},
        {"id": "ag-2", "bot_id": "bot-1", "name": "Claude Code", "runtime": "claude-code", "enabled": True},
    ],
    "bot-2": [],
}

MODELS = [
    {
        "id": "deepseek-v4.1-flash",
        "type": "chat",
        "enable": True,
        "name": "deepseek-v4.1-flash",
        "display_name": "DeepSeek V4.1 Flash",
        "provider_name": "DeepSeek",
        "capabilities": {"reasoning_efforts": ["低", "中", "高"]},
    },
    {
        "id": "kimi-k3",
        "type": "chat",
        "enable": True,
        "display_name": "Kimi K3",
        "provider_name": "Moonshot",
        "capabilities": {"reasoning_efforts": ["低", "高"]},
    },
    {
        "id": "text-embed",
        "type": "embedding",
        "enable": True,
        "display_name": "Embedding",
    },
]

TARGETS = {
    "bot-1": {
        "targets": [
            {"target_id": "t-1", "kind": "container", "name": "主容器", "primary": True, "online": True},
            {"target_id": "t-2", "kind": "container", "name": "备用容器", "online": False},
        ]
    },
    "bot-2": {"targets": []},
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # Flush per line: the process is usually backgrounded to a file, and a
        # buffered log makes a working server look like a dead one.
        print(f"  {self.command} {self.path}", flush=True)

    def _send(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _token_ok(self):
        return (self.headers.get("Authorization") or "").startswith("Bearer ")

    def do_GET(self):
        path = self.path.split("?")[0]

        if path == "/api/ping":
            return self._send(200, {"status": "ok", "version": "mock-1.0"})

        if path == "/api/bots":
            if not self._token_ok():
                return self._send(401, {"detail": "missing token"})
            return self._send(200, {"items": BOTS})

        if path.startswith("/api/bots/") and path.endswith("/agents"):
            if not self._token_ok():
                return self._send(401, {"detail": "missing token"})
            bot_id = path.split("/")[3]
            return self._send(200, {"items": AGENTS.get(bot_id, [])})

        if path == "/api/models":
            if not self._token_ok():
                return self._send(401, {"detail": "missing token"})
            return self._send(200, MODELS)

        if path.startswith("/api/bots/") and path.endswith("/workspace-targets"):
            if not self._token_ok():
                return self._send(401, {"detail": "missing token"})
            bot_id = path.split("/")[3]
            return self._send(200, TARGETS.get(bot_id, {"targets": []}))

        if path.startswith("/api/bots/") and "/messages" in path:
            if not self._token_ok():
                return self._send(401, {"detail": "missing token"})
            return self._send(200, {"items": []})

        if path.startswith("/api/bots/") and path.endswith("/workdirs"):
            if not self._token_ok():
                return self._send(401, {"detail": "missing token"})
            bot_id = path.split("/")[3]
            # Note: "workdirs", not "items" — this endpoint differs.
            return self._send(200, {"workdirs": WORKDIRS.get(bot_id, [])})

        if path.startswith("/api/bots/") and path.endswith("/sessions"):
            if not self._token_ok():
                return self._send(401, {"detail": "missing token"})
            bot_id = path.split("/")[3]
            return self._send(200, {"items": SESSIONS.get(bot_id, [])})

        return self._send(404, {"detail": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"

        if path == "/api/auth/login":
            try:
                creds = json.loads(raw or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"detail": "bad json"})
            if not creds.get("username") or not creds.get("password"):
                return self._send(401, {"detail": "invalid credentials"})
            return self._send(
                200,
                {
                    "access_token": "mock-token-abc",
                    "token_type": "Bearer",
                    "user_id": "user-1",
                    "username": creds["username"],
                    "display_name": creds["username"],
                },
            )

        return self._send(404, {"detail": "not found"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8443)
    ap.add_argument("--cert", default="/tmp/memoh-mock/cert.pem")
    ap.add_argument("--key", default="/tmp/memoh-mock/key.pem")
    args = ap.parse_args()

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(args.cert, args.key)

    # Threading, not single-threaded: OkHttp holds a keep-alive connection open,
    # and a serial server blocks every later request behind it — which surfaces
    # in the client as "Read timed out" rather than as anything diagnostic.
    httpd = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    httpd.daemon_threads = True
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print(f"mock Memoh server on https://0.0.0.0:{args.port} (emulator: 10.0.2.2:{args.port})", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
