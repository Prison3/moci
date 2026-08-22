#!/usr/bin/env python3
"""为 words.pos 批量补全词性。一词可有多个词性，存成「n. / v.」。"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from nltk.corpus import wordnet as wn  # noqa: E402

from db import connect, init_schema  # noqa: E402

# 与表单 / App 一致的顺序
POS_ORDER = [
    "n.",
    "v.",
    "adj.",
    "adv.",
    "prep.",
    "conj.",
    "pron.",
    "art.",
    "num.",
    "interj.",
]

WN_MAP = {
    "n": "n.",
    "v": "v.",
    "a": "adj.",
    "s": "adj.",
    "r": "adv.",
}


def join_pos(tags: set[str] | list[str]) -> str:
    uniq = {t.strip() for t in tags if t and t.strip()}
    return " / ".join(t for t in POS_ORDER if t in uniq)


# —— 封闭词类与小学高频多词性（手工优先）——
CURATED: dict[str, set[str]] = {
    # 冠词
    "a": {"art."},
    "an": {"art."},
    "the": {"art."},
    # 代词
    "i": {"pron."},
    "me": {"pron."},
    "my": {"pron."},
    "mine": {"pron."},
    "you": {"pron."},
    "your": {"pron."},
    "yours": {"pron."},
    "he": {"pron."},
    "him": {"pron."},
    "his": {"pron."},
    "she": {"pron."},
    "her": {"pron."},
    "hers": {"pron."},
    "it": {"pron."},
    "its": {"pron."},
    "we": {"pron."},
    "us": {"pron."},
    "our": {"pron."},
    "ours": {"pron."},
    "they": {"pron."},
    "them": {"pron."},
    "their": {"pron."},
    "theirs": {"pron."},
    "this": {"pron.", "adj."},
    "that": {"pron.", "adj.", "conj."},
    "these": {"pron.", "adj."},
    "those": {"pron.", "adj."},
    "who": {"pron."},
    "whom": {"pron."},
    "whose": {"pron."},
    "which": {"pron.", "adj."},
    "what": {"pron.", "adj."},
    "where": {"adv.", "conj."},
    "when": {"adv.", "conj."},
    "why": {"adv."},
    "how": {"adv."},
    "someone": {"pron."},
    "somebody": {"pron."},
    "something": {"pron."},
    "anyone": {"pron."},
    "anybody": {"pron."},
    "anything": {"pron."},
    "everyone": {"pron."},
    "everybody": {"pron."},
    "everything": {"pron."},
    "no one": {"pron."},
    "nobody": {"pron."},
    "nothing": {"pron."},
    "myself": {"pron."},
    "yourself": {"pron."},
    "himself": {"pron."},
    "herself": {"pron."},
    "itself": {"pron."},
    "ourselves": {"pron."},
    "yourselves": {"pron."},
    "themselves": {"pron."},
    "one another": {"pron."},
    "each other": {"pron."},
    # 介词
    "in": {"prep.", "adv."},
    "on": {"prep.", "adv."},
    "at": {"prep."},
    "to": {"prep."},
    "for": {"prep."},
    "of": {"prep."},
    "from": {"prep."},
    "with": {"prep."},
    "without": {"prep."},
    "about": {"prep.", "adv."},
    "into": {"prep."},
    "onto": {"prep."},
    "over": {"prep.", "adv."},
    "under": {"prep.", "adv."},
    "above": {"prep.", "adv."},
    "below": {"prep.", "adv."},
    "between": {"prep."},
    "among": {"prep."},
    "beside": {"prep."},
    "behind": {"prep.", "adv."},
    "before": {"prep.", "adv.", "conj."},
    "after": {"prep.", "adv.", "conj."},
    "across": {"prep.", "adv."},
    "along": {"prep.", "adv."},
    "around": {"prep.", "adv."},
    "through": {"prep.", "adv."},
    "during": {"prep."},
    "against": {"prep."},
    "near": {"prep.", "adj.", "adv."},
    "by": {"prep.", "adv."},
    "up": {"prep.", "adv."},
    "down": {"prep.", "adv."},
    "off": {"prep.", "adv."},
    "out": {"prep.", "adv."},
    "inside": {"prep.", "adv.", "n."},
    "outside": {"prep.", "adv.", "n.", "adj."},
    "past": {"prep.", "adj.", "n."},
    "since": {"prep.", "conj.", "adv."},
    "till": {"prep.", "conj."},
    "until": {"prep.", "conj."},
    "as": {"prep.", "conj.", "adv."},
    "like": {"prep.", "v.", "adj.", "conj."},
    "than": {"prep.", "conj."},
    "per": {"prep."},
    # 连词
    "and": {"conj."},
    "or": {"conj."},
    "but": {"conj.", "prep."},
    "so": {"conj.", "adv."},
    "because": {"conj."},
    "if": {"conj."},
    "although": {"conj."},
    "though": {"conj.", "adv."},
    "while": {"conj.", "n."},
    "whether": {"conj."},
    "unless": {"conj."},
    "both": {"conj.", "adj.", "pron."},
    "either": {"conj.", "adj.", "pron.", "adv."},
    "neither": {"conj.", "adj.", "pron.", "adv."},
    "nor": {"conj."},
    "yet": {"conj.", "adv."},
    # 感叹
    "hello": {"interj."},
    "hi": {"interj."},
    "hey": {"interj."},
    "bye": {"interj."},
    "goodbye": {"interj."},
    "yes": {"interj.", "n.", "adv."},
    "no": {"interj.", "adv.", "adj.", "n."},
    "ok": {"interj.", "adj.", "adv."},
    "okay": {"interj.", "adj.", "adv."},
    "please": {"interj.", "v.", "adv."},
    "thanks": {"interj.", "n."},
    "thank you": {"interj."},
    "sorry": {"interj.", "adj."},
    "excuse me": {"interj."},
    "wow": {"interj."},
    "oh": {"interj."},
    "ah": {"interj."},
    "oops": {"interj."},
    "come on": {"interj."},
    # 月份 / 星期
    "january": {"n."},
    "february": {"n."},
    "march": {"n.", "v."},
    "april": {"n."},
    "may": {"n.", "v."},
    "june": {"n."},
    "july": {"n."},
    "august": {"n."},
    "september": {"n."},
    "october": {"n."},
    "november": {"n."},
    "december": {"n."},
    "monday": {"n."},
    "tuesday": {"n."},
    "wednesday": {"n."},
    "thursday": {"n."},
    "friday": {"n."},
    "saturday": {"n."},
    "sunday": {"n."},
    # 常见多词性
    "book": {"n.", "v."},
    "water": {"n.", "v."},
    "light": {"n.", "v.", "adj."},
    "play": {"v.", "n."},
    "run": {"v.", "n."},
    "walk": {"v.", "n."},
    "talk": {"v.", "n."},
    "work": {"v.", "n."},
    "help": {"v.", "n."},
    "call": {"v.", "n."},
    "name": {"n.", "v."},
    "show": {"v.", "n."},
    "open": {"v.", "adj."},
    "close": {"v.", "adj.", "adv."},
    "clean": {"v.", "adj."},
    "dry": {"v.", "adj."},
    "warm": {"adj.", "v."},
    "cool": {"adj.", "v."},
    "cold": {"adj.", "n."},
    "hot": {"adj."},
    "right": {"adj.", "n.", "adv."},
    "left": {"adj.", "n.", "adv."},
    "back": {"n.", "adv.", "adj.", "v."},
    "front": {"n.", "adj."},
    "home": {"n.", "adv."},
    "school": {"n."},
    "class": {"n."},
    "park": {"n.", "v."},
    "shop": {"n.", "v."},
    "store": {"n.", "v."},
    "watch": {"v.", "n."},
    "look": {"v.", "n."},
    "see": {"v."},
    "hear": {"v."},
    "listen": {"v."},
    "read": {"v."},
    "write": {"v."},
    "draw": {"v."},
    "sing": {"v."},
    "dance": {"v.", "n."},
    "swim": {"v.", "n."},
    "fly": {"v.", "n."},
    "drive": {"v.", "n."},
    "ride": {"v.", "n."},
    "jump": {"v.", "n."},
    "stop": {"v.", "n."},
    "start": {"v.", "n."},
    "begin": {"v."},
    "end": {"n.", "v."},
    "finish": {"v.", "n."},
    "cook": {"v.", "n."},
    "drink": {"v.", "n."},
    "eat": {"v."},
    "sleep": {"v.", "n."},
    "dream": {"n.", "v."},
    "love": {"v.", "n."},
    "like": {"v.", "prep.", "adj.", "conj."},
    "want": {"v."},
    "need": {"v.", "n."},
    "hope": {"v.", "n."},
    "wish": {"v.", "n."},
    "feel": {"v."},
    "think": {"v."},
    "know": {"v."},
    "learn": {"v."},
    "teach": {"v."},
    "study": {"v.", "n."},
    "use": {"v.", "n."},
    "make": {"v."},
    "do": {"v.", "n."},
    "have": {"v."},
    "has": {"v."},
    "had": {"v."},
    "be": {"v."},
    "am": {"v."},
    "is": {"v."},
    "are": {"v."},
    "was": {"v."},
    "were": {"v."},
    "been": {"v."},
    "being": {"v.", "n."},
    "can": {"v.", "n."},
    "could": {"v."},
    "may": {"v.", "n."},
    "might": {"v.", "n."},
    "must": {"v.", "n."},
    "shall": {"v."},
    "should": {"v."},
    "will": {"v.", "n."},
    "would": {"v."},
    "get": {"v."},
    "got": {"v."},
    "give": {"v."},
    "take": {"v."},
    "put": {"v."},
    "set": {"v.", "n.", "adj."},
    "let": {"v."},
    "keep": {"v."},
    "find": {"v."},
    "found": {"v.", "adj."},
    "turn": {"v.", "n."},
    "change": {"v.", "n."},
    "move": {"v.", "n."},
    "stand": {"v.", "n."},
    "sit": {"v."},
    "lie": {"v.", "n."},
    "fall": {"v.", "n."},
    "rain": {"n.", "v."},
    "snow": {"n.", "v."},
    "wind": {"n.", "v."},
    "sun": {"n."},
    "moon": {"n."},
    "star": {"n.", "v."},
    "day": {"n."},
    "night": {"n."},
    "morning": {"n."},
    "afternoon": {"n."},
    "evening": {"n."},
    "time": {"n.", "v."},
    "year": {"n."},
    "month": {"n."},
    "week": {"n."},
    "today": {"n.", "adv."},
    "tomorrow": {"n.", "adv."},
    "yesterday": {"n.", "adv."},
    "now": {"adv.", "n."},
    "then": {"adv.", "conj."},
    "here": {"adv."},
    "there": {"adv."},
    "away": {"adv."},
    "again": {"adv."},
    "too": {"adv."},
    "also": {"adv."},
    "very": {"adv."},
    "much": {"adj.", "adv.", "pron."},
    "many": {"adj.", "pron."},
    "more": {"adj.", "adv.", "pron."},
    "most": {"adj.", "adv.", "pron."},
    "some": {"adj.", "pron."},
    "any": {"adj.", "pron."},
    "all": {"adj.", "pron.", "adv."},
    "every": {"adj."},
    "each": {"adj.", "pron."},
    "other": {"adj.", "pron."},
    "another": {"adj.", "pron."},
    "same": {"adj.", "pron."},
    "different": {"adj."},
    "new": {"adj."},
    "old": {"adj."},
    "young": {"adj."},
    "big": {"adj."},
    "small": {"adj."},
    "little": {"adj.", "adv."},
    "long": {"adj.", "adv."},
    "short": {"adj."},
    "tall": {"adj."},
    "high": {"adj.", "adv."},
    "low": {"adj.", "adv."},
    "fast": {"adj.", "adv."},
    "slow": {"adj.", "adv.", "v."},
    "early": {"adj.", "adv."},
    "late": {"adj.", "adv."},
    "good": {"adj.", "n."},
    "bad": {"adj."},
    "well": {"adv.", "adj.", "n."},
    "better": {"adj.", "adv."},
    "best": {"adj.", "adv.", "n."},
    "happy": {"adj."},
    "sad": {"adj."},
    "busy": {"adj."},
    "free": {"adj.", "v.", "adv."},
    "ready": {"adj."},
    "sure": {"adj.", "adv."},
    "true": {"adj."},
    "false": {"adj."},
    "first": {"num.", "adj.", "adv."},
    "last": {"adj.", "adv.", "v.", "n."},
    "next": {"adj.", "adv."},
    "only": {"adj.", "adv."},
    "just": {"adv.", "adj."},
    "even": {"adv.", "adj."},
    "still": {"adv.", "adj."},
    "already": {"adv."},
    "always": {"adv."},
    "often": {"adv."},
    "sometimes": {"adv."},
    "never": {"adv."},
    "usually": {"adv."},
    "really": {"adv."},
    "quite": {"adv."},
    "together": {"adv."},
    "alone": {"adj.", "adv."},
    "not": {"adv."},
    "don't": {"v."},
    "doesn't": {"v."},
    "didn't": {"v."},
    "can't": {"v."},
    "won't": {"v."},
    "isn't": {"v."},
    "aren't": {"v."},
    "wasn't": {"v."},
    "weren't": {"v."},
    "haven't": {"v."},
    "hasn't": {"v."},
    "hadn't": {"v."},
}


def from_notes(notes: str) -> set[str]:
    n = notes or ""
    if "序数词" in n:
        return {"num.", "adj."}
    if "数词" in n:
        return {"num."}
    if any(x in n for x in ("月份", "星期", "节日")):
        return {"n."}
    return set()


def from_meaning(meaning: str) -> set[str]:
    m = (meaning or "").strip()
    tags: set[str] = set()
    if not m:
        return tags
    # 常见中文释义线索（可叠加）
    if any(x in m for x in ("感叹", "嘿", "喂", "啊", "呀")):
        tags.add("interj.")
    if m.endswith("的") or "的；" in m or "的," in m:
        tags.add("adj.")
    if m.endswith("地") or "地；" in m:
        tags.add("adv.")
    if any(x in m for x in ("……的", "…的", "……地")):
        if "地" in m:
            tags.add("adv.")
        if "的" in m:
            tags.add("adj.")
    return tags


def from_wordnet(term: str) -> set[str]:
    key = term.strip().lower().replace("’", "'")
    # 短语：取末词或整词查询
    candidates = [key]
    if " " in key:
        candidates.append(key.replace(" ", "_"))
        candidates.append(key.split()[-1])
    if "-" in key:
        candidates.append(key.replace("-", "_"))
        candidates.append(key.split("-")[-1])

    tags: set[str] = set()
    for cand in candidates:
        for syn in wn.synsets(cand):
            mapped = WN_MAP.get(syn.pos())
            if mapped:
                tags.add(mapped)
        if tags:
            break
    return tags


def infer_pos(term: str, meaning: str, notes: str) -> str:
    key = term.strip().lower().replace("’", "'")
    # 短语（含空格）不设词性
    if " " in key:
        return ""

    tags: set[str] = set()

    # 1) 封闭词类 / 手工表：完全采用，避免 WordNet 噪声
    if key in CURATED:
        return join_pos(CURATED[key])

    # 2) 备注分类
    note_tags = from_notes(notes)
    if "数词" in (notes or "") or "序数词" in (notes or ""):
        return join_pos(note_tags)
    tags |= note_tags

    # 3) WordNet 开放词类（可多词性）
    tags |= from_wordnet(term)
    tags |= from_meaning(meaning)

    # 4) 兜底
    if not tags:
        if "-" in key:
            tags.add("n.")
        elif key.endswith("ly") and len(key) > 3:
            tags.add("adv.")
        else:
            tags.add("n.")

    return join_pos(tags)


def main() -> None:
    parser = argparse.ArgumentParser(description="批量补全单词词性（支持多词性）")
    parser.add_argument(
        "--only-empty",
        action="store_true",
        default=True,
        help="只填空词性（默认）",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="覆盖已有词性",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只打印不写库",
    )
    args = parser.parse_args()
    only_empty = not args.all

    db = connect()
    init_schema(db)
    rows = db.execute(
        "SELECT id, term, meaning, notes, pos FROM words ORDER BY id"
    ).fetchall()

    updated = 0
    multi = 0
    samples: list[tuple[str, str]] = []
    for row in rows:
        old = (row["pos"] or "").strip()
        if only_empty and old:
            continue
        new = infer_pos(row["term"], row["meaning"] or "", row["notes"] or "")
        if not new or new == old:
            continue
        if " / " in new:
            multi += 1
        if len(samples) < 40:
            samples.append((row["term"], new))
        if not args.dry_run:
            db.execute("UPDATE words SET pos = ? WHERE id = ?", (new, row["id"]))
        updated += 1

    if not args.dry_run:
        db.commit()
    empty = db.execute(
        "SELECT COUNT(*) AS n FROM words WHERE COALESCE(pos, '') = ''"
    ).fetchone()["n"]
    total = db.execute("SELECT COUNT(*) AS n FROM words").fetchone()["n"]
    multi_n = db.execute(
        "SELECT COUNT(*) AS n FROM words WHERE pos LIKE '% / %'"
    ).fetchone()["n"]
    db.close()

    mode = "dry-run" if args.dry_run else "written"
    print(f"[{mode}] 更新 {updated} 条（其中推断为多词性 {multi} 条）")
    print(f"库中合计 {total}，仍空 {empty}，多词性 {multi_n}")
    print("样例：")
    for term, pos in samples:
        print(f"  {term:24} {pos}")


if __name__ == "__main__":
    main()
