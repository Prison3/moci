#!/usr/bin/env python3
"""为已有短语/例句批量补全中文翻译。"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from data.word_usage import translate_usage, usage_for  # noqa: E402
from db import connect, init_schema  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description="补全短语/例句中文翻译")
    parser.add_argument(
        "--regen-en",
        action="store_true",
        help="同时用生成器刷新英文短语/例句",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="覆盖已有中文翻译（默认只填空）",
    )
    args = parser.parse_args()

    db = connect()
    init_schema(db)
    rows = db.execute(
        """
        SELECT id, term, meaning, notes, phrase, phrase_zh, example, example_zh
        FROM words ORDER BY id
        """
    ).fetchall()

    updated = 0
    for row in rows:
        phrase = row["phrase"] or ""
        example = row["example"] or ""
        if args.regen_en:
            phrase, phrase_zh, example, example_zh = usage_for(
                row["term"], row["meaning"] or "", row["notes"] or ""
            )
        else:
            if not phrase and not example:
                continue
            phrase_zh, example_zh = translate_usage(
                row["term"],
                row["meaning"] or "",
                row["notes"] or "",
                phrase,
                example,
            )
            if not args.all:
                if (row["phrase_zh"] or "").strip():
                    phrase_zh = row["phrase_zh"]
                if (row["example_zh"] or "").strip():
                    example_zh = row["example_zh"]

        if (
            phrase == (row["phrase"] or "")
            and example == (row["example"] or "")
            and phrase_zh == (row["phrase_zh"] or "")
            and example_zh == (row["example_zh"] or "")
        ):
            continue

        db.execute(
            """
            UPDATE words
            SET phrase = ?, phrase_zh = ?, example = ?, example_zh = ?
            WHERE id = ?
            """,
            (phrase, phrase_zh, example, example_zh, row["id"]),
        )
        updated += 1

    db.commit()
    empty_p = db.execute(
        """
        SELECT COUNT(*) AS n FROM words
        WHERE COALESCE(phrase,'') <> '' AND COALESCE(phrase_zh,'') = ''
        """
    ).fetchone()["n"]
    empty_e = db.execute(
        """
        SELECT COUNT(*) AS n FROM words
        WHERE COALESCE(example,'') <> '' AND COALESCE(example_zh,'') = ''
        """
    ).fetchone()["n"]
    filled = db.execute(
        """
        SELECT COUNT(*) AS n FROM words
        WHERE COALESCE(phrase_zh,'') <> '' AND COALESCE(example_zh,'') <> ''
        """
    ).fetchone()["n"]
    db.close()
    print(f"更新 {updated} 条；双语齐全 {filled}；短语缺译 {empty_p}；例句缺译 {empty_e}")


if __name__ == "__main__":
    main()
