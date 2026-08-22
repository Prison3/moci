"""Moci ApiService gRPC 网关。"""

from __future__ import annotations

import grpc

from flask import Flask
from grpc_gen import moci_pb2, moci_pb2_grpc

import grpc_dispatch


class ApiServicer(moci_pb2_grpc.ApiServiceServicer):
    def __init__(self, app: Flask):
        self._app = app

    def Invoke(self, request, context):
        with self._app.app_context():
            try:
                result = grpc_dispatch.invoke_flask_api(
                    self._app,
                    request.method,
                    request.path,
                    session=request.session or None,
                    csrf=request.csrf_token or None,
                    body_json=request.body_json or None,
                    query=dict(request.query),
                )
                return moci_pb2.ApiInvokeResponse(**result)
            except Exception as exc:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(str(exc))
                return moci_pb2.ApiInvokeResponse(
                    ok=False,
                    error="internal",
                    message="服务器内部错误。",
                    http_status=500,
                )
