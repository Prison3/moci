#!/usr/bin/env python3
"""墨词 — 移动优先的记单词系统。"""

from __future__ import annotations

import hashlib
import os
import re
import secrets
import unicodedata
from datetime import datetime, timedelta
from functools import wraps
from pathlib import Path

from flask import (
    Flask,
    flash,
    g,
    jsonify,
    redirect,
    render_template,
    request,
    send_from_directory,
    session,
    url_for,
)
from werkzeug.security import check_password_hash, generate_password_hash

import db as database

# 部分旧 Python/OpenSSL 构建（如 macOS 自带 3.9）没有 hashlib.scrypt，回退 pbkdf2。
_HASH_METHOD = "scrypt" if hasattr(hashlib, "scrypt") else "pbkdf2:sha256"


def make_password_hash(password: str) -> str:
    return generate_password_hash(password, method=_HASH_METHOD)

BASE_DIR = Path(__file__).resolve().parent
INSTANCE_DIR = BASE_DIR / "instance"
DOWNLOADS_DIR = BASE_DIR / "downloads"
APP_APK_NAME = "moci.apk"
APP_VERSION_NAME = os.environ.get("APP_VERSION_NAME", "1.0.0")
APP_VERSION_CODE = int(os.environ.get("APP_VERSION_CODE", "1"))
_SCHEMA_READY = False

USERNAME_RE = re.compile(r"^[A-Za-z0-9_\u4e00-\u9fff]{2,20}$")
ROLE_USER = "user"
ROLE_ADMIN = "admin"
ROLE_PARENT = "parent"
STATUS_PENDING = "pending"
STATUS_APPROVED = "approved"
STATUS_REJECTED = "rejected"
DEFAULT_DAILY_WORDS = 8
DEFAULT_DAILY_REVIEW = 8
MIN_DAILY_WORDS = 0
MAX_DAILY_WORDS = 50
KIND_NEW = "new"
KIND_REVIEW = "review"
KIND_LABELS = {KIND_NEW: "新词学习", KIND_REVIEW: "复习"}


def _load_secret() -> str:
    INSTANCE_DIR.mkdir(parents=True, exist_ok=True)
    secret_file = INSTANCE_DIR / "secret_key"
    if secret_file.exists():
        return secret_file.read_text(encoding="utf-8").strip()
    key = secrets.token_hex(32)
    secret_file.write_text(key, encoding="utf-8")
    return key


app = Flask(__name__)
app.config["SECRET_KEY"] = os.environ.get("SECRET_KEY") or _load_secret()
app.config["SESSION_COOKIE_HTTPONLY"] = True
app.config["SESSION_COOKIE_SAMESITE"] = "Lax"
app.config["TEMPLATES_AUTO_RELOAD"] = True


def get_db() -> database.Database:
    if "db" not in g:
        INSTANCE_DIR.mkdir(parents=True, exist_ok=True)
        g.db = database.connect()
    return g.db


@app.teardown_appcontext
def close_db(_exc: BaseException | None = None) -> None:
    db = g.pop("db", None)
    if db is not None:
        if _exc is not None:
            db.rollback()
        db.close()


def init_db() -> None:
    global _SCHEMA_READY
    if _SCHEMA_READY:
        return
    database.init_schema(get_db())
    _SCHEMA_READY = True


def now_iso() -> str:
    return datetime.now().replace(microsecond=0).isoformat(sep=" ")


def current_user():
    user_id = session.get("user_id")
    if not user_id:
        return None
    return get_db().execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()


def is_admin(user) -> bool:
    return bool(user) and user["role"] == ROLE_ADMIN


def is_parent(user) -> bool:
    return bool(user) and user["role"] == ROLE_PARENT


def is_learner(user) -> bool:
    return bool(user) and user["role"] == ROLE_USER


def is_approved(user) -> bool:
    if not user:
        return False
    if is_admin(user):
        return True
    status = user["status"] if "status" in user.keys() else STATUS_APPROVED
    return status == STATUS_APPROVED


def _kick_unapproved(user) -> None:
    session.clear()
    status = user["status"] if user and "status" in user.keys() else STATUS_PENDING
    if status == STATUS_REJECTED:
        flash("账号未通过审核，请联系管理员。", "error")
    else:
        flash("账号正在等待管理员同意，通过后才能登录。", "error")


