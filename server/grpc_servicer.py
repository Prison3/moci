"""Moci SyncService gRPC 双向流实现。"""

from __future__ import annotations

import queue
import threading
from typing import Iterator

import grpc

from flask import Flask
from grpc_gen import moci_pb2, moci_pb2_grpc

import grpc_auth
import grpc_hub


class SyncServicer(moci_pb2_grpc.SyncServiceServicer):
    def __init__(self, app: Flask):
        self._app = app

    def Connect(
        self,
        request_iterator: Iterator[moci_pb2.ClientMessage],
        context: grpc.ServicerContext,
    ) -> Iterator[moci_pb2.ServerMessage]:
        incoming: queue.Queue = queue.Queue()

        def _read_client() -> None:
            try:
                for message in request_iterator:
                    incoming.put(message)
            except Exception:
                pass
            finally:
                incoming.put(None)

        threading.Thread(target=_read_client, daemon=True).start()

        try:
            first = incoming.get(timeout=15)
        except queue.Empty:
            yield moci_pb2.ServerMessage(
                error=moci_pb2.Error(code="timeout", message="等待认证超时。")
            )
            return

        if first is None or not first.HasField("hello"):
            yield moci_pb2.ServerMessage(
                error=moci_pb2.Error(code="unauthorized", message="请先发送 hello。")
            )
            return

        with self._app.app_context():
            user_id = grpc_auth.user_id_from_session(self._app, first.hello.session)

        if not user_id:
            yield moci_pb2.ServerMessage(
                error=moci_pb2.Error(code="unauthorized", message="登录已失效。")
            )
            return

        out_q = grpc_hub.subscribe(user_id)
        try:
            yield moci_pb2.ServerMessage(ready=moci_pb2.Ready(user_id=user_id))
            while context.is_active():
                try:
                    server_msg = out_q.get(timeout=0.5)
                    yield server_msg
                except queue.Empty:
                    pass
                while True:
                    try:
                        client_msg = incoming.get_nowait()
                    except queue.Empty:
                        break
                    if client_msg is None:
                        return
                    if client_msg.HasField("ping"):
                        yield moci_pb2.ServerMessage(pong=moci_pb2.Pong())
        finally:
            grpc_hub.unsubscribe(user_id, out_q)
