"""通过 Flask test client 将 gRPC Invoke 转发到现有 /api/v1 路由。"""

from __future__ import annotations

import json
from typing import Any

from flask import Flask


def _session_from_set_cookie(headers) -> str | None:
    for header in headers.getlist("Set-Cookie"):
        part = header.split(";", 1)[0]
        if part.startswith("session="):
            return part[len("session=") :]
    return None


def _session_from_client(client, prior: str | None) -> str | None:
    """Flask test client 有时把 cookie 放进 jar 而不出现在 Set-Cookie 头里。"""
    try:
        getter = getattr(client, "get_cookie", None)
        if not callable(getter):
            return None
        value = getter("session")
        if value and value != prior:
            return value
    except Exception:
        pass
    return None


def invoke_flask_api(
    app: Flask,
    method: str,
    path: str,
    *,
    session: str | None = None,
    csrf: str | None = None,
    body_json: str | None = None,
    query: dict[str, str] | None = None,
) -> dict[str, Any]:
    body = None
    if body_json:
        body = json.loads(body_json)

    headers: dict[str, str] = {}
    if csrf:
        headers["X-CSRF-Token"] = csrf

    query_string = {k: v for k, v in (query or {}).items() if v}
    incoming_session = (session or "").strip() or None

    with app.test_client() as client:
        if incoming_session:
            client.set_cookie("session", incoming_session)

        http_method = (method or "GET").upper()
        call = getattr(client, http_method.lower())
        kwargs: dict[str, Any] = {"headers": headers}
        if query_string:
            kwargs["query_string"] = query_string
        if body is not None:
            kwargs["json"] = body

        resp = call(path, **kwargs)
        text = resp.get_data(as_text=True) or "{}"
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            payload = {
                "ok": False,
                "error": "bad_response",
                "message": f"服务器响应异常（{resp.status_code}）。",
            }

        new_session = _session_from_set_cookie(resp.headers)
        if not new_session:
            new_session = _session_from_client(client, incoming_session)
        csrf_token = payload.get("csrf_token") if isinstance(payload, dict) else None

        return {
            "ok": bool(payload.get("ok")) if isinstance(payload, dict) else False,
            "error": str(payload.get("error", "error")) if isinstance(payload, dict) else "error",
            "message": str(payload.get("message", "")) if isinstance(payload, dict) else "",
            "body_json": json.dumps(payload, ensure_ascii=False),
            "http_status": resp.status_code,
            "session": new_session or "",
            "csrf_token": csrf_token or "",
        }
