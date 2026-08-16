#!/usr/bin/env python3
"""墨词 — 移动优先的记单词系统。"""

from __future__ import annotations

import os
import re
import secrets
import sqlite3
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
    session,
    url_for,
)
from werkzeug.security import check_password_hash, generate_password_hash

BASE_DIR = Path(__file__).resolve().parent
INSTANCE_DIR = BASE_DIR / "instance"
DB_PATH = INSTANCE_DIR / "words.db"

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


def get_db() -> sqlite3.Connection:
    if "db" not in g:
        INSTANCE_DIR.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute("PRAGMA journal_mode = WAL")
        g.db = conn
    return g.db


@app.teardown_appcontext
def close_db(_exc: BaseException | None = None) -> None:
    db = g.pop("db", None)
    if db is not None:
        db.close()


def _columns(db: sqlite3.Connection, table: str) -> set[str]:
    return {row[1] for row in db.execute(f"PRAGMA table_info({table})")}


def _tables(db: sqlite3.Connection) -> set[str]:
    rows = db.execute(
        "SELECT name FROM sqlite_master WHERE type='table'"
    ).fetchall()
    return {row[0] for row in rows}


def init_db() -> None:
    db = get_db()
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            role TEXT NOT NULL DEFAULT 'user',
            status TEXT NOT NULL DEFAULT 'pending',
            daily_words INTEGER NOT NULL DEFAULT 8,
            daily_review INTEGER NOT NULL DEFAULT 8,
            created_at TEXT NOT NULL
        )
        """
    )
    if "role" not in _columns(db, "users"):
        db.execute(
            "ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'user'"
        )
    if "status" not in _columns(db, "users"):
        db.execute(
            "ALTER TABLE users ADD COLUMN status TEXT NOT NULL DEFAULT 'pending'"
        )
        db.execute(
            "UPDATE users SET status = ? WHERE status IS NULL OR status = '' OR status = ?",
            (STATUS_APPROVED, STATUS_PENDING),
        )
        db.execute(
            "UPDATE users SET status = ? WHERE role = ?",
            (STATUS_APPROVED, ROLE_ADMIN),
        )
    if "daily_words" not in _columns(db, "users"):
        db.execute(
            "ALTER TABLE users ADD COLUMN daily_words INTEGER NOT NULL DEFAULT 8"
        )
    if "daily_review" not in _columns(db, "users"):
        db.execute(
            "ALTER TABLE users ADD COLUMN daily_review INTEGER NOT NULL DEFAULT 8"
        )

    if "words" not in _tables(db):
        _create_words_table(db)
    elif "user_id" in _columns(db, "words"):
        _migrate_legacy_words(db)

    db.executescript(
        """
        CREATE TABLE IF NOT EXISTS progress (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            word_id INTEGER NOT NULL,
            status TEXT NOT NULL DEFAULT 'new',
            review_count INTEGER NOT NULL DEFAULT 0,
            correct_streak INTEGER NOT NULL DEFAULT 0,
            last_reviewed TEXT,
            next_review TEXT,
            updated_at TEXT NOT NULL,
            UNIQUE(user_id, word_id),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY (word_id) REFERENCES words(id) ON DELETE CASCADE
        );
        CREATE TABLE IF NOT EXISTS review_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            word_id INTEGER NOT NULL,
            rating TEXT NOT NULL,
            kind TEXT NOT NULL DEFAULT 'new',
            created_at TEXT NOT NULL,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY (word_id) REFERENCES words(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_words_term ON words(term);
        CREATE INDEX IF NOT EXISTS idx_progress_due ON progress(user_id, next_review);
        CREATE INDEX IF NOT EXISTS idx_review_logs_day ON review_logs(user_id, created_at);
        CREATE TABLE IF NOT EXISTS parent_children (
            parent_id INTEGER NOT NULL,
            child_id INTEGER NOT NULL,
            created_at TEXT NOT NULL,
            PRIMARY KEY (parent_id, child_id),
            FOREIGN KEY (parent_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY (child_id) REFERENCES users(id) ON DELETE CASCADE
        );
        """
    )
    if "kind" not in _columns(db, "review_logs"):
        db.execute(
            "ALTER TABLE review_logs ADD COLUMN kind TEXT NOT NULL DEFAULT 'new'"
        )
    if "words" in _tables(db) and "phrase" not in _columns(db, "words"):
        db.execute(
            "ALTER TABLE words ADD COLUMN phrase TEXT NOT NULL DEFAULT ''"
        )

    admin_n = db.execute(
        "SELECT COUNT(*) AS n FROM users WHERE role = ?", (ROLE_ADMIN,)
    ).fetchone()["n"]
    if admin_n == 0:
        first = db.execute("SELECT id FROM users ORDER BY id ASC LIMIT 1").fetchone()
        if first:
            db.execute(
                "UPDATE users SET role = ? WHERE id = ?", (ROLE_ADMIN, first["id"])
            )
    db.commit()


def _create_words_table(db: sqlite3.Connection) -> None:
    db.execute(
        """
        CREATE TABLE words (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            term TEXT NOT NULL,
            phonetic TEXT NOT NULL DEFAULT '',
            meaning TEXT NOT NULL,
            phrase TEXT NOT NULL DEFAULT '',
            example TEXT NOT NULL DEFAULT '',
            notes TEXT NOT NULL DEFAULT '',
            created_by INTEGER,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
        )
        """
    )


def _migrate_legacy_words(db: sqlite3.Connection) -> None:
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS words_new (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            term TEXT NOT NULL,
            phonetic TEXT NOT NULL DEFAULT '',
            meaning TEXT NOT NULL,
            phrase TEXT NOT NULL DEFAULT '',
            example TEXT NOT NULL DEFAULT '',
            notes TEXT NOT NULL DEFAULT '',
            created_by INTEGER,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS progress (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            word_id INTEGER NOT NULL,
            status TEXT NOT NULL DEFAULT 'new',
            review_count INTEGER NOT NULL DEFAULT 0,
            correct_streak INTEGER NOT NULL DEFAULT 0,
            last_reviewed TEXT,
            next_review TEXT,
            updated_at TEXT NOT NULL,
            UNIQUE(user_id, word_id)
        )
        """
    )
    old_words = db.execute("SELECT * FROM words ORDER BY id ASC").fetchall()
    term_map: dict[str, int] = {}
    for w in old_words:
        key = (w["term"] or "").strip().lower()
        if key not in term_map:
            cur = db.execute(
                """
                INSERT INTO words_new (
                    term, phonetic, meaning, phrase, example, notes, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    w["term"],
                    w["phonetic"],
                    w["meaning"],
                    w["phrase"] if "phrase" in w.keys() else "",
                    w["example"],
                    w["notes"],
                    w["user_id"],
                    w["created_at"],
                    w["updated_at"],
                ),
            )
            term_map[key] = cur.lastrowid
        new_id = term_map[key]
        db.execute(
            """
            INSERT OR IGNORE INTO progress (
                user_id, word_id, status, review_count, correct_streak,
                last_reviewed, next_review, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                w["user_id"],
                new_id,
                w["status"] or "new",
                w["review_count"] or 0,
                w["correct_streak"] or 0,
                w["last_reviewed"],
                w["next_review"],
                w["updated_at"] or now_iso(),
            ),
        )
    db.execute("DROP TABLE words")
    db.execute("ALTER TABLE words_new RENAME TO words")


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


def learner_required(view):
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
        if is_admin(user) or is_parent(user):
            flash("当前账号无需背单词。", "ok")
            return redirect(url_for("home"))
        return view(*args, **kwargs)

    return wrapped


def admin_required(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        if not session.get("user_id"):
            next_url = request.path if request.method == "GET" else url_for("home")
            return redirect(url_for("login", next=next_url))
        if not is_admin(current_user()):
            flash("只有管理员可以执行此操作。", "error")
            return redirect(url_for("home"))
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
    }


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


def schedule_review(progress, rating: str) -> dict:
    """新词学会→了解；了解学会→掌握。不认识则回到新词。"""
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
            SELECT w.id, w.term, w.phonetic, w.meaning, w.phrase, w.example, w.notes,
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
            SELECT w.id, w.term, w.phonetic, w.meaning, w.phrase, w.example, w.notes,
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
        sql += " ORDER BY datetime(p.last_reviewed) ASC LIMIT ?"
    else:
        sql += """
            ORDER BY CASE COALESCE(p.status, 'new')
                WHEN 'new' THEN 0 WHEN 'learning' THEN 1 ELSE 2 END,
                datetime(p.next_review) ASC
            LIMIT ?
        """
    params.append(limit)
    rows = get_db().execute(sql, params).fetchall()
    cards = [dict(row) for row in rows]
    for card in cards:
        card["kind"] = kind
    return cards


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
    new_done = len(today_reviewed_word_ids(user_id, KIND_NEW))
    review_done = len(today_reviewed_word_ids(user_id, KIND_REVIEW))
    new_part = _part(new_q, new_done)
    review_part = _part(review_q, review_done)
    return {
        "new": new_part,
        "review": review_part,
        "quota": new_q + review_q,
        "done": new_done + review_done,
        "remaining": new_part["remaining"] + review_part["remaining"],
    }


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
    sql += " ORDER BY datetime(w.updated_at) DESC"
    return get_db().execute(sql, params).fetchall()


def list_library(q: str = ""):
    sql = "SELECT * FROM words WHERE 1 = 1"
    params: list = []
    if q:
        sql += " AND (term LIKE ? OR meaning LIKE ? OR phonetic LIKE ? OR IFNULL(phrase,'') LIKE ? OR example LIKE ?)"
        like = f"%{q}%"
        params.extend([like, like, like, like, like])
    sql += " ORDER BY datetime(updated_at) DESC"
    return get_db().execute(sql, params).fetchall()


def day_bounds(day: str) -> tuple[str, str]:
    return f"{day} 00:00:00", f"{day} 23:59:59"


def parse_day(raw: str | None) -> str:
    today = datetime.now().strftime("%Y-%m-%d")
    value = (raw or "").strip()
    try:
        datetime.strptime(value, "%Y-%m-%d")
        return value
    except ValueError:
        return today


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
        SELECT u.id, u.username, u.status, u.created_at, u.daily_words, u.daily_review
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
                logs = db.execute(
                    """
                    SELECT l.created_at, l.rating, COALESCE(l.kind, 'new') AS kind, w.term, w.meaning
                    FROM review_logs l
                    JOIN words w ON w.id = l.word_id
                    WHERE l.user_id = ? AND l.created_at >= ? AND l.created_at <= ?
                    ORDER BY l.created_at DESC
                    """,
                    (detail_id, start, end),
                ).fetchall()
    return {
        "summary": summary,
        "by_user": by_user,
        "detail_user": detail_user,
        "logs": logs,
    }


def parent_required(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        if not session.get("user_id"):
            next_url = request.path if request.method == "GET" else url_for("home")
            return redirect(url_for("login", next=next_url))
        user = current_user()
        if not user or not is_approved(user):
            if user:
                _kick_unapproved(user)
            return redirect(url_for("login"))
        if not is_parent(user):
            flash("只有家长可以查看孩子的学习情况。", "error")
            return redirect(url_for("home"))
        return view(*args, **kwargs)

    return wrapped


@app.before_request
def ensure_db():
    init_db()


@app.route("/")
@login_required
def home():
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
            "SELECT * FROM words ORDER BY datetime(created_at) DESC LIMIT 6"
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
    if is_parent(user):
        kids = children_of(user["id"])
        day = datetime.now().strftime("%Y-%m-%d")
        child_stats = []
        for kid in kids:
            ws = word_stats(kid["id"])
            task = today_task(kid["id"], kid)
            child_stats.append({"user": kid, "stats": ws, "task": task})
        return render_template(
            "parent_home.html", children=child_stats, day=day
        )
    stats = word_stats(user["id"])
    task = today_task(user["id"])
    return render_template("home.html", stats=stats, task=task)


@app.route("/register", methods=["GET", "POST"])
def register():
    if session.get("user_id"):
        return redirect(url_for("home"))
    if request.method == "POST":
        if not validate_csrf():
            flash("请求已过期，请重试。", "error")
            return redirect(url_for("register"))
        username = (request.form.get("username") or "").strip()
        password = request.form.get("password") or ""
        confirm = request.form.get("confirm") or ""
        chosen_role = (request.form.get("role") or ROLE_USER).strip()
        if chosen_role not in {ROLE_USER, ROLE_PARENT}:
            chosen_role = ROLE_USER
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
                user_n = db.execute("SELECT COUNT(*) AS n FROM users").fetchone()["n"]
                role = ROLE_ADMIN if user_n == 0 else chosen_role
                status = STATUS_APPROVED if role == ROLE_ADMIN else STATUS_PENDING
                db.execute(
                    """
                    INSERT INTO users (username, password_hash, role, status, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (
                        username,
                        generate_password_hash(password),
                        role,
                        status,
                        now_iso(),
                    ),
                )
                db.commit()
                if role == ROLE_ADMIN:
                    user = db.execute(
                        "SELECT * FROM users WHERE username = ?", (username,)
                    ).fetchone()
                    session.clear()
                    session["user_id"] = user["id"]
                    session["_csrf"] = secrets.token_hex(16)
                    flash("注册成功。你是第一位用户，已设为管理员。", "ok")
                    return redirect(url_for("home"))
                if chosen_role == ROLE_PARENT:
                    flash("家长账号已提交，请等待管理员同意后再登录。", "ok")
                else:
                    flash("注册已提交，请等待管理员同意后再登录。", "ok")
                return redirect(url_for("login"))
    return render_template("register.html")


@app.route("/login", methods=["GET", "POST"])
def login():
    if session.get("user_id"):
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
        elif not is_approved(user):
            _kick_unapproved(user)
        else:
            session.clear()
            session["user_id"] = user["id"]
            session["_csrf"] = secrets.token_hex(16)
            nxt = request.args.get("next") or url_for("home")
            if not nxt.startswith("/"):
                nxt = url_for("home")
            return redirect(nxt)
    return render_template("login.html")


@app.route("/logout")
@login_required
def logout():
    session.clear()
    flash("已退出登录。", "ok")
    return redirect(url_for("login"))


@app.route("/words")
@login_required
def words():
    user = current_user()
    if not is_admin(user):
        return redirect(url_for("home"))
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
                term, phonetic, meaning, phrase, example, notes, created_by, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                data["term"],
                data["phonetic"],
                data["meaning"],
                data["phrase"],
                data["example"],
                data["notes"],
                user["id"],
                ts,
                ts,
            ),
        )
        get_db().commit()
        flash(f"已录入「{data['term']}」，所有用户都可以开始学习。", "ok")
        if request.form.get("again") == "1":
            return redirect(url_for("word_new"))
        return redirect(url_for("words"))
    return render_template("word_form.html", word=None, mode="new")


@app.route("/words/<int:word_id>")
@login_required
def word_detail(word_id: int):
    user = current_user()
    if not is_admin(user):
        return redirect(url_for("home"))
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
            UPDATE words SET term=?, phonetic=?, meaning=?, phrase=?, example=?, notes=?, updated_at=?
            WHERE id=?
            """,
            (
                data["term"],
                data["phonetic"],
                data["meaning"],
                data["phrase"],
                data["example"],
                data["notes"],
                now_iso(),
                word_id,
            ),
        )
        get_db().commit()
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
            term, phonetic, meaning, phrase, example, notes, created_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            data["term"],
            data["phonetic"],
            data["meaning"],
            data["phrase"],
            data["example"],
            data["notes"],
            user["id"],
            ts,
            ts,
        ),
    )
    get_db().commit()
    flash(f"已录入「{data['term']}」。", "ok")
    return redirect(url_for("words"))


