#!/usr/bin/env python3
"""导入小学英语词汇（课标二级 + 数词/星期/月份 + 教材常用词），并补全短语与例句。"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from data.primary_school_words import WORDS  # noqa: E402
from data.word_usage import usage_for  # noqa: E402

DB_PATH = ROOT / "instance" / "words.db"


def now_iso() -> str:
    return datetime.now().replace(microsecond=0).isoformat(sep=" ")


def ensure_phrase_column(conn: sqlite3.Connection) -> None:
    cols = {row[1] for row in conn.execute("PRAGMA table_info(words)")}
    if "phrase" not in cols:
        conn.execute("ALTER TABLE words ADD COLUMN phrase TEXT NOT NULL DEFAULT ''")


def main() -> None:
    parser = argparse.ArgumentParser(description="导入小学词汇并补全短语、例句")
    parser.add_argument(
        "--refresh",
        action="store_true",
        help="覆盖已有短语和例句（用生成结果重新填写）",
    )
    args = parser.parse_args()
    refresh = args.refresh

    if not DB_PATH.exists():
        raise SystemExit(f"找不到数据库：{DB_PATH}，请先启动一次应用。")

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    ensure_phrase_column(conn)

    admin = conn.execute(
        "SELECT id FROM users WHERE role = 'admin' ORDER BY id ASC LIMIT 1"
    ).fetchone()
    if not admin:
        raise SystemExit("没有管理员账号，请先注册（第一位用户会成为管理员）。")
    admin_id = admin["id"]

    existing = {
        row["term"].lower(): row
        for row in conn.execute(
            "SELECT id, term, meaning, notes, phrase, example FROM words"
        )
    }

    ts = now_iso()
    inserted = 0
    skipped = 0
    filled = 0
    for term, phonetic, meaning, notes in WORDS:
        term = term.strip()
        meaning = meaning.strip()
        notes = notes.strip()
        if not term or not meaning:
            continue
        if len(term) > 80 or len(meaning) > 400:
            continue
        phrase, example = usage_for(term, meaning, notes)
        key = term.lower()
        if key in existing:
            skipped += 1
            row = existing[key]
            if refresh:
                new_phrase, new_example = phrase, example
            else:
                new_phrase = (row["phrase"] or "").strip() or phrase
                new_example = (row["example"] or "").strip() or example
            if new_phrase != (row["phrase"] or "") or new_example != (row["example"] or ""):
                conn.execute(
                    """
                    UPDATE words
                    SET phrase = ?, example = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (new_phrase, new_example, ts, row["id"]),
                )
                filled += 1
            continue
        conn.execute(
            """
            INSERT INTO words (
                term, phonetic, meaning, phrase, example, notes, created_by, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                term,
                phonetic.strip(),
                meaning,
                phrase,
                example,
                notes,
                admin_id,
                ts,
                ts,
            ),
        )
        existing[key] = {
            "id": conn.execute("SELECT last_insert_rowid() AS id").fetchone()["id"],
            "term": term,
            "meaning": meaning,
            "notes": notes,
            "phrase": phrase,
            "example": example,
        }
        inserted += 1

    extra = conn.execute(
        """
        SELECT id, term, meaning, notes, phrase, example
        FROM words
        WHERE IFNULL(phrase, '') = '' OR IFNULL(example, '') = ''
        """
    ).fetchall()
    for row in extra:
        phrase, example = usage_for(row["term"], row["meaning"] or "", row["notes"] or "")
        new_phrase = (row["phrase"] or "").strip() or phrase
        new_example = (row["example"] or "").strip() or example
        if new_phrase != (row["phrase"] or "") or new_example != (row["example"] or ""):
            conn.execute(
                """
                UPDATE words
                SET phrase = ?, example = ?, updated_at = ?
                WHERE id = ?
                """,
                (new_phrase, new_example, ts, row["id"]),
            )
            filled += 1

    conn.commit()
    total = conn.execute("SELECT COUNT(*) AS n FROM words").fetchone()["n"]
    empty = conn.execute(
        """
        SELECT COUNT(*) AS n FROM words
        WHERE IFNULL(phrase, '') = '' OR IFNULL(example, '') = ''
        """
    ).fetchone()["n"]
    conn.close()
    print(
        f"导入完成：新增 {inserted} 个，跳过重复 {skipped} 个，"
        f"补全短语/例句 {filled} 条，词库现有 {total} 个，仍缺 {empty} 条。"
    )


if __name__ == "__main__":
    main()
