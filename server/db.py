"""SQLite 连接与表结构。"""

from __future__ import annotations

import os
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Any, Sequence

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_DB_PATH = BASE_DIR / "instance" / "words.db"


def database_path() -> Path:
    custom = (os.environ.get("DATABASE_PATH") or "").strip()
    if custom:
        return Path(custom)
    url = (os.environ.get("DATABASE_URL") or "").strip()
    if url.startswith("sqlite:///"):
        return Path(url[len("sqlite:///") :])
    return DEFAULT_DB_PATH


class Database:
    def __init__(self, conn: sqlite3.Connection):
        self._conn = conn

    def execute(self, sql: str, params: Sequence[Any] | dict | None = None):
        if params is None:
            return self._conn.execute(sql)
        if isinstance(params, dict):
            return self._conn.execute(sql, params)
        return self._conn.execute(sql, tuple(params))

    def executescript(self, script: str) -> None:
        self._conn.executescript(script)

    def commit(self) -> None:
        self._conn.commit()

    def rollback(self) -> None:
        self._conn.rollback()

    def close(self) -> None:
        self._conn.close()


def connect() -> Database:
    path = database_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    return Database(conn)


def _tables(db: Database) -> set[str]:
    rows = db.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
    ).fetchall()
    return {row["name"] for row in rows}


def _columns(db: Database, table: str) -> set[str]:
    return {row["name"] for row in db.execute(f"PRAGMA table_info({table})")}


def _add_column(db: Database, table: str, column: str, ddl: str) -> None:
    if column not in _columns(db, table):
        db.execute(f"ALTER TABLE {table} ADD COLUMN {ddl}")


def _now_iso() -> str:
    return datetime.now().replace(microsecond=0).isoformat(sep=" ")


def _create_words_table(db: Database) -> None:
    db.execute(
        """
        CREATE TABLE words (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            term TEXT NOT NULL,
            phonetic TEXT NOT NULL DEFAULT '',
            pos TEXT NOT NULL DEFAULT '',
            meaning TEXT NOT NULL,
            phrase TEXT NOT NULL DEFAULT '',
            phrase_zh TEXT NOT NULL DEFAULT '',
            example TEXT NOT NULL DEFAULT '',
            example_zh TEXT NOT NULL DEFAULT '',
            notes TEXT NOT NULL DEFAULT '',
            level TEXT NOT NULL DEFAULT 'primary',
            created_by INTEGER,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
        )
        """
    )


def _migrate_legacy_words(db: Database) -> None:
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS words_new (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            term TEXT NOT NULL,
            phonetic TEXT NOT NULL DEFAULT '',
            pos TEXT NOT NULL DEFAULT '',
            meaning TEXT NOT NULL,
            phrase TEXT NOT NULL DEFAULT '',
            phrase_zh TEXT NOT NULL DEFAULT '',
            example TEXT NOT NULL DEFAULT '',
            example_zh TEXT NOT NULL DEFAULT '',
            notes TEXT NOT NULL DEFAULT '',
            level TEXT NOT NULL DEFAULT 'primary',
            created_by INTEGER,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
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
                    term, phonetic, pos, meaning, phrase, phrase_zh, example, example_zh,
                    notes, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    w["term"],
                    w["phonetic"],
                    w["pos"] if "pos" in w.keys() else "",
                    w["meaning"],
                    w["phrase"] if "phrase" in w.keys() else "",
                    w["phrase_zh"] if "phrase_zh" in w.keys() else "",
                    w["example"],
                    w["example_zh"] if "example_zh" in w.keys() else "",
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
                w["updated_at"] or _now_iso(),
            ),
        )
    db.execute("DROP TABLE words")
    db.execute("ALTER TABLE words_new RENAME TO words")


