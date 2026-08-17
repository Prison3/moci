#!/usr/bin/env python3
"""Parse a Shanghai Oxford word dump and emit extra entries."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from data.primary_school_words import WORDS  # noqa: E402

PAIR_RE = re.compile(
    r"([A-Za-z][A-Za-z0-9'’.\-]{0,28}"
    r"(?:\s+[A-Za-z][A-Za-z0-9'’.\-]{0,20}){0,2})"
    r"\s*"
    r"([\u4e00-\u9fff《》（）()、；;：:\.．…，,——\-]+)"
)

SKIP_LINE = re.compile(
    r"年级|M\dU\d|小学|网站|相关|热门|演讲|声明|Copyright|本文来源|上一篇|下一篇"
)

SKIP_TERMS = {
    "yum",
    "rat-tat",
    "pencilpencil",
    "halt-time",
    "toy story",
    "mona lisa",
    "the louvre",
    "litter red riding hood",
    "red riding hood",
    "have a clod",
    "have nothing on",
    "t-shirtt",
    # 不规则过去式：课本当词条学原形即可
    "bought",
    "built",
    "came",
    "did",
    "grew",
    "had",
    "heard",
    "learnt",
    "sat",
    "saw",
    "was",
    "were",
    "went",
}

TERM_FIXES = {
    "at moon": "at noon",
    "t-shirtt": "t-shirt",
    "australia": "Australia",
}

MEANING_FIXES = {
    "a loaf of": "一条（面包）",
    "at noon": "在中午",
    "go trick-or-treating": "玩万圣节不给糖就捣蛋",
    "snack": "零食",
    "so": "如此；太",
    "t-shirt": "T恤衫",
    "television": "电视",
    "traffic light": "交通信号灯",
    "wild goose": "大雁",
}

# 教材里常见、抓取时漏掉的上海牛津词
CURATED = [
    ("badminton", "", "羽毛球", "小学 · 上海牛津"),
    ("balcony", "", "阳台", "小学 · 上海牛津"),
    ("basin", "", "盆；洗脸盆", "小学 · 上海牛津"),
    ("bookshop", "", "书店", "小学 · 上海牛津"),
    ("boots", "", "靴子", "小学 · 上海牛津"),
    ("chips", "", "薯条；炸土豆条", "小学 · 上海牛津"),
    ("copybook", "", "习字本；抄写本", "小学 · 上海牛津"),
    ("countryside", "", "乡村", "小学 · 上海牛津"),
    ("cricket", "", "板球", "小学 · 上海牛津"),
    ("crisps", "", "薯片", "小学 · 上海牛津"),
    ("crossroads", "", "十字路口", "小学 · 上海牛津"),
    ("department store", "", "百货商店", "小学 · 上海牛津"),
    ("dining room", "", "餐厅", "小学 · 上海牛津"),
    ("exercise book", "", "练习本", "小学 · 上海牛津"),
    ("fence", "", "篱笆；栅栏", "小学 · 上海牛津"),
    ("ferry", "", "渡船", "小学 · 上海牛津"),
    ("flat", "", "公寓", "小学 · 上海牛津"),
    ("fridge", "", "冰箱", "小学 · 上海牛津"),
    ("gate", "", "大门", "小学 · 上海牛津"),
    ("glove", "", "手套", "小学 · 上海牛津"),
    ("ham", "", "火腿", "小学 · 上海牛津"),
    ("headteacher", "", "校长", "小学 · 上海牛津"),
    ("hopscotch", "", "跳房子", "小学 · 上海牛津"),
    ("island", "", "岛", "小学 · 上海牛津"),
    ("jumper", "", "套头毛衣", "小学 · 上海牛津"),
    ("kettle", "", "水壶", "小学 · 上海牛津"),
    ("kiwi", "", "猕猴桃", "小学 · 上海牛津"),
    ("lamb", "", "羊肉；小羊", "小学 · 上海牛津"),
    ("lift", "", "电梯", "小学 · 上海牛津"),
    ("mango", "", "芒果", "小学 · 上海牛津"),
    ("mushroom", "", "蘑菇", "小学 · 上海牛津"),
    ("onion", "", "洋葱", "小学 · 上海牛津"),
    ("pea", "", "豌豆", "小学 · 上海牛津"),
    ("primary school", "", "小学", "小学 · 上海牛津"),
    ("queue", "", "排队；队列", "小学 · 上海牛津"),
    ("raincoat", "", "雨衣", "小学 · 上海牛津"),
    ("roundabout", "", "环形交叉路口", "小学 · 上海牛津"),
    ("rugby", "", "橄榄球", "小学 · 上海牛津"),
    ("sandals", "", "凉鞋", "小学 · 上海牛津"),
    ("sandwich", "", "三明治", "小学 · 上海牛津"),
    ("sausage", "", "香肠", "小学 · 上海牛津"),
    ("slippers", "", "拖鞋", "小学 · 上海牛津"),
    ("spaceship", "", "宇宙飞船", "小学 · 上海牛津"),
    ("steak", "", "牛排", "小学 · 上海牛津"),
    ("sweets", "", "糖果", "小学 · 上海牛津"),
    ("table tennis", "", "乒乓球", "小学 · 上海牛津"),
    ("tap", "", "水龙头", "小学 · 上海牛津"),
    ("the Double Ninth Festival", "", "重阳节", "小学 · 上海牛津"),
    ("the Dragon Boat Festival", "", "端午节", "小学 · 上海牛津"),
    ("trainers", "", "运动鞋", "小学 · 上海牛津"),
    ("uniform", "", "校服；制服", "小学 · 上海牛津"),
    ("wardrobe", "", "衣柜", "小学 · 上海牛津"),
]

existing = {w[0].lower() for w in WORDS}


def clean_term(term: str) -> str:
    term = term.replace("’", "'").strip(" .")
    term = re.sub(r"\s+", " ", term)
    return TERM_FIXES.get(term.lower(), term)


def clean_meaning(meaning: str) -> str:
    meaning = meaning.strip(" .;；,，")
    meaning = re.sub(r"\s+", "", meaning)
    meaning = re.sub(r"[（(]+$", "", meaning)
    if not re.search(r"[\u4e00-\u9fff]", meaning):
        return ""
    return meaning[:80]


def ok(term: str, meaning: str) -> bool:
    key = term.lower()
    if not term or not meaning:
        return False
    if key in SKIP_TERMS or key in existing:
        return False
    if len(term) > 36:
        return False
    if term.lower().startswith(("www", "http")):
        return False
    words = term.split()
    if len(words) > 4:
        return False
    if words[0].lower() in {"a", "an", "the"} and len(words) == 2:
        return False
    if re.fullmatch(r"[A-Za-z]+s", term) and term.lower()[:-1] in existing:
        return False
    return True


def main() -> None:
    src = Path(sys.argv[1])
    text = src.read_text(encoding="utf-8")
    text = text.split("本文来源")[0]
    seen: dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or SKIP_LINE.search(line):
            continue
        for term, meaning in PAIR_RE.findall(line):
            term = clean_term(term)
            key = term.lower()
            meaning = MEANING_FIXES.get(key, clean_meaning(meaning))
            if not ok(term, meaning):
                continue
            if key not in seen:
                seen[key] = meaning

    for term, _phonetic, meaning, _notes in CURATED:
        key = term.lower()
        if key in existing or key in seen or key in SKIP_TERMS:
            continue
        seen[key] = meaning

    out = ROOT / "data" / "shanghai_oxford_extra.py"
    lines = [
        "# 上海牛津小学英语（1–5 年级）补充词，已去掉词库里已有的条目。",
        "# 每条：(单词, 音标, 释义, 分类备注)",
        "",
        "EXTRA_WORDS = [",
    ]
    for term, meaning in sorted(seen.items(), key=lambda x: x[0].lower()):
        lines.append(f'    ({term!r}, "", {meaning!r}, "小学 · 上海牛津"),')
    lines.append("]")
    lines.append("")
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {len(seen)} extra words to {out}")


if __name__ == "__main__":
    main()
