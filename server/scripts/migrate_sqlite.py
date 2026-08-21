#!/usr/bin/env python3
"""把 instance/words.db 的数据拷到 PostgreSQL。目标库已有用户时默认跳过。"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from db import connect, init_schema, reset_id_sequence  # noqa: E402

SQLITE_PATH = ROOT / "instance" / "words.db"
TABLES = ("users", "words", "progress", "review_logs", "parent_children", "daily_results")


def sqlite_columns(conn: sqlite3.Connection, table: str) -> list[str]:
    return [row[1] for row in conn.execute(f"PRAGMA table_info({table})")]


def sqlite_tables(conn: sqlite3.Connection) -> set[str]:
    return {
        row[0]
        for row in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="从 SQLite 迁到 PostgreSQL")
    parser.add_argument("--force", action="store_true", help="即使目标库已有用户也覆盖导入")
    parser.add_argument("--sqlite", default=str(SQLITE_PATH), help="SQLite 文件路径")
    args = parser.parse_args()

    src_path = Path(args.sqlite)
    if not src_path.exists():
        raise SystemExit(f"找不到 SQLite 文件：{src_path}")

    pg = connect()
    init_schema(pg)
    existing = pg.execute("SELECT COUNT(*) AS n FROM users").fetchone()["n"]
    if existing and not args.force:
        pg.close()
        raise SystemExit(
            f"PostgreSQL 里已有 {existing} 个用户，未覆盖。"
            "如需强制导入请加 --force。"
        )

    src = sqlite3.connect(src_path)
    src.row_factory = sqlite3.Row
    present = sqlite_tables(src)

    if args.force:
        for table in reversed(TABLES):
            pg.execute(f"DELETE FROM {table}")

    for table in TABLES:
        if table not in present:
            continue
        cols = sqlite_columns(src, table)
        if not cols:
            continue
        pg_cols = {
            row["column_name"]
            for row in pg.execute(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """,
                (table,),
            )
        }
        use_cols = [c for c in cols if c in pg_cols]
        if not use_cols:
            continue
        col_sql = ", ".join(use_cols)
        placeholders = ", ".join("?" * len(use_cols))
        rows = src.execute(f"SELECT {col_sql} FROM {table}").fetchall()
        for row in rows:
            values = [row[c] for c in use_cols]
            pg.execute(
                f"INSERT INTO {table} ({col_sql}) VALUES ({placeholders})",
                values,
            )
        if "id" in use_cols:
            reset_id_sequence(pg, table)
        print(f"{table}: 导入 {len(rows)} 行")

    pg.commit()
    src.close()
    pg.close()
    print("迁移完成。")


if __name__ == "__main__":
    main()
