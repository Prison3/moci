#!/usr/bin/env python3
"""本地测试 gRPC：登录 → 双向流 → 家长改设置 → 推送。"""

from __future__ import annotations

import json
import os
import queue
import sys
import threading
import time
from pathlib import Path

import grpc

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "server"
sys.path.insert(0, str(SERVER))

from grpc_gen import moci_pb2, moci_pb2_grpc  # noqa: E402

GRPC_ADDR = os.environ.get("MOCI_TEST_GRPC", "127.0.0.1:50052")


def grpc_invoke(
    method: str,
    path: str,
    *,
    session: str = "",
    csrf: str = "",
    body: dict | None = None,
) -> tuple[dict, str, str]:
    channel = grpc.insecure_channel(GRPC_ADDR)
    stub = moci_pb2_grpc.ApiServiceStub(channel)
    req = moci_pb2.ApiInvokeRequest(
        method=method,
        path=path,
        session=session,
        csrf_token=csrf,
        body_json=json.dumps(body) if body is not None else "",
    )
    resp = stub.Invoke(req, timeout=10)
    channel.close()
    data = json.loads(resp.body_json or "{}")
    if not resp.ok:
        raise RuntimeError(data.get("message") or resp.message or "请求失败")
    return data, resp.session or session, resp.csrf_token or csrf


def login(username: str, password: str) -> tuple[str, str]:
    data, session, csrf = grpc_invoke(
        "POST",
        "/api/v1/auth/login",
        body={"username": username, "password": password},
    )
    user = data["user"]
    print(f"登录成功: {user['username']} (id={user['id']}, role={user['role']})")
    return session, csrf


def stream_session(session_cookie: str, out: queue.Queue) -> None:
    channel = grpc.insecure_channel(GRPC_ADDR)
    stub = moci_pb2_grpc.SyncServiceStub(channel)

    def iter_requests():
        yield moci_pb2.ClientMessage(hello=moci_pb2.Hello(session=session_cookie))
        while True:
            time.sleep(25)
            yield moci_pb2.ClientMessage(ping=moci_pb2.Ping())

    try:
        for msg in stub.Connect(iter_requests()):
            out.put(msg)
    except grpc.RpcError as exc:
        out.put(exc)
    finally:
        channel.close()


def parent_update_child(session_cookie: str, csrf: str, child_id: int) -> None:
    grpc_invoke(
        "POST",
        f"/api/v1/profile/child/{child_id}/settings",
        session=session_cookie,
        csrf=csrf,
        body={
            "daily_words": 12,
            "daily_review": 10,
            "know_speak": True,
            "know_spell": True,
            "know_pos": True,
            "know_phonetic": True,
        },
    )
    print("家长已保存设置: daily_words=12, daily_review=10")


def main() -> None:
    from app import app, create_app

    create_app()

    with app.app_context():
        from db import connect

        db = connect()
        learner = db.execute(
            "SELECT id, username FROM users WHERE role = 'user' ORDER BY id LIMIT 1"
        ).fetchone()
        parent = db.execute(
            "SELECT id, username FROM users WHERE role = 'parent' ORDER BY id LIMIT 1"
        ).fetchone()
        db.close()
        if not learner:
            raise SystemExit("数据库里没有学生账号，请先在 App 或 Web 注册。")
        if not parent:
            raise SystemExit("数据库里没有家长账号。")

    password = os.environ.get("MOCI_TEST_PASSWORD", "123456")
    username = learner["username"]
    user_id = learner["id"]
    parent_name = parent["username"]

    print(f"学生: {username} (id={user_id})")
    print(f"家长: {parent_name} (id={parent['id']})")
    session, _ = login(username, password)

    received: queue.Queue = queue.Queue()
    t = threading.Thread(target=stream_session, args=(session, received), daemon=True)
    t.start()

    deadline = time.time() + 10
    ready = False
    while time.time() < deadline:
        try:
            msg = received.get(timeout=1)
        except queue.Empty:
            continue
        if isinstance(msg, grpc.RpcError):
            raise SystemExit(f"gRPC 错误: {msg.code()} {msg.details()}")
        if msg.HasField("ready"):
            print(f"gRPC 已连接，user_id={msg.ready.user_id}")
            ready = True
            break
        if msg.HasField("error"):
            raise SystemExit(f"认证失败: {msg.error.message}")

    if not ready:
        raise SystemExit("10 秒内未收到 Ready")

    parent_session, parent_csrf = login(parent_name, password)
    parent_update_child(parent_session, parent_csrf, user_id)

    deadline = time.time() + 5
    while time.time() < deadline:
        try:
            msg = received.get(timeout=1)
        except queue.Empty:
            continue
        if msg.HasField("settings_updated"):
            u = msg.settings_updated.user
            print(
                f"收到推送: daily_words={u.daily_words}, daily_review={u.daily_review}, "
                f"know_speak={u.know_speak}, know_spell={u.know_spell}"
            )
            print("本地 gRPC 测试通过。")
            return

    raise SystemExit("5 秒内未收到 settings_updated 推送")


if __name__ == "__main__":
    main()
