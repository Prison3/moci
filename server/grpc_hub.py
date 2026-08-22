"""gRPC 连接注册表：按 user_id 推送 ServerMessage。"""

from __future__ import annotations

import queue
import threading
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from grpc_gen import moci_pb2

_lock = threading.Lock()
_streams: dict[int, set[queue.Queue]] = {}


def subscribe(user_id: int) -> queue.Queue:
    q: queue.Queue = queue.Queue(maxsize=32)
    with _lock:
        _streams.setdefault(user_id, set()).add(q)
    return q


def unsubscribe(user_id: int, q: queue.Queue) -> None:
    with _lock:
        streams = _streams.get(user_id)
        if not streams:
            return
        streams.discard(q)
        if not streams:
            del _streams[user_id]


def publish(user_id: int, message: moci_pb2.ServerMessage) -> None:
    with _lock:
        streams = list(_streams.get(user_id, ()))
    for q in streams:
        try:
            q.put_nowait(message)
        except queue.Full:
            pass


def publish_all(message: moci_pb2.ServerMessage) -> None:
    with _lock:
        queues = [q for streams in _streams.values() for q in streams]
    for q in queues:
        try:
            q.put_nowait(message)
        except queue.Full:
            pass
