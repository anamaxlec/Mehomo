#!/usr/bin/env python3
"""Local HTTPS fixture for Memoh feature screens and WebSocket decisions.

Uses the existing debug-only CA. It never contacts Memoh Cloud.
Run: python3 tools/api-integration-server.py --port 8444
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import ssl
import struct
import time
import uuid
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

spec = importlib.util.spec_from_file_location("baseline", Path(__file__).with_name("mock-server.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
memories = [{"id": "memory-1", "memory": "项目使用 Material 3 Expressive，菜单覆盖底部按钮。", "created_at": "2026-09-30T09:00:00Z"}]
schedules = [{"id": "schedule-1", "name": "每日资料整理", "pattern": "0 9 * * *", "command": "整理昨日资料，生成简报", "enabled": True, "current_calls": 3, "run_target": "new_session"}]
skills = [{"name": "Research Notes", "description": "整理调研笔记", "raw": "---\nname: research-notes\ndescription: Organize notes\n---\n\nSummarize the source.", "source_path": "/data/skills/research-notes/SKILL.md", "editable": True, "deletable": True, "state": "enabled", "managed": False}]
apps = []
mcps = [{"id": "mcp-1", "name": "资料搜索", "type": "http", "is_active": True, "status": "connected", "config": {"url": "https://example.com/mcp"}, "tools_cache": [{"name": "search", "description": "Search notes"}]}]
queues = {"steer_supported": True, "follow_up": [], "steer": []}
records = [{"id": str(i), "model_name": "DeepSeek V4.1 Flash", "session_type": "chat", "session_id": "sess-0", "created_at": "2026-09-30T09:00:00Z", "input_tokens": 1200, "output_tokens": 300} for i in range(65)]
controls = {"session_id": "sess-0", "capabilities": {"compact": True, "goal": True, "permission_modes": True, "plan_mode": True}, "commands": [{"name": "status", "description": "查看运行状态", "kind": "text"}], "modes": {"supported": True, "current_mode_id": "default", "available_modes": [{"id": "default", "name": "默认"}, {"id": "ask", "name": "操作前询问"}]}, "plan_mode": {"supported": True, "current_mode_id": "off", "available_modes": [{"id": "off", "name": "直接执行"}, {"id": "plan", "name": "先制定计划"}]}}
goal = {"objective": "整理项目调研资料", "status": "active", "tokens_used": 2400, "token_budget": 10000}
market = [{"registry_id": "official", "app_id": f"notes-{i}", "name": f"Research Toolkit {i + 1}", "description": "资料整理、搜索与记录工具", "version": "1.0.0"} for i in range(42)]
turn_id = str(uuid.uuid4())
history = [{"role": "assistant", "turn_id": turn_id, "messages": [{"id": 1, "type": "text", "role": "assistant", "content": "这是本地接口联调环境。可以测试菜单、审批与提问。"}]}]

class Handler(base.Handler):
    def read_body(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        return json.loads(raw) if raw else {}
    def empty(self):
        self.send_response(204); self.send_header("Content-Length", "0"); self.end_headers()
    def events(self, status="installed"):
        body = ''.join('data: ' + json.dumps(event, ensure_ascii=False) + '\n\n' for event in [
            {"type": "started", "kind": "app"}, {"type": "step", "kind": "skill", "id": "research-notes"},
            {"type": "log", "data": "Installing skills"}, {"type": "done", "status": status}]).encode()
        self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        p = urlparse(self.path); path = p.path; query = parse_qs(p.query)
        if path.endswith("/web/ws"): return self.websocket()
        if path.endswith("/sessions/events"):
            self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.send_header("Connection", "close"); self.end_headers()
            try:
                for _ in range(30): self.wfile.write(b": keepalive\n\n"); self.wfile.flush(); time.sleep(3)
            except (BrokenPipeError, ConnectionResetError): pass
            self.close_connection = True; return
        if path.startswith("/api/supermarket"):
            if "/registries/" in path: return self._send(200, {"revision": "fixture-revision", "name": "Research Toolkit"})
            page = int(query.get("page", ["1"])[0]); text = query.get("q", [""])[0].lower()
            data = [m for m in market if text in m["name"].lower()]
            if path.endswith("skills"): data = [dict(m, skill_id=m["app_id"], name=m["name"] + " Skill") for m in data]
            return self._send(200, {"data": data[(page-1)*30:page*30], "page": page, "total": len(data), "limit": 30})
        if path.endswith("/memory/status"): return self._send(200, {"provider_type": "builtin", "source_count": len(memories), "indexed_count": len(memories), "compact": {"semantic": True}})
        if path.endswith("/memory/graph"): return self._send(200, {"nodes": [{"id": "a", "label": "M3E"}, {"id": "b", "label": "菜单"}], "edges": [{"source": "a", "target": "b", "rel": "用于"}]})
        if path.endswith("/memory"): return self._send(200, {"results": memories})
        if path.endswith("/schedule"): return self._send(200, {"items": schedules})
        if path.endswith("/logs"): return self._send(200, {"items": [{"id": "log-1", "status": "success", "session_id": "sess-0", "result_text": "简报已生成。", "started_at": "2026-09-30T09:00:00Z"}], "total_count": 1})
        if path.endswith("/token-usage"): return self._send(200, {"chat": [{"day": "2026-09-30", "input_tokens": 78000, "output_tokens": 19500}], "by_model": [{"model_name": "DeepSeek V4.1 Flash", "input_tokens": 78000, "output_tokens": 19500}]})
        if path.endswith("/token-usage/records"):
            offset = int(query.get("offset", ["0"])[0]); return self._send(200, {"items": records[offset:offset+50], "total": len(records)})
        if path.endswith("/container/metrics"): return self._send(200, {"supported": True, "backend": "docker", "status": {"exists": True, "task_running": True}, "metrics": {"cpu": {"usage_percent": 3.4}, "memory": {"usage_bytes": 320000000, "limit_bytes": 2000000000}, "storage": {"used_bytes": 1234000000}}})
        if path.endswith("/container/skills"): return self._send(200, {"skills": skills})
        if path.endswith("/mcp"): return self._send(200, {"items": mcps})
        if path.endswith("/removal-preview"): return self._send(200, {"installation_id": path.split('/')[-2], "dependencies": [{"id": "python", "action": "keep", "reason": "shared"}], "connectors": [], "required_apps": []})
        if path.endswith("/apps"): return self._send(200, {"items": apps})
        if path.endswith("/queue"): return self._send(200, queues)
        if path.endswith("/runtime-controls"): return self._send(200, controls)
        if path.endswith("/runtime-controls/goal"): return self._send(200, {"goal": goal})
        if path.endswith("/messages"): return self._send(200, {"items": history})
        if "/sessions/" in path and path.split('/')[-1].startswith('sess-'):
            session = next((s for s in base.SESSIONS['bot-1'] if s['id'] == path.split('/')[-1]), None)
            return self._send(200, session or {"id": path.split('/')[-1], "bot_id": "bot-1", "runtime_type": "model", "channel_type": "local"})
        return super().do_GET()
    def do_POST(self):
        path = urlparse(self.path).path
        if path.endswith("/auth/login"): return super().do_POST()
        body = self.read_body()
        if path.endswith("/memory/search"): return self._send(200, {"results": [m for m in memories if body['query'].lower() in m['memory'].lower()]})
        if path.endswith("/memory/compact"): return self._send(200, {"success": True})
        if path.endswith("/memory"):
            assert 'message' in body; memories.append({"id": str(uuid.uuid4()), "memory": body['message']}); return self._send(200, {"results": memories})
        if path.endswith("/schedule"):
            assert 'command' in body; item = dict(body, id=str(uuid.uuid4()), current_calls=0); schedules.append(item); return self._send(200, item)
        if path.endswith("/mcp"):
            item = dict(body, id=str(uuid.uuid4()), type=body['transport'], status='connected', config=body, tools_cache=[]); mcps.append(item); return self._send(201, item)
        if path.endswith("/probe"): return self._send(200, {"status": "connected", "tools": []})
        if path.endswith("/container/skills"):
            raw = body['skills'][0]; item = next((s for s in skills if s['source_path'] == body.get('source_path')), None)
            if item: item['raw'] = raw
            else: skills.append({"name": "custom-skill", "raw": raw, "editable": True, "deletable": True, "state": "enabled", "source_path": '/data/skills/custom-skill/SKILL.md'})
            return self.empty()
        if path.endswith("/skills/actions"):
            for item in skills:
                if item['source_path'] == body['target_path']: item['state'] = 'disabled' if body['action'] == 'disable' else 'enabled'
            return self.empty()
        if path.endswith("/apps/check-updates"): return self._send(200, {"items": []})
        if path.endswith("/apps"):
            assert 'revision' in body; apps.append(dict(body, installation_id=str(uuid.uuid4()), name='Research Toolkit', status='installed', version='1.0.0', skills=[], connectors=[])); return self.events()
        if path.endswith('/apps/update') or path.endswith('/resume'): return self.events()
        if path.endswith("-queue"):
            q = queues['steer' if path.endswith('/steer-queue') else 'follow_up']; item = {'item_id': str(uuid.uuid4()), 'text': body['text'], 'status': 'pending', 'position': len(q)}; q.append(item); return self._send(200, item)
        if path.endswith('/steer') and '/follow-up-queue/' in path:
            item = next(i for i in queues['follow_up'] if i['item_id'] == path.split('/')[-2]); queues['follow_up'].remove(item); queues['steer'].append(item); return self.empty()
        if path.endswith('/runtime-controls/goal'):
            goal['status'] = 'paused' if body['action'] == 'pause' else 'cleared'; return self.empty()
        if path.endswith('/runtime-controls/commands'): return self._send(200, {'text': '运行状态正常'})
        if path.endswith('/quick-actions/execute'): return self._send(200, {'type': 'command_result', 'result': {'kind': 'list', 'title': '快捷操作', 'items': [{'title': '帮助', 'description': '查看可用操作'}, {'title': '技能', 'description': '查看已安装技能'}]}})
        if path.endswith('/compact'): return self._send(200, {'status': 'completed'})
        if path.endswith('/fork') or path.endswith('/sessions'):
            item = dict(body, id='sess-' + str(uuid.uuid4()), bot_id='bot-1', runtime_type='model', channel_type='local', title='新会话'); base.SESSIONS['bot-1'].insert(0, item); return self._send(201, item)
        return self._send(404, {'message': 'Fixture route missing'})
    def do_PATCH(self): return self.modify('PATCH')
    def do_PUT(self): return self.modify('PUT')
    def modify(self, method):
        path = urlparse(self.path).path; body = self.read_body(); ident = path.split('/')[-1]
        if path.endswith('/reorder'):
            q = queues['steer' if '/steer-queue/' in path else 'follow_up']; item = next(i for i in q if i['item_id'] == body['item']['item_id']); q.remove(item); before = body['before'].get('item_id'); index = next((i for i,v in enumerate(q) if v['item_id'] == before),len(q)); q.insert(index,item); return self.empty()
        if path.endswith('/runtime-controls/mode'):
            modes = controls['plan_mode' if body.get('mode_kind') == 'plan' else 'modes']; modes['current_mode_id'] = body['mode_id']; return self._send(200, controls)
        if '-queue/' in path:
            q = queues['steer' if '/steer-queue/' in path else 'follow_up']; next(i for i in q if i['item_id'] == ident)['text'] = body['text']; return self.empty()
        if '/sessions/' in path:
            item = next(s for s in base.SESSIONS['bot-1'] if s['id'] == ident); item.update(body); item['model_preference_revision'] = str(uuid.uuid4()); return self._send(200, item)
        for marker, collection in [('/memory/', memories), ('/schedule/', schedules), ('/mcp/', mcps)]:
            if marker in path:
                item = next(i for i in collection if i['id'] == ident); item.update(body)
                if marker == '/mcp/': item['config'] = body
                return self._send(200, item)
        return self._send(404, {'message': 'Fixture route missing'})
    def do_DELETE(self):
        path = urlparse(self.path).path; body = self.read_body(); ident = path.split('/')[-1]
        if path.endswith('/container/skills'): skills[:] = [s for s in skills if s['source_path'] not in body['source_paths']]; return self.empty()
        if '-queue/' in path:
            key = 'steer' if '/steer-queue/' in path else 'follow_up'; queues[key] = [i for i in queues[key] if i['item_id'] != ident]; return self.empty()
        for marker, collection, key in [('/memory/', memories, 'id'), ('/schedule/', schedules, 'id'), ('/mcp/', mcps, 'id'), ('/apps/', apps, 'installation_id')]:
            if marker in path:
                collection[:] = [i for i in collection if i.get(key) != ident]
                return self.events('removed') if marker == '/apps/' else self.empty()
        return self._send(404, {'message': 'Fixture route missing'})
    def ws_send(self, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        prefix = bytes([0x81, len(data)]) if len(data) < 126 else bytes([0x81, 126]) + struct.pack('!H', len(data))
        self.wfile.write(prefix + data); self.wfile.flush()
    def ws_read(self):
        prefix = self.rfile.read(2)
        if len(prefix) < 2 or prefix[0] & 15 == 8: return None
        length = prefix[1] & 127
        if length == 126: length = struct.unpack('!H', self.rfile.read(2))[0]
        elif length == 127: length = struct.unpack('!Q', self.rfile.read(8))[0]
        mask = self.rfile.read(4) if prefix[1] & 128 else None
        data = self.rfile.read(length)
        if mask: data = bytes(v ^ mask[i % 4] for i,v in enumerate(data))
        if prefix[0] & 15 != 1: return {}
        return json.loads(data)
    def websocket(self):
        accept = base64.b64encode(hashlib.sha1((self.headers['Sec-WebSocket-Key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        self.send_response(101); self.send_header('Upgrade', 'websocket'); self.send_header('Connection', 'Upgrade'); self.send_header('Sec-WebSocket-Accept', accept); self.end_headers()
        sid = 'sess-0'; seq = 0; run = None
        def snapshot():
            nonlocal seq
            seq += 1
            self.ws_send({'type': 'runtime_snapshot', 'session_id': sid, 'epoch': 'fixture-epoch', 'seq': seq, 'snapshot': {'bot_id': 'bot-1', 'session_id': sid, 'epoch': 'fixture-epoch', 'seq': seq, 'current_run_view': run}})
        try:
            while True:
                message = self.ws_read()
                if message is None: break
                kind = message.get('type')
                print('  WS', kind, {k:v for k,v in message.items() if k in ['model_id', 'reasoning_effort', 'workspace_target_id', 'decision', 'option_id', 'answers', 'canceled']}, flush=True)
                if kind == 'runtime_subscribe': sid = message['session_id']; snapshot()
                elif kind == 'message':
                    self.ws_send({'type': 'run_accepted', 'session_id': sid, 'invocation_id': message['invocation_id'], 'run_id': 'run-1'})
                    run = {'run_id': 'run-1', 'turn_id': str(uuid.uuid4()), 'status': 'waiting_decision', 'messages': [{'id': 2, 'role': 'assistant', 'type': 'tool', 'name': 'read_file', 'tool_approval': {'approval_id': 'approval-1', 'status': 'pending', 'options': [{'id': 'allow-once', 'name': '允许一次', 'kind': 'allow_once'}]}}]}; snapshot()
                elif kind in ['tool_approval_response', 'user_input_response', 'abort']:
                    self.ws_send({'type': 'control_ack', 'session_id': sid, 'control': kind, 'control_id': message['control_id'], 'applied': True})
                    if kind == 'tool_approval_response':
                        run['messages'] = [{'id': 3, 'role': 'assistant', 'type': 'user_input', 'user_input': {'user_input_id': 'question-1', 'status': 'pending', 'questions': [{'id': 'q1', 'text': '使用哪种输出格式？', 'kind': 'single_select', 'options': [{'id': 'markdown', 'label': 'Markdown'}, {'id': 'text', 'label': '纯文本'}], 'required': True, 'allow_custom': True}]}}]
                    else: run['status'] = 'completed'; run['messages'] = [{'id': 4, 'role': 'assistant', 'type': 'text', 'content': '审批与提问响应已收到，任务完成。'}]
                    snapshot()
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError): pass
        self.close_connection = True

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--port', type=int, default=8444); args = parser.parse_args()
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain('/tmp/memoh-mock/cert.pem', '/tmp/memoh-mock/key.pem')
    server.socket = ctx.wrap_socket(server.socket, server_side=True)
    print(f'Local API fixture: https://127.0.0.1:{args.port}', flush=True); server.serve_forever()