def login_required(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        if not session.get("user_id"):
            next_url = request.path if request.method == "GET" else url_for("home")
            return redirect(url_for("login", next=next_url))
        user = current_user()
        if not user:
            session.clear()
            return redirect(url_for("login"))
        if not is_approved(user):
            _kick_unapproved(user)
            return redirect(url_for("login"))
        return view(*args, **kwargs)

    return wrapped


def admin_required(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        if not session.get("user_id"):
            next_url = request.path if request.method == "GET" else url_for("home")
            return redirect(url_for("login", next=next_url))
        user = current_user()
        if not user:
            session.clear()
            return redirect(url_for("login"))
        if not is_admin(user):
            session.clear()
            flash("网页端仅供管理员使用，学生和家长请使用 App。", "error")
            return redirect(url_for("login"))
        return view(*args, **kwargs)

    return wrapped


def csrf_token() -> str:
    token = session.get("_csrf")
    if not token:
        token = secrets.token_hex(16)
        session["_csrf"] = token
    return token


def validate_csrf() -> bool:
    sent = request.form.get("csrf_token") or request.headers.get("X-CSRF-Token", "")
    return bool(sent) and secrets.compare_digest(sent, session.get("_csrf", ""))


@app.context_processor
def inject_globals():
    user = current_user()
    return {
        "current_user": user,
        "is_admin": is_admin(user),
        "is_parent": is_parent(user),
        "is_learner": is_learner(user),
        "csrf_token": csrf_token(),
        "active_page": request.endpoint,
        "board_href": board_href,
    }


def parse_kind(raw: str | None) -> str | None:
    value = (raw or "").strip()
    return value if value in KIND_LABELS else None


def board_href(endpoint: str, date: str, user_id=None, kind=None) -> str:
    kwargs: dict = {"date": date}
    if user_id:
        kwargs["user_id"] = user_id
    if kind:
        kwargs["kind"] = kind
    return url_for(endpoint, **kwargs)


def word_stats(user_id: int) -> dict:
    db = get_db()
    total = db.execute("SELECT COUNT(*) AS n FROM words").fetchone()["n"]
    row = db.execute(
        """
        SELECT
            SUM(CASE WHEN COALESCE(p.status, 'new') = 'new' THEN 1 ELSE 0 END) AS new_count,
            SUM(CASE WHEN p.status = 'learning' THEN 1 ELSE 0 END) AS learning,
            SUM(CASE WHEN p.status = 'mastered' THEN 1 ELSE 0 END) AS mastered
        FROM words w
        LEFT JOIN progress p ON p.word_id = w.id AND p.user_id = ?
        """,
        (user_id,),
    ).fetchone()
    due = db.execute(
        """
        SELECT COUNT(*) AS n
        FROM words w
        LEFT JOIN progress p ON p.word_id = w.id AND p.user_id = ?
        WHERE COALESCE(p.status, 'new') = 'new'
          AND (p.next_review IS NULL OR p.next_review <= ?)
        """,
        (user_id, now_iso()),
    ).fetchone()["n"]
    return {
        "total": total or 0,
        "new_count": row["new_count"] or 0,
        "learning": row["learning"] or 0,
        "mastered": row["mastered"] or 0,
        "due": due or 0,
    }


def normalize_spelling(text: str) -> str:
    value = (text or "").strip().lower()
    value = re.sub(r"\s+", " ", value)
    return value


def normalize_phonetic(text: str) -> str:
    value = unicodedata.normalize("NFC", (text or "").strip())
    value = value.strip("/").strip()
    value = value.replace("g", "ɡ")
    value = re.sub(r"[\s./]", "", value)
    return value


def normalize_spoken(text: str) -> str:
    value = (text or "").strip().lower()
    value = re.sub(r"[^a-z0-9\s]", " ", value)
    value = re.sub(r"\s+", " ", value)
    return value.strip()


def spoken_matches(spoken: str, term: str) -> bool:
    want = normalize_spoken(term)
    said = normalize_spoken(spoken)
    if not want or not said:
        return False
    if said == want:
        return True
    said_tokens = said.split()
    want_tokens = want.split()
    n = len(want_tokens)
    for i in range(len(said_tokens) - n + 1):
        if said_tokens[i : i + n] == want_tokens:
            return True
    return False


def schedule_review(progress, rating: str) -> dict:
    """新词学会→复习；复习学会→掌握。不认识则回到新词。"""
    streak = (progress["correct_streak"] if progress else 0) or 0
    count = ((progress["review_count"] if progress else 0) or 0) + 1
    now = datetime.now().replace(microsecond=0)
    prev = (progress["status"] if progress else "new") or "new"

    if rating == "again":
        status = "new"
        streak = 0
        nxt = now + timedelta(minutes=10)
    elif prev == "learning":
        streak += 1
        status = "mastered"
        nxt = now + timedelta(days=3650)
    else:
        streak += 1
        status = "learning"
        nxt = now

    return {
        "status": status,
        "correct_streak": streak,
        "review_count": count,
        "last_reviewed": now.isoformat(sep=" "),
        "next_review": nxt.isoformat(sep=" "),
        "updated_at": now.isoformat(sep=" "),
    }


def fetch_progress(user_id: int, word_id: int):
    return (
        get_db()
        .execute(
            "SELECT * FROM progress WHERE user_id = ? AND word_id = ?",
            (user_id, word_id),
        )
        .fetchone()
    )


def due_cards(
    user_id: int,
    limit: int = 40,
    exclude_ids: list[int] | None = None,
    kind: str = KIND_NEW,
) -> list[dict]:
    if limit <= 0:
        return []
    if kind == KIND_REVIEW:
        sql = """
            SELECT w.id, w.term, w.phonetic, w.pos, w.meaning, w.phrase, w.phrase_zh, w.example, w.example_zh, w.notes,
                   COALESCE(p.status, 'new') AS status,
                   COALESCE(p.review_count, 0) AS review_count,
                   COALESCE(p.correct_streak, 0) AS correct_streak,
                   p.last_reviewed, p.next_review
            FROM words w
            JOIN progress p ON p.word_id = w.id AND p.user_id = ?
            WHERE p.status = 'learning'
        """
        params: list = [user_id]
    else:
        sql = """
            SELECT w.id, w.term, w.phonetic, w.pos, w.meaning, w.phrase, w.phrase_zh, w.example, w.example_zh, w.notes,
                   COALESCE(p.status, 'new') AS status,
                   COALESCE(p.review_count, 0) AS review_count,
                   COALESCE(p.correct_streak, 0) AS correct_streak,
                   p.last_reviewed, p.next_review
            FROM words w
            LEFT JOIN progress p ON p.word_id = w.id AND p.user_id = ?
            WHERE COALESCE(p.status, 'new') = 'new'
              AND (p.next_review IS NULL OR p.next_review <= ?)
        """
        params = [user_id, now_iso()]
    if exclude_ids:
        placeholders = ",".join("?" * len(exclude_ids))
        sql += f" AND w.id NOT IN ({placeholders})"
        params.extend(exclude_ids)
    if kind == KIND_REVIEW:
        sql += " ORDER BY p.last_reviewed ASC LIMIT ?"
    else:
        sql += """
            ORDER BY CASE COALESCE(p.status, 'new')
                WHEN 'new' THEN 0 WHEN 'learning' THEN 1 ELSE 2 END,
                p.next_review ASC
            LIMIT ?
        """
    params.append(limit)
    rows = get_db().execute(sql, params).fetchall()
    cards = [dict(row) for row in rows]
    for card in cards:
        card["kind"] = kind
    return cards


def count_due_cards(
    user_id: int,
    kind: str,
    exclude_ids: list[int] | None = None,
) -> int:
    """词库里现在还能拿来学的张数（不含今日已学过的）。"""
    if kind == KIND_REVIEW:
        sql = """
            SELECT COUNT(*) AS n
            FROM words w
            JOIN progress p ON p.word_id = w.id AND p.user_id = ?
            WHERE p.status = 'learning'
        """
        params: list = [user_id]
    else:
        sql = """
            SELECT COUNT(*) AS n
            FROM words w
            LEFT JOIN progress p ON p.word_id = w.id AND p.user_id = ?
            WHERE COALESCE(p.status, 'new') = 'new'
              AND (p.next_review IS NULL OR p.next_review <= ?)
        """
        params = [user_id, now_iso()]
    if exclude_ids:
        placeholders = ",".join("?" * len(exclude_ids))
        sql += f" AND w.id NOT IN ({placeholders})"
        params.extend(exclude_ids)
    return get_db().execute(sql, params).fetchone()["n"] or 0


def clamp_daily_words(raw, default: int = DEFAULT_DAILY_WORDS) -> int:
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return default
    return max(MIN_DAILY_WORDS, min(MAX_DAILY_WORDS, value))


def _user_int(user, key: str, default: int) -> int:
    if not user:
        return default
    try:
        raw = user[key]
    except (KeyError, IndexError, TypeError):
        return default
    if raw is None:
        return default
    return clamp_daily_words(raw, default)


def daily_words_of(user) -> int:
    return _user_int(user, "daily_words", DEFAULT_DAILY_WORDS)


def daily_review_of(user) -> int:
    return _user_int(user, "daily_review", DEFAULT_DAILY_REVIEW)


def know_checks_of(user) -> dict:
    speak = _user_int(user, "know_speak", 1) == 1
    spell = _user_int(user, "know_spell", 1) == 1
    pos = _user_int(user, "know_pos", 1) == 1
    phonetic = _user_int(user, "know_phonetic", 1) == 1
    return {"speak": speak, "spell": spell, "pos": pos, "phonetic": phonetic}


def parse_pos_tags(raw) -> list[str]:
    if isinstance(raw, (list, tuple)):
        parts = [str(p).strip() for p in raw if str(p).strip()]
        return list(dict.fromkeys(parts))
    text = str(raw or "").strip()
    if not text:
        return []
    parts = [
        p.strip()
        for p in re.split(r"[/|,，、;；|]+|\s+", text)
        if p.strip()
    ]
    return list(dict.fromkeys(parts))


def quotas_for(user_ids: list[int] | None) -> tuple[int | None, int | None]:
    if not user_ids:
        return None, None
    placeholders = ",".join("?" * len(user_ids))
    row = get_db().execute(
        f"""
        SELECT COALESCE(SUM(daily_words), 0) AS new_q,
               COALESCE(SUM(daily_review), 0) AS review_q
        FROM users WHERE id IN ({placeholders})
        """,
        user_ids,
    ).fetchone()
    return int(row["new_q"] or 0), int(row["review_q"] or 0)


def today_reviewed_word_ids(
    user_id: int, kind: str | None = None, day: str | None = None
) -> list[int]:
    day = day or datetime.now().strftime("%Y-%m-%d")
    start, end = day_bounds(day)
    sql = """
        SELECT DISTINCT word_id FROM review_logs
        WHERE user_id = ? AND created_at >= ? AND created_at <= ?
    """
    params: list = [user_id, start, end]
    if kind:
        sql += " AND kind = ?"
        params.append(kind)
    rows = get_db().execute(sql, params).fetchall()
    return [row["word_id"] for row in rows]


def _part(quota: int, done: int) -> dict:
    remaining = max(0, quota - done)
    return {"quota": quota, "done": done, "remaining": remaining}


def today_task(user_id: int, user=None) -> dict:
    if user is None:
        user = get_db().execute(
            "SELECT daily_words, daily_review FROM users WHERE id = ?", (user_id,)
        ).fetchone()
    new_q = daily_words_of(user)
    review_q = daily_review_of(user)
    new_done_ids = today_reviewed_word_ids(user_id, KIND_NEW)
    review_done_ids = today_reviewed_word_ids(user_id, KIND_REVIEW)
    new_done = len(new_done_ids)
    review_done = len(review_done_ids)
    # 与发卡片时相同：今日已学过的词都排除。没有下一张可学的卡时 remaining 为 0。
    done_ids = today_reviewed_word_ids(user_id)
    new_left = count_due_cards(user_id, KIND_NEW, done_ids)
    review_left = count_due_cards(user_id, KIND_REVIEW, done_ids)
    new_q = min(new_q, new_done + new_left)
    review_q = min(review_q, review_done + review_left)
    new_part = _part(new_q, new_done)
    review_part = _part(review_q, review_done)
    remaining = min(new_part["remaining"], new_left) + min(
        review_part["remaining"], review_left
    )
    return {
        "new": {**new_part, "remaining": min(new_part["remaining"], new_left)},
        "review": {**review_part, "remaining": min(review_part["remaining"], review_left)},
        "quota": new_q + review_q,
        "done": new_done + review_done,
        "remaining": remaining,
    }


def month_study_calendar(user_id: int, user=None) -> dict:
    now = datetime.now()
    year, month = now.year, now.month
    first = datetime(year, month, 1)
    if month == 12:
        nxt = datetime(year + 1, 1, 1)
    else:
        nxt = datetime(year, month + 1, 1)
    last_day = (nxt - timedelta(days=1)).day
    start = first.strftime("%Y-%m-%d 00:00:00")
    end = (nxt - timedelta(seconds=1)).strftime("%Y-%m-%d %H:%M:%S")
    rows = get_db().execute(
        """
        SELECT SUBSTR(created_at, 1, 10) AS day,
               COALESCE(kind, 'new') AS kind,
               COUNT(DISTINCT word_id) AS words
        FROM review_logs
        WHERE user_id = ? AND created_at >= ? AND created_at <= ?
        GROUP BY 1, 2
        """,
        (user_id, start, end),
    ).fetchall()
    by_day: dict[str, dict[str, int]] = {}
    for row in rows:
        day = row["day"]
        if not day:
            continue
        bucket = by_day.setdefault(day, {"new": 0, "review": 0})
        if row["kind"] == KIND_REVIEW:
            bucket["review"] = row["words"] or 0
        else:
            bucket["new"] = row["words"] or 0

    if user is None:
        user = get_db().execute(
            "SELECT daily_words, daily_review FROM users WHERE id = ?", (user_id,)
        ).fetchone()
    new_q = daily_words_of(user)
    review_q = daily_review_of(user)
    today = now.strftime("%Y-%m-%d")
    today_info = today_task(user_id, user)
    cells: list[dict] = [{"blank": True} for _ in range(first.weekday())]
    studied_days = 0
    complete_days = 0
    for day_n in range(1, last_day + 1):
        date_s = f"{year:04d}-{month:02d}-{day_n:02d}"
        bucket = by_day.get(date_s, {"new": 0, "review": 0})
        new_n = bucket["new"]
        review_n = bucket["review"]
        studied = (new_n + review_n) > 0
        if date_s == today:
            remaining = today_info["remaining"]
        else:
            remaining = max(0, new_q - new_n) + max(0, review_q - review_n)
        complete = remaining == 0 and studied
        future = date_s > today
        if studied:
            studied_days += 1
        if complete:
            complete_days += 1
        cells.append(
            {
                "blank": False,
                "day": day_n,
                "date": date_s,
                "new_n": new_n,
                "review_n": review_n,
                "studied": studied,
                "complete": complete,
                "today": date_s == today,
                "future": future,
            }
        )
    while len(cells) % 7:
        cells.append({"blank": True})
    today_cell = next((c for c in cells if c.get("today")), None)
    return {
        "year": year,
        "month": month,
        "title": f"{year}年{month}月",
        "cells": cells,
        "today_cell": today_cell,
        "studied_days": studied_days,
        "complete_days": complete_days,
        "new_quota": new_q,
        "review_quota": review_q,
        "today": today,
    }


def _shift_month(year: int, month: int, delta: int) -> tuple[int, int]:
    month += delta
    year += (month - 1) // 12
    month = (month - 1) % 12 + 1
    return year, month


def month_learning_calendar(
    allowed_ids: list[int] | None,
    day: str,
    child_count: int = 0,
) -> dict:
    selected = datetime.strptime(day, "%Y-%m-%d")
    year, month = selected.year, selected.month
    first = datetime(year, month, 1)
    if month == 12:
        nxt = datetime(year + 1, 1, 1)
    else:
        nxt = datetime(year, month + 1, 1)
    last_day = (nxt - timedelta(days=1)).day
    start = first.strftime("%Y-%m-%d 00:00:00")
    end = (nxt - timedelta(seconds=1)).strftime("%Y-%m-%d %H:%M:%S")
    today = datetime.now().strftime("%Y-%m-%d")
    by_day: dict[str, dict[str, int]] = {}
    if allowed_ids is None or allowed_ids:
        extra = ""
        params: list = [start, end]
        if allowed_ids is not None:
            placeholders = ",".join("?" * len(allowed_ids))
            extra = f" AND user_id IN ({placeholders})"
            params.extend(allowed_ids)
        rows = get_db().execute(
            f"""
            SELECT SUBSTR(created_at, 1, 10) AS day,
                   COUNT(DISTINCT CASE WHEN COALESCE(kind, 'new') = 'new' THEN user_id || ':' || word_id END) AS new_n,
                   COUNT(DISTINCT CASE WHEN kind = 'review' THEN user_id || ':' || word_id END) AS review_n,
                   COUNT(DISTINCT user_id) AS learners
            FROM review_logs
            WHERE created_at >= ? AND created_at <= ?{extra}
            GROUP BY 1
            """,
            params,
        ).fetchall()
        for row in rows:
            key = row["day"]
            if not key:
                continue
            by_day[key] = {
                "new": row["new_n"] or 0,
                "review": row["review_n"] or 0,
                "learners": row["learners"] or 0,
            }

    cells: list[dict] = [{"blank": True} for _ in range(first.weekday())]
    studied_days = 0
    for day_n in range(1, last_day + 1):
        date_s = f"{year:04d}-{month:02d}-{day_n:02d}"
        bucket = by_day.get(date_s, {"new": 0, "review": 0, "learners": 0})
        new_n = bucket["new"]
        review_n = bucket["review"]
        learners = bucket["learners"]
        studied = learners > 0 or (new_n + review_n) > 0
        complete = child_count > 0 and learners >= child_count
        if studied:
            studied_days += 1
        cells.append(
            {
                "blank": False,
                "day": day_n,
                "date": date_s,
                "new_n": new_n,
                "review_n": review_n,
                "learners": learners,
                "studied": studied,
                "complete": complete,
                "today": date_s == today,
                "selected": date_s == day,
                "future": date_s > today,
            }
        )
    while len(cells) % 7:
        cells.append({"blank": True})
    selected_cell = next((c for c in cells if c.get("selected")), None)
    now = datetime.now()
    prev_y, prev_m = _shift_month(year, month, -1)
    next_y, next_m = _shift_month(year, month, 1)
    next_allowed = (next_y, next_m) <= (now.year, now.month)
    new_q, review_q = quotas_for(allowed_ids)
    return {
        "year": year,
        "month": month,
        "title": f"{year}年{month}月",
        "cells": cells,
        "selected_cell": selected_cell,
        "studied_days": studied_days,
        "prev_date": f"{prev_y:04d}-{prev_m:02d}-01",
        "next_date": f"{next_y:04d}-{next_m:02d}-01" if next_allowed else "",
        "new_quota": new_q,
        "review_quota": review_q,
    }


def day_study_words(user_id: int, day: str) -> list[dict]:
    start, end = day_bounds(day)
    rows = get_db().execute(
        """
        SELECT w.term, w.meaning,
               COALESCE(w.phrase, '') AS phrase,
               COALESCE(w.phrase_zh, '') AS phrase_zh,
               COALESCE(w.example, '') AS example,
               COALESCE(w.example_zh, '') AS example_zh,
               COALESCE(p.status, 'new') AS status,
               last.rating,
               COALESCE(last.kind, 'new') AS kind
        FROM (
            SELECT word_id, MAX(id) AS log_id
            FROM review_logs
            WHERE user_id = ? AND created_at >= ? AND created_at <= ?
            GROUP BY word_id
        ) g
        JOIN review_logs last ON last.id = g.log_id
        JOIN words w ON w.id = g.word_id
        LEFT JOIN progress p ON p.word_id = g.word_id AND p.user_id = ?
        ORDER BY last.created_at DESC
        """,
        (user_id, start, end, user_id),
    ).fetchall()
    return [
        {
            "term": row["term"],
            "meaning": row["meaning"],
            "phrase": row["phrase"] or "",
            "phrase_zh": row["phrase_zh"] or "",
            "example": row["example"] or "",
            "example_zh": row["example_zh"] or "",
            "status": row["status"] or "new",
            "rating": row["rating"],
            "kind": row["kind"] or KIND_NEW,
        }
        for row in rows
    ]


def collect_day_words(
    user_ids: list[int], day: str, kind: str | None = None
) -> list[dict]:
    if not user_ids:
        return []
    named = len(user_ids) > 1
    names: dict[int, str] = {}
    if named:
        placeholders = ",".join("?" * len(user_ids))
        rows = get_db().execute(
            f"SELECT id, username FROM users WHERE id IN ({placeholders})",
            user_ids,
        ).fetchall()
        names = {row["id"]: row["username"] for row in rows}
    logs: list[dict] = []
    for uid in user_ids:
        for word in day_study_words(uid, day):
            if kind and word["kind"] != kind:
                continue
            if named:
                word["username"] = names.get(uid, "")
            logs.append(word)
    return logs


def list_words(user_id: int, q: str = "", status: str = ""):
    sql = """
        SELECT w.*, COALESCE(p.status, 'new') AS status,
               p.next_review, p.last_reviewed
        FROM words w
        LEFT JOIN progress p ON p.word_id = w.id AND p.user_id = ?
        WHERE 1 = 1
    """
    params: list = [user_id]
    if q:
        sql += " AND (w.term LIKE ? OR w.meaning LIKE ? OR w.phonetic LIKE ? OR w.phrase LIKE ? OR w.example LIKE ?)"
        like = f"%{q}%"
        params.extend([like, like, like, like, like])
    if status in {"new", "learning", "mastered"}:
        sql += " AND COALESCE(p.status, 'new') = ?"
        params.append(status)
    sql += " ORDER BY w.updated_at DESC"
    return get_db().execute(sql, params).fetchall()


def list_library(q: str = ""):
    sql = "SELECT * FROM words WHERE 1 = 1"
    params: list = []
    if q:
        sql += " AND (term LIKE ? OR meaning LIKE ? OR phonetic LIKE ? OR COALESCE(phrase,'') LIKE ? OR example LIKE ?)"
        like = f"%{q}%"
        params.extend([like, like, like, like, like])
    sql += " ORDER BY updated_at DESC"
    return get_db().execute(sql, params).fetchall()


def day_bounds(day: str) -> tuple[str, str]:
    return f"{day} 00:00:00", f"{day} 23:59:59"


def parse_day(raw: str | None) -> str:
    today = datetime.now().strftime("%Y-%m-%d")
    value = (raw or "").strip()
    try:
        datetime.strptime(value, "%Y-%m-%d")
    except ValueError:
        return today
    if value > today:
        return today
    return value


def child_ids_of(parent_id: int) -> list[int]:
    rows = get_db().execute(
        """
        SELECT child_id FROM parent_children
        WHERE parent_id = ?
        ORDER BY child_id ASC
        """,
        (parent_id,),
    ).fetchall()
    return [row["child_id"] for row in rows]


def children_of(parent_id: int):
    return get_db().execute(
        """
        SELECT u.id, u.username, u.role, u.status, u.created_at, u.daily_words, u.daily_review,
               u.know_speak, u.know_spell, u.know_pos, u.know_phonetic
        FROM parent_children pc
        JOIN users u ON u.id = pc.child_id
        WHERE pc.parent_id = ?
        ORDER BY u.username ASC
        """,
        (parent_id,),
    ).fetchall()


def parents_of(child_id: int):
    return get_db().execute(
        """
        SELECT u.id, u.username, u.status, u.created_at
        FROM parent_children pc
        JOIN users u ON u.id = pc.parent_id
        WHERE pc.child_id = ?
        ORDER BY u.username ASC
        """,
        (child_id,),
    ).fetchall()


def _switch_session(user) -> None:
    session["user_id"] = user["id"]
    session["_csrf"] = secrets.token_hex(16)


def learning_report(day: str, allowed_ids: list[int] | None = None, detail_id: int | None = None):
    start, end = day_bounds(day)
    db = get_db()
    if allowed_ids is not None and not allowed_ids:
        empty = {
            "reviews": 0,
            "learners": 0,
            "again_n": 0,
            "easy_n": 0,
            "new_n": 0,
            "review_n": 0,
        }
        return {"summary": empty, "by_user": [], "detail_user": None, "logs": []}

    extra = ""
    params: list = [start, end]
    if allowed_ids is not None:
        placeholders = ",".join("?" * len(allowed_ids))
        extra = f" AND l.user_id IN ({placeholders})"
        params.extend(allowed_ids)

    summary = db.execute(
        f"""
        SELECT
            COUNT(*) AS reviews,
            COUNT(DISTINCT l.user_id) AS learners,
            SUM(CASE WHEN l.rating = 'easy' THEN 1 ELSE 0 END) AS easy_n,
            SUM(CASE WHEN l.rating != 'easy' THEN 1 ELSE 0 END) AS again_n,
            SUM(CASE WHEN COALESCE(l.kind, 'new') = 'new' THEN 1 ELSE 0 END) AS new_n,
            SUM(CASE WHEN l.kind = 'review' THEN 1 ELSE 0 END) AS review_n
        FROM review_logs l
        WHERE l.created_at >= ? AND l.created_at <= ?{extra}
        """,
        params,
    ).fetchone()
    by_user = db.execute(
        f"""
        SELECT u.id, u.username,
               COUNT(l.id) AS reviews,
               COUNT(DISTINCT l.word_id) AS words,
               SUM(CASE WHEN l.rating = 'easy' THEN 1 ELSE 0 END) AS easy_n,
               SUM(CASE WHEN l.rating != 'easy' THEN 1 ELSE 0 END) AS again_n,
               SUM(CASE WHEN COALESCE(l.kind, 'new') = 'new' THEN 1 ELSE 0 END) AS new_n,
               SUM(CASE WHEN l.kind = 'review' THEN 1 ELSE 0 END) AS review_n,
               MAX(l.created_at) AS last_at
        FROM review_logs l
        JOIN users u ON u.id = l.user_id
        WHERE l.created_at >= ? AND l.created_at <= ?{extra}
        GROUP BY u.id
        ORDER BY reviews DESC, u.username ASC
        """,
        params,
    ).fetchall()
    detail_user = None
    logs = []
    if detail_id:
        if allowed_ids is not None and detail_id not in allowed_ids:
            detail_id = None
        if detail_id:
            detail_user = db.execute(
                "SELECT id, username FROM users WHERE id = ? AND role = ?",
                (detail_id, ROLE_USER),
            ).fetchone()
            if detail_user:
                logs = day_study_words(detail_id, day)
    return {
        "summary": summary,
        "by_user": by_user,
        "detail_user": detail_user,
        "logs": logs,
    }


def _user_count() -> int:
    return get_db().execute("SELECT COUNT(*) AS n FROM users").fetchone()["n"]


@app.before_request
def ensure_db():
    init_db()


@app.route("/")
@admin_required
def home():
    db = get_db()
    pending = db.execute(
        """
        SELECT id, username, role, created_at FROM users
        WHERE role != ? AND status = ?
        ORDER BY id ASC
        """,
        (ROLE_ADMIN, STATUS_PENDING),
    ).fetchall()
    recent = db.execute(
        "SELECT * FROM words ORDER BY created_at DESC LIMIT 6"
    ).fetchall()
    return render_template(
        "home.html",
        stats={"total": db.execute("SELECT COUNT(*) AS n FROM words").fetchone()["n"]},
        recent=recent,
        pending=pending,
        pending_count=len(pending),
        user_count=db.execute(
            "SELECT COUNT(*) AS n FROM users WHERE role = ?", (ROLE_USER,)
        ).fetchone()["n"],
        parent_count=db.execute(
            "SELECT COUNT(*) AS n FROM users WHERE role = ?", (ROLE_PARENT,)
        ).fetchone()["n"],
    )


@app.route("/register", methods=["GET", "POST"])
def register():
    if session.get("user_id"):
        return redirect(url_for("home"))
    if _user_count() > 0:
        flash("学生和家长请在 App 中注册，网页端仅供管理员登录。", "error")
        return redirect(url_for("login"))
    if request.method == "POST":
        if not validate_csrf():
            flash("请求已过期，请重试。", "error")
            return redirect(url_for("register"))
        username = (request.form.get("username") or "").strip()
        password = request.form.get("password") or ""
        confirm = request.form.get("confirm") or ""
        if not USERNAME_RE.match(username):
            flash("用户名需为 2–20 位字母、数字、下划线或中文。", "error")
        elif len(password) < 6:
            flash("密码至少 6 位。", "error")
        elif password != confirm:
            flash("两次输入的密码不一致。", "error")
        else:
            db = get_db()
            exists = db.execute(
                "SELECT id FROM users WHERE username = ?", (username,)
            ).fetchone()
            if exists:
                flash("该用户名已被占用。", "error")
            else:
                db.execute(
                    """
                    INSERT INTO users (username, password_hash, role, status, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (
                        username,
                        make_password_hash(password),
                        ROLE_ADMIN,
                        STATUS_APPROVED,
                        now_iso(),
                    ),
                )
                db.commit()
                user = db.execute(
                    "SELECT * FROM users WHERE username = ?", (username,)
                ).fetchone()
                session.clear()
                session["user_id"] = user["id"]
                session["_csrf"] = secrets.token_hex(16)
                flash("注册成功。你是第一位用户，已设为管理员。", "ok")
                return redirect(url_for("home"))
    return render_template("register.html")


@app.route("/login", methods=["GET", "POST"])
def login():
    if session.get("user_id") and is_admin(current_user()):
        return redirect(url_for("home"))
    if request.method == "POST":
        if not validate_csrf():
            flash("请求已过期，请重试。", "error")
            return redirect(url_for("login"))
        username = (request.form.get("username") or "").strip()
        password = request.form.get("password") or ""
        user = (
            get_db()
            .execute("SELECT * FROM users WHERE username = ?", (username,))
            .fetchone()
        )
        if not user or not check_password_hash(user["password_hash"], password):
            flash("用户名或密码不正确。", "error")
        elif not is_admin(user):
            session.clear()
            flash("网页端仅供管理员使用，学生和家长请使用 App。", "error")
        elif not is_approved(user):
            _kick_unapproved(user)
        else:
            session.clear()
            session["user_id"] = user["id"]
            session["_csrf"] = secrets.token_hex(16)
            nxt = request.args.get("next") or url_for("home")
            if not nxt.startswith("/") or nxt.startswith("//"):
                nxt = url_for("home")
            return redirect(nxt)
    return render_template("login.html", allow_register=_user_count() == 0)


@app.route("/logout")
@login_required
def logout():
    session.clear()
    flash("已退出登录。", "ok")
    return redirect(url_for("login"))


@app.route("/words")
@admin_required
def words():
    q = (request.args.get("q") or "").strip()
    items = list_library(q=q)
    total = get_db().execute("SELECT COUNT(*) AS n FROM words").fetchone()["n"]
    return render_template(
        "words.html", words=items, q=q, status="", stats={"total": total}
    )


@app.route("/words/new", methods=["GET", "POST"])
@admin_required
def word_new():
    user = current_user()
    if request.method == "POST":
        if not validate_csrf():
            flash("请求已过期，请重试。", "error")
            return redirect(url_for("word_new"))
        data = _parse_word_form()
        if data.get("error"):
            flash(data["error"], "error")
            return render_template("word_form.html", word=data, mode="new")
        dup = (
            get_db()
            .execute(
                "SELECT id FROM words WHERE lower(term) = lower(?)",
                (data["term"],),
            )
            .fetchone()
        )
        if dup:
            flash("词库里已有这个单词。", "error")
            return render_template("word_form.html", word=data, mode="new")
        ts = now_iso()
        get_db().execute(
            """
            INSERT INTO words (
                term, phonetic, pos, meaning, phrase, phrase_zh, example, example_zh, notes, created_by, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                data["term"],
                data["phonetic"],
                data["pos"],
                data["meaning"],
                data["phrase"],
                data["phrase_zh"],
                data["example"],
                data["example_zh"],
                data["notes"],
                user["id"],
                ts,
                ts,
            ),
        )
        get_db().commit()
        _notify_words_updated("created")
        flash(f"已录入「{data['term']}」，所有用户都可以开始学习。", "ok")
        if request.form.get("again") == "1":
            return redirect(url_for("word_new"))
        return redirect(url_for("words"))
    return render_template("word_form.html", word=None, mode="new")


@app.route("/words/<int:word_id>")
@admin_required
def word_detail(word_id: int):
    word = get_db().execute("SELECT * FROM words WHERE id = ?", (word_id,)).fetchone()
    if not word:
        flash("找不到这个单词。", "error")
        return redirect(url_for("words"))
    return render_template("word_detail.html", word=word)


@app.route("/words/<int:word_id>/edit", methods=["GET", "POST"])
@admin_required
def word_edit(word_id: int):
    word = get_db().execute("SELECT * FROM words WHERE id = ?", (word_id,)).fetchone()
    if not word:
        flash("找不到这个单词。", "error")
        return redirect(url_for("words"))
    if request.method == "POST":
        if not validate_csrf():
            flash("请求已过期，请重试。", "error")
            return redirect(url_for("word_edit", word_id=word_id))
        data = _parse_word_form()
        if data.get("error"):
            flash(data["error"], "error")
            return render_template(
                "word_form.html", word=data | {"id": word_id}, mode="edit"
            )
        dup = (
            get_db()
            .execute(
                "SELECT id FROM words WHERE lower(term) = lower(?) AND id != ?",
                (data["term"], word_id),
            )
            .fetchone()
        )
        if dup:
            flash("词库里已有这个单词。", "error")
            return render_template(
                "word_form.html", word=data | {"id": word_id}, mode="edit"
            )
        get_db().execute(
            """
            UPDATE words SET term=?, phonetic=?, pos=?, meaning=?, phrase=?, phrase_zh=?, example=?, example_zh=?, notes=?, updated_at=?
            WHERE id=?
            """,
            (
                data["term"],
                data["phonetic"],
                data["pos"],
                data["meaning"],
                data["phrase"],
                data["phrase_zh"],
                data["example"],
                data["example_zh"],
                data["notes"],
                now_iso(),
                word_id,
            ),
        )
        get_db().commit()
        _notify_words_updated("updated", word_id)
        flash("已保存修改。", "ok")
        return redirect(url_for("word_detail", word_id=word_id))
    return render_template("word_form.html", word=word, mode="edit")


@app.route("/words/<int:word_id>/delete", methods=["POST"])
@admin_required
def word_delete(word_id: int):
    if not validate_csrf():
        flash("请求已过期，请重试。", "error")
        return redirect(url_for("words"))
    cur = get_db().execute("DELETE FROM words WHERE id = ?", (word_id,))
    get_db().commit()
    if cur.rowcount:
        _notify_words_updated("deleted", word_id)
    flash("已删除。" if cur.rowcount else "找不到这个单词。", "ok" if cur.rowcount else "error")
    return redirect(url_for("words"))


@app.route("/words/quick", methods=["POST"])
@admin_required
def word_quick():
    if not validate_csrf():
        flash("请求已过期，请重试。", "error")
        return redirect(url_for("words"))
    user = current_user()
    data = _parse_word_form(require_meaning=True)
    if data.get("error"):
        flash(data["error"], "error")
        return redirect(url_for("words"))
    dup = (
        get_db()
        .execute(
            "SELECT id FROM words WHERE lower(term) = lower(?)",
            (data["term"],),
        )
        .fetchone()
    )
    if dup:
        flash("词库里已有这个单词。", "error")
        return redirect(url_for("words"))
    ts = now_iso()
    get_db().execute(
        """
        INSERT INTO words (
            term, phonetic, pos, meaning, phrase, phrase_zh, example, example_zh, notes, created_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            data["term"],
            data["phonetic"],
            data["pos"],
            data["meaning"],
            data["phrase"],
            data["phrase_zh"],
            data["example"],
            data["example_zh"],
            data["notes"],
            user["id"],
            ts,
            ts,
        ),
    )
    get_db().commit()
    _notify_words_updated("created")
    flash(f"已录入「{data['term']}」。", "ok")
    return redirect(url_for("words"))


@app.route("/me")
@admin_required
def profile():
    return render_template("profile.html")


@app.route("/admin/users")
@admin_required
def admin_users():
    db = get_db()
    users = db.execute(
        """
        SELECT id, username, role, status, created_at FROM users
        ORDER BY CASE status WHEN 'pending' THEN 0 WHEN 'approved' THEN 1 ELSE 2 END, id ASC
        """
    ).fetchall()
    admin_count = sum(1 for u in users if u["role"] == ROLE_ADMIN)
    pending_count = sum(
        1 for u in users if u["role"] != ROLE_ADMIN and u["status"] == STATUS_PENDING
    )
    students = [
        u
        for u in users
        if u["role"] == ROLE_USER and u["status"] == STATUS_APPROVED
    ]
    links = db.execute(
        """
        SELECT pc.parent_id, u.id, u.username
        FROM parent_children pc
        JOIN users u ON u.id = pc.child_id
        ORDER BY u.username ASC
        """
    ).fetchall()
    children_map: dict[int, list] = {}
    for row in links:
        children_map.setdefault(row["parent_id"], []).append(row)
    return render_template(
        "admin_users.html",
        users=users,
        admin_count=admin_count,
        pending_count=pending_count,
        students=students,
        children_map=children_map,
    )


@app.route("/admin/users/<int:user_id>/status", methods=["POST"])
@admin_required
def admin_set_status(user_id: int):
    if not validate_csrf():
        flash("请求已过期，请重试。", "error")
        return redirect(url_for("admin_users"))
    status = (request.form.get("status") or "").strip()
    if status not in {STATUS_APPROVED, STATUS_REJECTED, STATUS_PENDING}:
        flash("无效的审核状态。", "error")
        return redirect(url_for("admin_users"))
    target = get_db().execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    if not target:
        flash("找不到该用户。", "error")
        return redirect(url_for("admin_users"))
    if target["role"] == ROLE_ADMIN:
        flash("不能修改管理员的审核状态。", "error")
        return redirect(url_for("admin_users"))
    get_db().execute("UPDATE users SET status = ? WHERE id = ?", (status, user_id))
    get_db().commit()
    labels = {
        STATUS_APPROVED: "已同意，可以登录",
        STATUS_REJECTED: "已拒绝",
        STATUS_PENDING: "改回待审核",
    }
    flash(f"{target['username']} {labels[status]}。", "ok")
    return redirect(url_for("admin_users"))


@app.route("/admin/users/<int:user_id>/role", methods=["POST"])
@admin_required
def admin_set_role(user_id: int):
    if not validate_csrf():
        flash("请求已过期，请重试。", "error")
        return redirect(url_for("admin_users"))
    role = (request.form.get("role") or "").strip()
    if role not in {ROLE_ADMIN, ROLE_USER, ROLE_PARENT}:
        flash("无效的角色。", "error")
        return redirect(url_for("admin_users"))
    target = get_db().execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    if not target:
        flash("找不到该用户。", "error")
        return redirect(url_for("admin_users"))
    if target["role"] == ROLE_ADMIN and role != ROLE_ADMIN:
        admin_n = get_db().execute(
            "SELECT COUNT(*) AS n FROM users WHERE role = ?", (ROLE_ADMIN,)
        ).fetchone()["n"]
        if admin_n <= 1:
            flash("至少保留一名管理员。", "error")
            return redirect(url_for("admin_users"))
    extra_sql = ""
    extra_params: list = []
    if role == ROLE_ADMIN:
        extra_sql = ", status = ?"
        extra_params = [STATUS_APPROVED]
    get_db().execute(
        f"UPDATE users SET role = ?{extra_sql} WHERE id = ?",
        (role, *extra_params, user_id),
    )
    if role != ROLE_PARENT:
        get_db().execute("DELETE FROM parent_children WHERE parent_id = ?", (user_id,))
    if role != ROLE_USER:
        get_db().execute("DELETE FROM parent_children WHERE child_id = ?", (user_id,))
    get_db().commit()
    labels = {ROLE_ADMIN: "管理员", ROLE_USER: "学生", ROLE_PARENT: "家长"}
    flash(f"已将 {target['username']} 设为{labels[role]}。", "ok")
    return redirect(url_for("admin_users"))


@app.route("/admin/users/<int:user_id>/children", methods=["POST"])
@admin_required
def admin_bind_child(user_id: int):
    if not validate_csrf():
        flash("请求已过期，请重试。", "error")
        return redirect(url_for("admin_users"))
    parent = get_db().execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    if not parent or parent["role"] != ROLE_PARENT:
        flash("只能给学生家长绑定孩子。", "error")
        return redirect(url_for("admin_users"))
    try:
        child_id = int(request.form.get("child_id") or "0")
    except ValueError:
        child_id = 0
    child = get_db().execute("SELECT * FROM users WHERE id = ?", (child_id,)).fetchone()
    if not child or child["role"] != ROLE_USER:
        flash("请选择有效的学生账号。", "error")
        return redirect(url_for("admin_users"))
    if child["status"] != STATUS_APPROVED:
        flash("只能绑定已审核通过的学生。", "error")
        return redirect(url_for("admin_users"))
    get_db().execute(
        """
        INSERT INTO parent_children (parent_id, child_id, created_at)
        VALUES (?, ?, ?)
        ON CONFLICT (parent_id, child_id) DO NOTHING
        """,
        (user_id, child_id, now_iso()),
    )
    get_db().commit()
    flash(f"已把 {child['username']} 绑定给 {parent['username']}。", "ok")
    return redirect(url_for("admin_users"))


@app.route("/admin/users/<int:user_id>/children/<int:child_id>/unbind", methods=["POST"])
@admin_required
def admin_unbind_child(user_id: int, child_id: int):
    if not validate_csrf():
        flash("请求已过期，请重试。", "error")
        return redirect(url_for("admin_users"))
    get_db().execute(
        "DELETE FROM parent_children WHERE parent_id = ? AND child_id = ?",
        (user_id, child_id),
    )
    get_db().commit()
    flash("已取消绑定。", "ok")
    return redirect(url_for("admin_users"))


@app.route("/admin/learning")
@admin_required
def admin_learning():
    day = parse_day(request.args.get("date"))
    raw = (request.args.get("user_id") or "").strip()
    detail_id = int(raw) if raw.isdigit() else None
    report = learning_report(day, allowed_ids=None, detail_id=detail_id)
    calendar = month_learning_calendar(None, day)
    return render_template(
        "admin_learning.html",
        day=day,
        calendar=calendar,
        learning_endpoint="admin_learning",
        selected_kind=None,
        kind_label="",
        empty_text="这一天还没有学生的学习记录。",
        **report,
    )


def _parse_word_form(require_meaning: bool = True) -> dict:
    term = (request.form.get("term") or "").strip()
    phonetic = (request.form.get("phonetic") or "").strip()
    pos = (request.form.get("pos") or "").strip()
    tags = [t.strip() for t in request.form.getlist("pos_tag") if (t or "").strip()]
    if tags:
        # 网页多选优先；保持常用顺序
        order = ["n.", "v.", "adj.", "adv.", "prep.", "conj.", "pron.", "art.", "num.", "interj."]
        ordered = [t for t in order if t in tags] + [t for t in tags if t not in order]
        pos = " / ".join(dict.fromkeys(ordered))
    meaning = (request.form.get("meaning") or "").strip()
    phrase = (request.form.get("phrase") or "").strip()
    phrase_zh = (request.form.get("phrase_zh") or "").strip()
    example = (request.form.get("example") or "").strip()
    example_zh = (request.form.get("example_zh") or "").strip()
    notes = (request.form.get("notes") or "").strip()
    data = {
        "term": term,
        "phonetic": phonetic,
        "pos": pos,
        "meaning": meaning,
        "phrase": phrase,
        "phrase_zh": phrase_zh,
        "example": example,
        "example_zh": example_zh,
        "notes": notes,
    }
    if not term:
        data["error"] = "请填写单词。"
    elif len(term) > 80:
        data["error"] = "单词过长。"
    elif len(pos) > 80:
        data["error"] = "词性过长。"
    elif require_meaning and not meaning:
        data["error"] = "请填写释义。"
    elif len(meaning) > 400:
        data["error"] = "释义过长。"
    elif len(phrase) > 200:
        data["error"] = "短语过长。"
    elif len(phrase_zh) > 200:
        data["error"] = "短语翻译过长。"
    elif len(example) > 400:
        data["error"] = "例句过长。"
    elif len(example_zh) > 400:
        data["error"] = "例句翻译过长。"
    return data


# ---------------------------------------------------------------------------
# /api/v1 — Android 原生客户端使用的 JSON API
#
# 认证复用 Flask session cookie（客户端需持久化名为 session 的 cookie）。
# 除登录/注册外的写操作需在请求头携带 X-CSRF-Token；
# 登录、切换账号、me 的响应里会下发当前会话的 csrf_token。
# 响应统一为 {"ok": true, ...} 或 {"ok": false, "error": 代码, "message": 文案}。
# ---------------------------------------------------------------------------


def _notify_words_updated(action: str, word_id: int = 0) -> None:
    from grpc_notify import push_words_updated

    push_words_updated(action, word_id)


def api_error(message: str, status: int = 400, code: str = "error"):
    return jsonify({"ok": False, "error": code, "message": message}), status


def app_apk_path() -> Path:
    return DOWNLOADS_DIR / APP_APK_NAME


def app_release_info() -> dict | None:
    path = app_apk_path()
    if not path.is_file():
        return None
    stat = path.stat()
    return {
        "version_name": APP_VERSION_NAME,
        "version_code": APP_VERSION_CODE,
        "filename": APP_APK_NAME,
        "size_bytes": stat.st_size,
        "updated_at": datetime.fromtimestamp(stat.st_mtime).replace(microsecond=0).isoformat(sep=" "),
        "download_url": url_for("download_apk", _external=True),
    }


@app.context_processor
def inject_app_release():
    return {"app_release": app_release_info()}


@app.get("/download/moci.apk")
def download_apk():
    path = app_apk_path()
    if not path.is_file():
        return api_error("Android 安装包尚未发布。", 404, "not_found")
    return send_from_directory(
        DOWNLOADS_DIR,
        APP_APK_NAME,
        as_attachment=True,
        download_name=APP_APK_NAME,
        mimetype="application/vnd.android.package-archive",
    )


@app.get("/api/v1/app/info")
def api_app_info():
    info = app_release_info()
    if not info:
        return api_error("Android 安装包尚未发布。", 404, "not_found")
    return jsonify({"ok": True, **info})


def _public_user(user) -> dict:
    return {
        "id": user["id"],
        "username": user["username"],
        "role": user["role"],
        "status": user["status"],
        "daily_words": daily_words_of(user),
        "daily_review": daily_review_of(user),
        "know_speak": _user_int(user, "know_speak", 1),
        "know_spell": _user_int(user, "know_spell", 1),
        "know_pos": _user_int(user, "know_pos", 1),
        "know_phonetic": _user_int(user, "know_phonetic", 1),
        "created_at": user["created_at"],
    }


def _word_payload(row) -> dict:
    out = {
        "id": row["id"],
        "term": row["term"],
        "phonetic": row["phonetic"] or "",
        "pos": (row["pos"] or "") if "pos" in row.keys() else "",
        "meaning": row["meaning"] or "",
        "phrase": row["phrase"] or "",
        "phrase_zh": (row["phrase_zh"] or "") if "phrase_zh" in row.keys() else "",
        "example": row["example"] or "",
        "example_zh": (row["example_zh"] or "") if "example_zh" in row.keys() else "",
        "notes": row["notes"] or "",
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }
    try:
        out["status"] = row["status"] or "new"
    except (KeyError, IndexError):
        pass
    return out


def _api_current_user():
    """返回 (user, error_response)；未登录或未审核时 user 为 None。"""
    if not session.get("user_id"):
        return None, api_error("请先登录。", 401, "unauthorized")
    user = current_user()
    if not user:
        session.clear()
        return None, api_error("请先登录。", 401, "unauthorized")
    if not is_approved(user):
        if user["status"] == STATUS_REJECTED:
            return None, api_error("账号未通过审核，请联系管理员。", 403, "not_approved")
        return None, api_error("账号正在等待管理员同意，通过后才能登录。", 403, "not_approved")
    return user, None


def api_required(view=None, *, roles: set[str] | None = None):
    def deco(fn):
        @wraps(fn)
        def wrapped(*args, **kwargs):
            user, err = _api_current_user()
            if err:
                return err
            if roles and user["role"] not in roles:
                return api_error("没有权限执行此操作。", 403, "forbidden")
            return fn(*args, **kwargs)

        return wrapped

    return deco(view) if view is not None else deco


def api_csrf():
    if not validate_csrf():
        return api_error("会话已过期，请重新登录后再试。", 403, "csrf")
    return None


@app.route("/api/v1/auth/register", methods=["POST"])
def api_register():
    if session.get("user_id"):
        return api_error("当前已登录，请先退出后再注册。")
    data = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    password = data.get("password") or ""
    confirm = data.get("confirm") or ""
    chosen_role = (data.get("role") or ROLE_USER).strip()
    if chosen_role not in {ROLE_USER, ROLE_PARENT}:
        chosen_role = ROLE_USER
    if not USERNAME_RE.match(username):
        return api_error("用户名需为 2–20 位字母、数字、下划线或中文。")
    if len(password) < 6:
        return api_error("密码至少 6 位。")
    if password != confirm:
        return api_error("两次输入的密码不一致。")
    db = get_db()
    exists = db.execute(
        "SELECT id FROM users WHERE username = ?", (username,)
    ).fetchone()
    if exists:
        return api_error("该用户名已被占用。")
    user_n = db.execute("SELECT COUNT(*) AS n FROM users").fetchone()["n"]
    role = ROLE_ADMIN if user_n == 0 else chosen_role
    status = STATUS_APPROVED if role == ROLE_ADMIN else STATUS_PENDING
    db.execute(
        """
        INSERT INTO users (username, password_hash, role, status, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        (username, make_password_hash(password), role, status, now_iso()),
    )
    db.commit()
    if role == ROLE_ADMIN:
        user = db.execute(
            "SELECT * FROM users WHERE username = ?", (username,)
        ).fetchone()
        session.clear()
        session["user_id"] = user["id"]
        session["_csrf"] = secrets.token_hex(16)
        return jsonify(
            {
                "ok": True,
                "auto_login": True,
                "user": _public_user(user),
                "csrf_token": session["_csrf"],
                "message": "注册成功。你是第一位用户，已设为管理员。",
            }
        )
    return jsonify(
        {
            "ok": True,
            "auto_login": False,
            "message": "注册已提交，请等待管理员同意后再登录。",
        }
    )


@app.route("/api/v1/auth/login", methods=["POST"])
def api_login():
    data = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    password = data.get("password") or ""
    user = (
        get_db()
        .execute("SELECT * FROM users WHERE username = ?", (username,))
        .fetchone()
    )
    if not user or not check_password_hash(user["password_hash"], password):
        return api_error("用户名或密码不正确。")
    if not is_approved(user):
        if user["status"] == STATUS_REJECTED:
            return api_error("账号未通过审核，请联系管理员。", 403, "not_approved")
        return api_error("账号正在等待管理员同意，通过后才能登录。", 403, "not_approved")
    session.clear()
    session["user_id"] = user["id"]
    session["_csrf"] = secrets.token_hex(16)
    return jsonify(
        {"ok": True, "user": _public_user(user), "csrf_token": session["_csrf"]}
    )


@app.route("/api/v1/auth/logout", methods=["POST"])
def api_logout():
    session.clear()
    return jsonify({"ok": True})


@app.get("/api/v1/auth/me")
def api_me():
    user, err = _api_current_user()
    if err:
        return err
    return jsonify(
        {"ok": True, "user": _public_user(user), "csrf_token": csrf_token()}
    )


@app.get("/api/v1/home")
@api_required
def api_home():
    user = current_user()
    db = get_db()
    if is_admin(user):
        pending = db.execute(
            """
            SELECT id, username, role, created_at FROM users
            WHERE role != ? AND status = ?
            ORDER BY id ASC
            """,
            (ROLE_ADMIN, STATUS_PENDING),
        ).fetchall()
        recent = db.execute(
            "SELECT * FROM words ORDER BY created_at DESC LIMIT 6"
        ).fetchall()
        return jsonify(
            {
                "ok": True,
                "user": _public_user(user),
                "stats": {
                    "total": db.execute("SELECT COUNT(*) AS n FROM words").fetchone()[
                        "n"
                    ]
                },
                "user_count": db.execute(
                    "SELECT COUNT(*) AS n FROM users WHERE role = ?", (ROLE_USER,)
                ).fetchone()["n"],
                "parent_count": db.execute(
                    "SELECT COUNT(*) AS n FROM users WHERE role = ?", (ROLE_PARENT,)
                ).fetchone()["n"],
                "pending_count": len(pending),
                "pending": [dict(r) for r in pending],
                "recent": [_word_payload(r) for r in recent],
            }
        )
    if is_parent(user):
        kids = children_of(user["id"])
        day = parse_day(request.args.get("date"))
        raw = (request.args.get("user_id") or "").strip()
        detail_id = int(raw) if raw.isdigit() else None
        kind = parse_kind(request.args.get("kind")) or KIND_NEW
        allowed = [kid["id"] for kid in kids]
        if allowed and detail_id not in allowed:
            detail_id = allowed[0]
        scope = [detail_id] if detail_id else allowed
        report = learning_report(day, allowed_ids=scope, detail_id=detail_id)
        calendar = month_learning_calendar(
            scope, day, child_count=1 if detail_id else len(allowed)
        )
        children = [
            {
                "user": _public_user(kid),
                "stats": word_stats(kid["id"]),
                "task": today_task(kid["id"], kid),
            }
            for kid in kids
        ]
        return jsonify(
            {
                "ok": True,
                "user": _public_user(user),
                "children": children,
                "day": day,
                "detail_id": detail_id,
                "kind": kind,
                "kind_label": KIND_LABELS.get(kind, ""),
                "calendar": calendar,
                "summary": dict(report["summary"]) if report["summary"] else None,
                "by_user": [dict(r) for r in report["by_user"]],
                "logs": collect_day_words(scope, day, kind),
            }
        )
    calendar = month_study_calendar(user["id"], user)
    return jsonify(
        {
            "ok": True,
            "user": _public_user(user),
            "stats": word_stats(user["id"]),
            "task": today_task(user["id"], user),
            "calendar": calendar,
            "day_words": day_study_words(user["id"], calendar["today"]),
            **know_checks_of(user),
        }
    )


@app.get("/api/v1/review/cards")
@api_required
def api_review_cards():
    user = current_user()
    if not is_learner(user):
        return api_error("当前账号无需背单词。", 403, "forbidden")
    task = today_task(user["id"])
    done_ids = today_reviewed_word_ids(user["id"])
    new_cards = due_cards(
        user["id"],
        limit=task["new"]["remaining"],
        exclude_ids=done_ids,
        kind=KIND_NEW,
    )
    review_cards = due_cards(
        user["id"],
        limit=task["review"]["remaining"],
        exclude_ids=done_ids,
        kind=KIND_REVIEW,
    )
    return jsonify(
        {
            "ok": True,
            "cards": new_cards + review_cards,
            "task": task,
            "stats": word_stats(user["id"]),
            **know_checks_of(user),
        }
    )


@app.route("/api/v1/review/<int:word_id>", methods=["POST"])
@api_required
def api_review_submit(word_id: int):
    err = api_csrf()
    if err:
        return err
    user = current_user()
    if not is_learner(user):
        return api_error("当前账号无需背单词。", 403, "forbidden")
    payload = request.get_json(silent=True) or {}
    rating = payload.get("rating")
    if rating not in {"again", "easy"}:
        return api_error("无效的评价。")
    word = (
        get_db()
        .execute("SELECT id, term, pos, phonetic FROM words WHERE id = ?", (word_id,))
        .fetchone()
    )
    if not word:
        return api_error("找不到这个单词。", 404, "not_found")
    if rating == "easy":
        checks = know_checks_of(user)
        if checks["speak"]:
            spoken = payload.get("spoken")
            if not isinstance(spoken, str) or not spoken_matches(spoken, word["term"]):
                return api_error("请先正确朗读这个单词。", 400, "spoken")
        if checks["spell"]:
            spelling = payload.get("spelling")
            if not isinstance(spelling, str) or not spelling.strip():
                return api_error("拼写不正确，请再试一次。", 400, "spelling")
            if normalize_spelling(spelling) != normalize_spelling(word["term"]):
                return api_error("拼写不正确，请再试一次。", 400, "spelling")
        expected_pos = parse_pos_tags(word["pos"] if "pos" in word.keys() else "")
        if checks["pos"] and expected_pos:
            selected_pos = parse_pos_tags(payload.get("pos_tags"))
            if set(selected_pos) != set(expected_pos):
                return api_error("词性不正确，请再试一次。", 400, "pos")
        expected_phonetic = word["phonetic"] if "phonetic" in word.keys() else ""
        if checks["phonetic"] and normalize_phonetic(expected_phonetic):
            submitted = payload.get("phonetic")
            if not isinstance(submitted, str) or not submitted.strip():
                return api_error("音标不正确，请再试一次。", 400, "phonetic")
            if normalize_phonetic(submitted) != normalize_phonetic(expected_phonetic):
                return api_error("音标不正确，请再试一次。", 400, "phonetic")
    progress = fetch_progress(user["id"], word_id)
    kind = KIND_REVIEW if progress and progress["status"] == "learning" else KIND_NEW
    patch = schedule_review(progress, rating)
    db = get_db()
    db.execute(
        """
        INSERT INTO progress (
            user_id, word_id, status, review_count, correct_streak,
            last_reviewed, next_review, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (user_id, word_id) DO UPDATE SET
            status=excluded.status,
            review_count=excluded.review_count,
            correct_streak=excluded.correct_streak,
            last_reviewed=excluded.last_reviewed,
            next_review=excluded.next_review,
            updated_at=excluded.updated_at
        """,
        (
            user["id"],
            word_id,
            patch["status"],
            patch["review_count"],
            patch["correct_streak"],
            patch["last_reviewed"],
            patch["next_review"],
            patch["updated_at"],
        ),
    )
    db.execute(
        """
        INSERT INTO review_logs (user_id, word_id, rating, kind, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        (user["id"], word_id, rating, kind, patch["updated_at"]),
    )
    db.commit()
    remaining = (
        db.execute(
            """
            SELECT COUNT(*) AS n
            FROM words w
            LEFT JOIN progress p ON p.word_id = w.id AND p.user_id = ?
            WHERE COALESCE(p.status, 'new') = 'new'
              AND (p.next_review IS NULL OR p.next_review <= ?)
            """,
            (user["id"], now_iso()),
        )
        .fetchone()["n"]
    )
    return jsonify({"ok": True, "remaining": remaining, **patch})


@app.get("/api/v1/study-day")
@api_required
def api_study_day_v1():
    day = parse_day(request.args.get("date"))
    return jsonify(
        {"ok": True, "date": day, "words": day_study_words(current_user()["id"], day)}
    )


@app.get("/api/v1/profile")
@api_required
def api_profile():
    user = current_user()
    out: dict = {"ok": True, "user": _public_user(user)}
    if is_learner(user):
        out["stats"] = word_stats(user["id"])
        out["parents"] = [dict(r) for r in parents_of(user["id"])]
    elif is_parent(user):
        out["children"] = [
            {"user": _public_user(kid), "task": today_task(kid["id"], kid)}
            for kid in children_of(user["id"])
        ]
    return jsonify(out)


@app.get("/api/v1/me/words")
@api_required
def api_my_words():
    user = current_user()
    if not is_learner(user):
        return api_error("只有学生可以查看自己的单词进度。", 403, "forbidden")
    status = (request.args.get("status") or "").strip()
    if status not in {"", "new", "learning", "mastered"}:
        return api_error("无效的单词状态。")
    q = (request.args.get("q") or "").strip()
    rows = list_words(user["id"], q=q, status=status)
    return jsonify(
        {
            "ok": True,
            "words": [_word_payload(r) for r in rows],
            "total": len(rows),
            "status": status,
            "q": q,
        }
    )


@app.route("/api/v1/profile/child/<int:child_id>/settings", methods=["POST"])
@api_required
def api_child_settings(child_id: int):
    err = api_csrf()
    if err:
        return err
    user = current_user()
    if not is_parent(user):
        return api_error("只有家长可以设置学习任务。", 403, "forbidden")
    if child_id not in child_ids_of(user["id"]):
        return api_error("只能设置自己的孩子。", 403, "forbidden")
    data = request.get_json(silent=True) or {}
    new_value = clamp_daily_words(data.get("daily_words"), DEFAULT_DAILY_WORDS)
    review_value = clamp_daily_words(data.get("daily_review"), DEFAULT_DAILY_REVIEW)
    know_speak = 1 if data.get("know_speak") else 0
    know_spell = 1 if data.get("know_spell") else 0
    know_pos = 1 if data.get("know_pos", True) else 0
    know_phonetic = 1 if data.get("know_phonetic", True) else 0
    get_db().execute(
        """
        UPDATE users SET daily_words = ?, daily_review = ?,
               know_speak = ?, know_spell = ?, know_pos = ?, know_phonetic = ?
        WHERE id = ? AND role = ?
        """,
        (
            new_value,
            review_value,
            know_speak,
            know_spell,
            know_pos,
            know_phonetic,
            child_id,
            ROLE_USER,
        ),
    )
    get_db().commit()
    child = get_db().execute("SELECT * FROM users WHERE id = ?", (child_id,)).fetchone()
    from grpc_notify import push_settings_updated

    push_settings_updated(child_id, _public_user(child))
    return jsonify(
        {
            "ok": True,
            "message": "已保存学习设置。",
            "user": _public_user(child),
            "task": today_task(child_id, child),
        }
    )


@app.route("/api/v1/switch", methods=["POST"])
@api_required
def api_switch():
    err = api_csrf()
    if err:
        return err
    user = current_user()
    data = request.get_json(silent=True) or {}
    try:
        target_id = int(data.get("target_id") or 0)
    except (TypeError, ValueError):
        target_id = 0
    target = get_db().execute("SELECT * FROM users WHERE id = ?", (target_id,)).fetchone()
    if not target or not is_approved(target):
        return api_error("找不到可切换的账号。")
    if target["id"] == user["id"]:
        return jsonify(
            {"ok": True, "user": _public_user(target), "csrf_token": csrf_token()}
        )

    if is_parent(user) and is_learner(target):
        if target["id"] not in child_ids_of(user["id"]):
            return api_error("只能切换到自己的孩子。")
        _switch_session(target)
        return jsonify(
            {
                "ok": True,
                "user": _public_user(target),
                "csrf_token": csrf_token(),
                "message": f"已切换到 {target['username']}。",
            }
        )

    if is_learner(user) and is_parent(target):
        linked = {row["id"] for row in parents_of(user["id"])}
        if target["id"] not in linked:
            return api_error("只能切换到绑定的家长。")
        password = data.get("password") or ""
        if not check_password_hash(target["password_hash"], password):
            return api_error("家长密码不正确。")
        _switch_session(target)
        return jsonify(
            {
                "ok": True,
                "user": _public_user(target),
                "csrf_token": csrf_token(),
                "message": f"已切换到 {target['username']}。",
            }
        )

    return api_error("不能切换到该账号。")


@app.get("/api/v1/words")
@api_required(roles={ROLE_ADMIN})
def api_words():
    q = (request.args.get("q") or "").strip()
    rows = list_library(q=q)
    total = get_db().execute("SELECT COUNT(*) AS n FROM words").fetchone()["n"]
    return jsonify(
        {"ok": True, "words": [_word_payload(r) for r in rows], "total": total, "q": q}
    )


def _parse_word_json(data: dict, require_meaning: bool = True) -> dict:
    raw_pos = str(data.get("pos") or "").strip()
    if isinstance(data.get("pos_tags"), list):
        tags = [str(t).strip() for t in data["pos_tags"] if str(t).strip()]
        raw_pos = " / ".join(dict.fromkeys(tags)) if tags else raw_pos
    else:
        # 规范化 “n.,v.” / “n.  v.” 为 “n. / v.”
        parts = [
            p.strip()
            for p in re.split(r"[/|,，、;；|]+|\s{2,}", raw_pos)
            if p.strip()
        ]
        raw_pos = " / ".join(dict.fromkeys(parts)) if parts else ""
    parsed = {
        "term": str(data.get("term") or "").strip(),
        "phonetic": str(data.get("phonetic") or "").strip(),
        "pos": raw_pos,
        "meaning": str(data.get("meaning") or "").strip(),
        "phrase": str(data.get("phrase") or "").strip(),
        "phrase_zh": str(data.get("phrase_zh") or "").strip(),
        "example": str(data.get("example") or "").strip(),
        "example_zh": str(data.get("example_zh") or "").strip(),
        "notes": str(data.get("notes") or "").strip(),
    }
    if not parsed["term"]:
        parsed["error"] = "请填写单词。"
    elif len(parsed["term"]) > 80:
        parsed["error"] = "单词过长。"
    elif len(parsed["pos"]) > 80:
        parsed["error"] = "词性过长。"
    elif require_meaning and not parsed["meaning"]:
        parsed["error"] = "请填写释义。"
    elif len(parsed["meaning"]) > 400:
        parsed["error"] = "释义过长。"
    elif len(parsed["phrase"]) > 200:
        parsed["error"] = "短语过长。"
    elif len(parsed["phrase_zh"]) > 200:
        parsed["error"] = "短语翻译过长。"
    elif len(parsed["example"]) > 400:
        parsed["error"] = "例句过长。"
    elif len(parsed["example_zh"]) > 400:
        parsed["error"] = "例句翻译过长。"
    return parsed


@app.route("/api/v1/words", methods=["POST"])
@api_required(roles={ROLE_ADMIN})
def api_word_create():
    err = api_csrf()
    if err:
        return err
    data = _parse_word_json(request.get_json(silent=True) or {})
    if data.get("error"):
        return api_error(data["error"])
    db = get_db()
    dup = db.execute(
        "SELECT id FROM words WHERE lower(term) = lower(?)", (data["term"],)
    ).fetchone()
    if dup:
        return api_error("词库里已有这个单词。")
    ts = now_iso()
    cur = db.execute(
        """
        INSERT INTO words (
            term, phonetic, pos, meaning, phrase, phrase_zh, example, example_zh, notes, created_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """,
        (
            data["term"],
            data["phonetic"],
            data["pos"],
            data["meaning"],
            data["phrase"],
            data["phrase_zh"],
            data["example"],
            data["example_zh"],
            data["notes"],
            current_user()["id"],
            ts,
            ts,
        ),
    )
    new_id = cur.fetchone()["id"]
    db.commit()
    _notify_words_updated("created", new_id)
    word = db.execute("SELECT * FROM words WHERE id = ?", (new_id,)).fetchone()
    return jsonify(
        {
            "ok": True,
            "word": _word_payload(word),
            "message": f"已录入「{data['term']}」，所有用户都可以开始学习。",
        }
    )


@app.get("/api/v1/words/<int:word_id>")
@api_required(roles={ROLE_ADMIN})
def api_word_detail(word_id: int):
    word = get_db().execute("SELECT * FROM words WHERE id = ?", (word_id,)).fetchone()
    if not word:
        return api_error("找不到这个单词。", 404, "not_found")
    return jsonify({"ok": True, "word": _word_payload(word)})


@app.route("/api/v1/words/<int:word_id>", methods=["PUT"])
@api_required(roles={ROLE_ADMIN})
def api_word_update(word_id: int):
    err = api_csrf()
    if err:
        return err
    db = get_db()
    word = db.execute("SELECT * FROM words WHERE id = ?", (word_id,)).fetchone()
    if not word:
        return api_error("找不到这个单词。", 404, "not_found")
    data = _parse_word_json(request.get_json(silent=True) or {})
    if data.get("error"):
        return api_error(data["error"])
    dup = db.execute(
        "SELECT id FROM words WHERE lower(term) = lower(?) AND id != ?",
        (data["term"], word_id),
    ).fetchone()
    if dup:
        return api_error("词库里已有这个单词。")
    db.execute(
        """
        UPDATE words SET term=?, phonetic=?, pos=?, meaning=?, phrase=?, phrase_zh=?, example=?, example_zh=?, notes=?, updated_at=?
        WHERE id=?
        """,
        (
            data["term"],
            data["phonetic"],
            data["pos"],
            data["meaning"],
            data["phrase"],
            data["phrase_zh"],
            data["example"],
            data["example_zh"],
            data["notes"],
            now_iso(),
            word_id,
        ),
    )
    db.commit()
    _notify_words_updated("updated", word_id)
    word = db.execute("SELECT * FROM words WHERE id = ?", (word_id,)).fetchone()
    return jsonify(
        {"ok": True, "word": _word_payload(word), "message": "已保存修改。"}
    )


@app.route("/api/v1/words/<int:word_id>", methods=["DELETE"])
@api_required(roles={ROLE_ADMIN})
def api_word_delete(word_id: int):
    err = api_csrf()
    if err:
        return err
    cur = get_db().execute("DELETE FROM words WHERE id = ?", (word_id,))
    get_db().commit()
    if not cur.rowcount:
        return api_error("找不到这个单词。", 404, "not_found")
    _notify_words_updated("deleted", word_id)
    return jsonify({"ok": True, "message": "已删除。"})


@app.get("/api/v1/admin/users")
@api_required(roles={ROLE_ADMIN})
def api_admin_users():
    db = get_db()
    users = db.execute(
        """
        SELECT id, username, role, status, created_at FROM users
        ORDER BY CASE status WHEN 'pending' THEN 0 WHEN 'approved' THEN 1 ELSE 2 END, id ASC
        """
    ).fetchall()
    links = db.execute(
        """
        SELECT pc.parent_id, u.id, u.username
        FROM parent_children pc
        JOIN users u ON u.id = pc.child_id
        ORDER BY u.username ASC
        """
    ).fetchall()
    children_map: dict[str, list] = {}
    for row in links:
        children_map.setdefault(str(row["parent_id"]), []).append(
            {"id": row["id"], "username": row["username"]}
        )
    return jsonify(
        {
            "ok": True,
            "users": [dict(u) for u in users],
            "admin_count": sum(1 for u in users if u["role"] == ROLE_ADMIN),
            "pending_count": sum(
                1
                for u in users
                if u["role"] != ROLE_ADMIN and u["status"] == STATUS_PENDING
            ),
            "students": [
                dict(u)
                for u in users
                if u["role"] == ROLE_USER and u["status"] == STATUS_APPROVED
            ],
            "children_map": children_map,
        }
    )


@app.route("/api/v1/admin/users/<int:user_id>/status", methods=["POST"])
@api_required(roles={ROLE_ADMIN})
def api_admin_set_status(user_id: int):
    err = api_csrf()
    if err:
        return err
    data = request.get_json(silent=True) or {}
    status = (data.get("status") or "").strip()
    if status not in {STATUS_APPROVED, STATUS_REJECTED, STATUS_PENDING}:
        return api_error("无效的审核状态。")
    target = get_db().execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    if not target:
        return api_error("找不到该用户。", 404, "not_found")
    if target["role"] == ROLE_ADMIN:
        return api_error("不能修改管理员的审核状态。")
    get_db().execute("UPDATE users SET status = ? WHERE id = ?", (status, user_id))
    get_db().commit()
    labels = {
        STATUS_APPROVED: "已同意，可以登录",
        STATUS_REJECTED: "已拒绝",
        STATUS_PENDING: "改回待审核",
    }
    return jsonify({"ok": True, "message": f"{target['username']} {labels[status]}。"})


@app.route("/api/v1/admin/users/<int:user_id>/role", methods=["POST"])
@api_required(roles={ROLE_ADMIN})
def api_admin_set_role(user_id: int):
    err = api_csrf()
    if err:
        return err
    data = request.get_json(silent=True) or {}
    role = (data.get("role") or "").strip()
    if role not in {ROLE_ADMIN, ROLE_USER, ROLE_PARENT}:
        return api_error("无效的角色。")
    db = get_db()
    target = db.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    if not target:
        return api_error("找不到该用户。", 404, "not_found")
    if target["role"] == ROLE_ADMIN and role != ROLE_ADMIN:
        admin_n = db.execute(
            "SELECT COUNT(*) AS n FROM users WHERE role = ?", (ROLE_ADMIN,)
        ).fetchone()["n"]
        if admin_n <= 1:
            return api_error("至少保留一名管理员。")
    extra_sql = ""
    extra_params: list = []
    if role == ROLE_ADMIN:
        extra_sql = ", status = ?"
        extra_params = [STATUS_APPROVED]
    db.execute(
        f"UPDATE users SET role = ?{extra_sql} WHERE id = ?",
        (role, *extra_params, user_id),
    )
    if role != ROLE_PARENT:
        db.execute("DELETE FROM parent_children WHERE parent_id = ?", (user_id,))
    if role != ROLE_USER:
        db.execute("DELETE FROM parent_children WHERE child_id = ?", (user_id,))
    db.commit()
    labels = {ROLE_ADMIN: "管理员", ROLE_USER: "学生", ROLE_PARENT: "家长"}
    return jsonify(
        {"ok": True, "message": f"已将 {target['username']} 设为{labels[role]}。"}
    )


@app.route("/api/v1/admin/users/<int:user_id>/children", methods=["POST"])
@api_required(roles={ROLE_ADMIN})
def api_admin_bind_child(user_id: int):
    err = api_csrf()
    if err:
        return err
    data = request.get_json(silent=True) or {}
    db = get_db()
    parent = db.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    if not parent or parent["role"] != ROLE_PARENT:
        return api_error("只能给学生家长绑定孩子。")
    try:
        child_id = int(data.get("child_id") or 0)
    except (TypeError, ValueError):
        child_id = 0
    child = db.execute("SELECT * FROM users WHERE id = ?", (child_id,)).fetchone()
    if not child or child["role"] != ROLE_USER:
        return api_error("请选择有效的学生账号。")
    if child["status"] != STATUS_APPROVED:
        return api_error("只能绑定已审核通过的学生。")
    db.execute(
        """
        INSERT INTO parent_children (parent_id, child_id, created_at)
        VALUES (?, ?, ?)
        ON CONFLICT (parent_id, child_id) DO NOTHING
        """,
        (user_id, child_id, now_iso()),
    )
    db.commit()
    return jsonify(
        {"ok": True, "message": f"已把 {child['username']} 绑定给 {parent['username']}。"}
    )


@app.route(
    "/api/v1/admin/users/<int:user_id>/children/<int:child_id>", methods=["DELETE"]
)
@api_required(roles={ROLE_ADMIN})
def api_admin_unbind_child(user_id: int, child_id: int):
    err = api_csrf()
    if err:
        return err
    get_db().execute(
        "DELETE FROM parent_children WHERE parent_id = ? AND child_id = ?",
        (user_id, child_id),
    )
    get_db().commit()
    return jsonify({"ok": True, "message": "已取消绑定。"})


@app.get("/api/v1/admin/learning")
@api_required(roles={ROLE_ADMIN})
def api_admin_learning():
    day = parse_day(request.args.get("date"))
    raw = (request.args.get("user_id") or "").strip()
    detail_id = int(raw) if raw.isdigit() else None
    report = learning_report(day, allowed_ids=None, detail_id=detail_id)
    return jsonify(
        {
            "ok": True,
            "day": day,
            "calendar": month_learning_calendar(None, day),
            "summary": dict(report["summary"]) if report["summary"] else None,
            "by_user": [dict(r) for r in report["by_user"]],
            "detail_user": dict(report["detail_user"]) if report["detail_user"] else None,
            "logs": report["logs"],
        }
    )


def create_app() -> Flask:
    with app.app_context():
        init_db()
    from grpc_server import start_grpc_server

    start_grpc_server(app)
    return app


if __name__ == "__main__":
    create_app()
    app.run(
        host="0.0.0.0",
        port=int(os.environ.get("PORT", "5000")),
        debug=True,
        use_reloader=False,
    )