@app.route("/review")
@learner_required
def review():
    user = current_user()
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
    cards = new_cards + review_cards
    stats = word_stats(user["id"])
    return render_template("review.html", cards=cards, stats=stats, task=task)


@app.route("/api/review/<int:word_id>", methods=["POST"])
@learner_required
def api_review(word_id: int):
    if not validate_csrf():
        return jsonify({"ok": False, "error": "csrf"}), 400
    payload = request.get_json(silent=True) or {}
    rating = payload.get("rating")
    if rating not in {"again", "easy"}:
        return jsonify({"ok": False, "error": "invalid rating"}), 400
    user = current_user()
    word = get_db().execute(
        "SELECT id, term FROM words WHERE id = ?", (word_id,)
    ).fetchone()
    if not word:
        return jsonify({"ok": False, "error": "not found"}), 404
    if rating == "easy":
        spelling = payload.get("spelling")
        if not isinstance(spelling, str) or not spelling.strip():
            return jsonify({"ok": False, "error": "spelling"}), 400
        if normalize_spelling(spelling) != normalize_spelling(word["term"]):
            return jsonify({"ok": False, "error": "spelling"}), 400
    progress = fetch_progress(user["id"], word_id)
    kind = KIND_REVIEW if progress and progress["status"] == "learning" else KIND_NEW
    patch = schedule_review(progress, rating)
    get_db().execute(
        """
        INSERT INTO progress (
            user_id, word_id, status, review_count, correct_streak,
            last_reviewed, next_review, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, word_id) DO UPDATE SET
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
    get_db().execute(
        """
        INSERT INTO review_logs (user_id, word_id, rating, kind, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        (user["id"], word_id, rating, kind, patch["updated_at"]),
    )
    get_db().commit()
    remaining = (
        get_db()
        .execute(
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


@app.route("/me")
@login_required
def profile():
    user = current_user()
    stats = word_stats(user["id"]) if is_learner(user) else None
    kids = children_of(user["id"]) if is_parent(user) else []
    parents = parents_of(user["id"]) if is_learner(user) else []
    return render_template(
        "profile.html", stats=stats, children=kids, parents=parents
    )


@app.route("/me/switch", methods=["POST"])
@login_required
def switch_account():
    if not validate_csrf():
        flash("请求已过期，请重试。", "error")
        return redirect(url_for("profile"))
    user = current_user()
    try:
        target_id = int(request.form.get("target_id") or "0")
    except ValueError:
        target_id = 0
    target = get_db().execute("SELECT * FROM users WHERE id = ?", (target_id,)).fetchone()
    if not target or not is_approved(target):
        flash("找不到可切换的账号。", "error")
        return redirect(url_for("profile"))
    if target["id"] == user["id"]:
        return redirect(url_for("home"))

    if is_parent(user) and is_learner(target):
        if target["id"] not in child_ids_of(user["id"]):
            flash("只能切换到自己的孩子。", "error")
            return redirect(url_for("profile"))
        _switch_session(target)
        flash(f"已切换到 {target['username']}。", "ok")
        return redirect(url_for("home"))

    if is_learner(user) and is_parent(target):
        linked = {row["id"] for row in parents_of(user["id"])}
        if target["id"] not in linked:
            flash("只能切换到绑定的家长。", "error")
            return redirect(url_for("profile"))
        password = request.form.get("password") or ""
        if not check_password_hash(target["password_hash"], password):
            flash("家长密码不正确。", "error")
            return redirect(url_for("profile"))
        _switch_session(target)
        flash(f"已切换到 {target['username']}。", "ok")
        return redirect(url_for("home"))

    flash("不能切换到该账号。", "error")
    return redirect(url_for("profile"))


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
        INSERT OR IGNORE INTO parent_children (parent_id, child_id, created_at)
        VALUES (?, ?, ?)
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
    return render_template(
        "admin_learning.html",
        day=day,
        learning_endpoint="admin_learning",
        empty_text="这一天还没有学生的学习记录。",
        **report,
    )


@app.route("/learning")
@parent_required
def parent_learning():
    user = current_user()
    day = parse_day(request.args.get("date"))
    raw = (request.args.get("user_id") or "").strip()
    detail_id = int(raw) if raw.isdigit() else None
    allowed = child_ids_of(user["id"])
    report = learning_report(day, allowed_ids=allowed, detail_id=detail_id)
    return render_template(
        "admin_learning.html",
        day=day,
        learning_endpoint="parent_learning",
        empty_text="这一天孩子们还没有学习记录。",
        **report,
    )


@app.route("/tasks", methods=["GET", "POST"])
@parent_required
def parent_tasks():
    user = current_user()
    allowed = set(child_ids_of(user["id"]))
    if request.method == "POST":
        if not validate_csrf():
            flash("请求已过期，请重试。", "error")
            return redirect(url_for("parent_tasks"))
        db = get_db()
        saved = 0
        for child_id in allowed:
            raw_new = request.form.get(f"daily_words_{child_id}")
            raw_review = request.form.get(f"daily_review_{child_id}")
            if (raw_new is None or str(raw_new).strip() == "") and (
                raw_review is None or str(raw_review).strip() == ""
            ):
                continue
            new_value = clamp_daily_words(raw_new, DEFAULT_DAILY_WORDS)
            review_value = clamp_daily_words(raw_review, DEFAULT_DAILY_REVIEW)
            db.execute(
                """
                UPDATE users SET daily_words = ?, daily_review = ?
                WHERE id = ? AND role = ?
                """,
                (new_value, review_value, child_id, ROLE_USER),
            )
            saved += 1
        db.commit()
        if saved:
            flash("已保存每日学习任务。", "ok")
        else:
            flash("还没有可设置的孩子。", "error")
        return redirect(url_for("parent_tasks"))
    items = []
    for kid in children_of(user["id"]):
        items.append(
            {
                "user": kid,
                "task": today_task(kid["id"], kid),
            }
        )
    return render_template("parent_tasks.html", children=items)


def _parse_word_form(require_meaning: bool = True) -> dict:
    term = (request.form.get("term") or "").strip()
    phonetic = (request.form.get("phonetic") or "").strip()
    meaning = (request.form.get("meaning") or "").strip()
    phrase = (request.form.get("phrase") or "").strip()
    example = (request.form.get("example") or "").strip()
    notes = (request.form.get("notes") or "").strip()
    data = {
        "term": term,
        "phonetic": phonetic,
        "meaning": meaning,
        "phrase": phrase,
        "example": example,
        "notes": notes,
    }
    if not term:
        data["error"] = "请填写单词。"
    elif len(term) > 80:
        data["error"] = "单词过长。"
    elif require_meaning and not meaning:
        data["error"] = "请填写释义。"
    elif len(meaning) > 400:
        data["error"] = "释义过长。"
    elif len(phrase) > 200:
        data["error"] = "短语过长。"
    elif len(example) > 400:
        data["error"] = "例句过长。"
    return data


def create_app() -> Flask:
    with app.app_context():
        init_db()
    return app


if __name__ == "__main__":
    create_app()
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "5000")), debug=True)
