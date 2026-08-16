#!/usr/bin/env python3
"""导入小学英语词汇（课标二级 + 数词/星期/月份 + 教材常用词）。"""

from __future__ import annotations

import sqlite3
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from data.primary_school_words import WORDS  # noqa: E402

DB_PATH = ROOT / "instance" / "words.db"


def now_iso() -> str:
    return datetime.now().replace(microsecond=0).isoformat(sep=" ")


def main() -> None:
    if not DB_PATH.exists():
        raise SystemExit(f"找不到数据库：{DB_PATH}，请先启动一次应用。")

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")

    admin = conn.execute(
        "SELECT id FROM users WHERE role = 'admin' ORDER BY id ASC LIMIT 1"
    ).fetchone()
    if not admin:
        raise SystemExit("没有管理员账号，请先注册（第一位用户会成为管理员）。")
    admin_id = admin["id"]

    existing = {
        row["term"].lower()
        for row in conn.execute("SELECT term FROM words")
    }

    ts = now_iso()
    inserted = 0
    skipped = 0
    for term, phonetic, meaning, notes in WORDS:
        term = term.strip()
        meaning = meaning.strip()
        if not term or not meaning:
            continue
        if len(term) > 80 or len(meaning) > 400:
            continue
        if term.lower() in existing:
            skipped += 1
            continue
        conn.execute(
            """
            INSERT INTO words (
                term, phonetic, meaning, example, notes, created_by, created_at, updated_at
            ) VALUES (?, ?, ?, '', ?, ?, ?, ?)
            """,
            (term, phonetic.strip(), meaning, notes.strip(), admin_id, ts, ts),
        )
        existing.add(term.lower())
        inserted += 1

    conn.commit()
    total = conn.execute("SELECT COUNT(*) AS n FROM words").fetchone()["n"]
    conn.close()
    print(f"导入完成：新增 {inserted} 个，跳过重复 {skipped} 个，词库现有 {total} 个。")


if __name__ == "__main__":
    main()