def init_schema(db: Database) -> None:
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
            know_speak INTEGER NOT NULL DEFAULT 1,
            know_spell INTEGER NOT NULL DEFAULT 1,
            know_pos INTEGER NOT NULL DEFAULT 1,
            know_phonetic INTEGER NOT NULL DEFAULT 1,
            reward_minutes INTEGER NOT NULL DEFAULT 30,
            created_at TEXT NOT NULL
        )
        """
    )
    _add_column(db, "users", "role", "role TEXT NOT NULL DEFAULT 'user'")
    if "status" not in _columns(db, "users"):
        db.execute(
            "ALTER TABLE users ADD COLUMN status TEXT NOT NULL DEFAULT 'pending'"
        )
        db.execute(
            "UPDATE users SET status = ? WHERE status IS NULL OR status = '' OR status = ?",
            ("approved", "pending"),
        )
        db.execute(
            "UPDATE users SET status = ? WHERE role = ?",
            ("approved", "admin"),
        )
    _add_column(db, "users", "daily_words", "daily_words INTEGER NOT NULL DEFAULT 8")
    _add_column(db, "users", "daily_review", "daily_review INTEGER NOT NULL DEFAULT 8")
    _add_column(db, "users", "know_speak", "know_speak INTEGER NOT NULL DEFAULT 1")
    _add_column(db, "users", "know_spell", "know_spell INTEGER NOT NULL DEFAULT 1")
    _add_column(db, "users", "know_pos", "know_pos INTEGER NOT NULL DEFAULT 1")
    _add_column(db, "users", "know_phonetic", "know_phonetic INTEGER NOT NULL DEFAULT 1")
    _add_column(
        db, "users", "reward_minutes", "reward_minutes INTEGER NOT NULL DEFAULT 30"
    )
    _add_column(
        db,
        "users",
        "word_levels",
        "word_levels TEXT NOT NULL DEFAULT 'primary,junior,senior'",
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
            UNIQUE (user_id, word_id),
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
        CREATE TABLE IF NOT EXISTS parent_children (
            parent_id INTEGER NOT NULL,
            child_id INTEGER NOT NULL,
            created_at TEXT NOT NULL,
            PRIMARY KEY (parent_id, child_id),
            FOREIGN KEY (parent_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY (child_id) REFERENCES users(id) ON DELETE CASCADE
        );
        CREATE TABLE IF NOT EXISTS daily_results (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            study_date TEXT NOT NULL,
            new_quota INTEGER NOT NULL DEFAULT 0,
            review_quota INTEGER NOT NULL DEFAULT 0,
            new_done INTEGER NOT NULL DEFAULT 0,
            review_done INTEGER NOT NULL DEFAULT 0,
            new_n INTEGER NOT NULL DEFAULT 0,
            review_n INTEGER NOT NULL DEFAULT 0,
            easy_n INTEGER NOT NULL DEFAULT 0,
            again_n INTEGER NOT NULL DEFAULT 0,
            reviews INTEGER NOT NULL DEFAULT 0,
            completed INTEGER NOT NULL DEFAULT 0,
            first_at TEXT,
            last_at TEXT,
            updated_at TEXT NOT NULL,
            UNIQUE (user_id, study_date),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_words_term ON words (term);
        CREATE INDEX IF NOT EXISTS idx_progress_due ON progress (user_id, next_review);
        CREATE INDEX IF NOT EXISTS idx_review_logs_day ON review_logs (user_id, created_at);
        CREATE INDEX IF NOT EXISTS idx_daily_results_date ON daily_results (study_date);
        CREATE TABLE IF NOT EXISTS game_scores (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            game TEXT NOT NULL,
            score INTEGER NOT NULL,
            played_at TEXT NOT NULL,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_game_scores_game_user ON game_scores (game, user_id);
        """
    )
    _add_column(db, "review_logs", "kind", "kind TEXT NOT NULL DEFAULT 'new'")
    _add_column(db, "words", "phrase", "phrase TEXT NOT NULL DEFAULT ''")
    _add_column(db, "words", "phrase_zh", "phrase_zh TEXT NOT NULL DEFAULT ''")
    _add_column(db, "words", "example_zh", "example_zh TEXT NOT NULL DEFAULT ''")
    _add_column(db, "words", "pos", "pos TEXT NOT NULL DEFAULT ''")
    if "level" not in _columns(db, "words"):
        db.execute(
            "ALTER TABLE words ADD COLUMN level TEXT NOT NULL DEFAULT 'primary'"
        )
        db.execute(
            """
            UPDATE words SET level = 'junior'
            WHERE level = 'primary' AND notes LIKE '初中%'
            """
        )
        db.execute(
            """
            UPDATE words SET level = 'senior'
            WHERE level = 'primary' AND notes LIKE '高中%'
            """
        )
        db.execute(
            """
            UPDATE words SET level = 'college'
            WHERE level = 'primary' AND notes LIKE '大学%'
            """
        )
    else:
        # 兜底：若曾加列但未回填，按 notes 再刷一次空/默认值
        db.execute(
            """
            UPDATE words SET level = 'junior'
            WHERE (level IS NULL OR level = '' OR level = 'primary')
              AND notes LIKE '初中%'
            """
        )
        db.execute(
            """
            UPDATE words SET level = 'senior'
            WHERE (level IS NULL OR level = '' OR level = 'primary')
              AND notes LIKE '高中%'
            """
        )
        db.execute(
            """
            UPDATE words SET level = 'college'
            WHERE (level IS NULL OR level = '' OR level = 'primary')
              AND notes LIKE '大学%'
            """
        )
        db.execute(
            """
            UPDATE words SET level = 'primary'
            WHERE level IS NULL OR level = ''
            """
        )
    db.execute("CREATE INDEX IF NOT EXISTS idx_words_level ON words (level)")

    admin_n = db.execute(
        "SELECT COUNT(*) AS n FROM users WHERE role = ?", ("admin",)
    ).fetchone()["n"]
    if admin_n == 0:
        first = db.execute(
            "SELECT id FROM users ORDER BY id ASC LIMIT 1"
        ).fetchone()
        if first:
            db.execute(
                "UPDATE users SET role = ? WHERE id = ?",
                ("admin", first["id"]),
            )
    db.commit()
