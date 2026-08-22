"""gRPC 推送：家长修改孩子设置后通知在线客户端。"""

from __future__ import annotations

from grpc_gen import moci_pb2
import grpc_hub


def _user_settings(user: dict) -> moci_pb2.UserSettings:
    return moci_pb2.UserSettings(
        id=int(user["id"]),
        username=user["username"],
        role=user["role"],
        status=user.get("status") or "approved",
        daily_words=int(user.get("daily_words") or 8),
        daily_review=int(user.get("daily_review") or 8),
        know_speak=bool(int(user.get("know_speak") or 1)),
        know_spell=bool(int(user.get("know_spell") or 1)),
        know_pos=bool(int(user.get("know_pos") or 1)),
        know_phonetic=bool(int(user.get("know_phonetic") or 1)),
    )


def push_settings_updated(user_id: int, user: dict) -> None:
    message = moci_pb2.ServerMessage(
        settings_updated=moci_pb2.SettingsUpdated(user=_user_settings(user))
    )
    grpc_hub.publish(user_id, message)
