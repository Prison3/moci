"""启动 gRPC 服务（与 Flask 同进程，默认 :50051）。"""

from __future__ import annotations

import os
from concurrent import futures

import grpc
from flask import Flask

from grpc_gen import moci_pb2_grpc
from grpc_api_servicer import ApiServicer
from grpc_servicer import SyncServicer

_server: grpc.Server | None = None


def start_grpc_server(app: Flask) -> None:
    global _server
    if _server is not None:
        return
    port = int(os.environ.get("GRPC_PORT", "50051"))
    _server = grpc.server(futures.ThreadPoolExecutor(max_workers=8))
    moci_pb2_grpc.add_SyncServiceServicer_to_server(SyncServicer(app), _server)
    moci_pb2_grpc.add_ApiServiceServicer_to_server(ApiServicer(app), _server)
    _server.add_insecure_port(f"[::]:{port}")
    _server.start()
    app.logger.info("gRPC SyncService listening on :%s", port)


def stop_grpc_server() -> None:
    global _server
    if _server is None:
        return
    _server.stop(grace=2)
    _server = None
