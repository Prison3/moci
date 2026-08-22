#!/usr/bin/env python3
"""导入小学英语词汇（课标二级 + 上海牛津补充词），并补全短语与例句。"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from data.primary_school_words import WORDS  # noqa: E402
from data.shanghai_oxford_extra import EXTRA_WORDS  # noqa: E402
from data.word_usage import usage_for  # noqa: E402
from db import connect, init_schema  # noqa: E402
from scripts.fill_pos import infer_pos  # noqa: E402


def now_iso() -> str:
    return datetime.now().replace(microsecond=0).isoformat(sep=" ")


def main() -> None:
    parser = argparse.ArgumentParser(description="导入小学词汇并补全短语、例句")
    parser.add_argument(
        "--refresh",
        action="store_true",
        help="覆盖已有短语和例句（用生成结果重新填写）",
    )
    args = parser.parse_args()
    refresh = args.refresh

    conn = connect()
    init_schema(conn)

    admin = conn.execute(
        "SELECT id FROM users WHERE role = 'admin' ORDER BY id ASC LIMIT 1"
    ).fetchone()
    if not admin:
        raise SystemExit("没有管理员账号，请先注册（第一位用户会成为管理员）。")
    admin_id = admin["id"]

    existing = {
        row["term"].lower(): row
        for row in conn.execute(
            """
            SELECT id, term, meaning, notes, phrase, phrase_zh, example, example_zh, pos
            FROM words
            """
        )
    }

    ts = now_iso()
    inserted = 0
    skipped = 0
    filled = 0
    for term, phonetic, meaning, notes in list(WORDS) + list(EXTRA_WORDS):
        term = term.strip()
        meaning = meaning.strip()
        notes = notes.strip()
        if not term or not meaning:
            continue
        if len(term) > 80 or len(meaning) > 400:
            continue
        phrase, phrase_zh, example, example_zh = usage_for(term, meaning, notes)
        pos = infer_pos(term, meaning, notes)
        key = term.lower()
        if key in existing:
            skipped += 1
            row = existing[key]
            if refresh:
                new_phrase, new_phrase_zh = phrase, phrase_zh
                new_example, new_example_zh = example, example_zh
            else:
                new_phrase = (row["phrase"] or "").strip() or phrase
                new_example = (row["example"] or "").strip() or example
                new_phrase_zh = (row["phrase_zh"] or "").strip() or phrase_zh
                new_example_zh = (row["example_zh"] or "").strip() or example_zh
            if (
                new_phrase != (row["phrase"] or "")
                or new_example != (row["example"] or "")
                or new_phrase_zh != (row["phrase_zh"] or "")
                or new_example_zh != (row["example_zh"] or "")
            ):
                conn.execute(
                    """
                    UPDATE words
                    SET phrase = ?, phrase_zh = ?, example = ?, example_zh = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (
                        new_phrase,
                        new_phrase_zh,
                        new_example,
                        new_example_zh,
                        ts,
                        row["id"],
                    ),
                )
                filled += 1
            if not (row.get("pos") or "").strip():
                conn.execute(
                    "UPDATE words SET pos = ?, updated_at = ? WHERE id = ?",
                    (pos, ts, row["id"]),
                )
            continue
        new_row = conn.execute(
            """
            INSERT INTO words (
                term, phonetic, pos, meaning, phrase, phrase_zh, example, example_zh,
                notes, created_by, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """,
            (
                term,
                phonetic.strip(),
                pos,
                meaning,
                phrase,
                phrase_zh,
                example,
                example_zh,
                notes,
                admin_id,
                ts,
                ts,
            ),
        ).fetchone()
        existing[key] = {
            "id": new_row["id"],
            "term": term,
            "meaning": meaning,
            "notes": notes,
            "phrase": phrase,
            "phrase_zh": phrase_zh,
            "example": example,
            "example_zh": example_zh,
            "pos": pos,
        }
        inserted += 1

    extra = conn.execute(
        """
        SELECT id, term, meaning, notes, phrase, phrase_zh, example, example_zh
        FROM words
        WHERE COALESCE(phrase, '') = '' OR COALESCE(example, '') = ''
           OR COALESCE(phrase_zh, '') = '' OR COALESCE(example_zh, '') = ''
        """
    ).fetchall()
    for row in extra:
        phrase, phrase_zh, example, example_zh = usage_for(
            row["term"], row["meaning"] or "", row["notes"] or ""
        )
        new_phrase = (row["phrase"] or "").strip() or phrase
        new_example = (row["example"] or "").strip() or example
        new_phrase_zh = (row["phrase_zh"] or "").strip() or phrase_zh
        new_example_zh = (row["example_zh"] or "").strip() or example_zh
        if (
            new_phrase != (row["phrase"] or "")
            or new_example != (row["example"] or "")
            or new_phrase_zh != (row["phrase_zh"] or "")
            or new_example_zh != (row["example_zh"] or "")
        ):
            conn.execute(
                """
                UPDATE words
                SET phrase = ?, phrase_zh = ?, example = ?, example_zh = ?, updated_at = ?
                WHERE id = ?
                """,
                (
                    new_phrase,
                    new_phrase_zh,
                    new_example,
                    new_example_zh,
                    ts,
                    row["id"],
                ),
            )
            filled += 1

    conn.commit()
    total = conn.execute("SELECT COUNT(*) AS n FROM words").fetchone()["n"]
    empty = conn.execute(
        """
        SELECT COUNT(*) AS n FROM words
        WHERE COALESCE(phrase, '') = '' OR COALESCE(example, '') = ''
           OR COALESCE(phrase_zh, '') = '' OR COALESCE(example_zh, '') = ''
        """
    ).fetchone()["n"]
    conn.close()
    print(
        f"导入完成：新增 {inserted} 个，跳过重复 {skipped} 个，"
        f"补全短语/例句 {filled} 条，词库现有 {total} 个，仍缺 {empty} 条。"
    )


if __name__ == "__main__":
    main()
