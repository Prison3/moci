"""从 Flask session cookie 解析当前用户 id。"""

from __future__ import annotations

from flask import Flask


def user_id_from_session(app: Flask, session_cookie: str) -> int | None:
    cookie = (session_cookie or "").strip()
    if not cookie:
        return None
    serializer = app.session_interface.get_signing_serializer(app)
    if serializer is None:
        return None
    try:
        data = serializer.loads(cookie)
    except Exception:
        return None
    user_id = data.get("user_id")
    if not user_id:
        return None
    try:
        return int(user_id)
    except (TypeError, ValueError):
        return None
