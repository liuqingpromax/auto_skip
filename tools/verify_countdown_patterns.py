#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
倒计时跳过按钮的正则验证（v0.4.0）。

背景：用户反馈存在两种排列 —— 「跳过3」（数字在后）与「3跳过」（数字在前），
n 通常为 1~5 秒。学习模式以前只剥尾部数字，`3跳过` 会被整串当成关键词，
学出来的规则只能匹配倒计时恰好等于那一刻的情况。

本脚本把 SkipTextNormalizer 的**等价实现**与 BuiltinRules 的正则搬过来，
用真实按钮文本验证：
  1. keywordOf 能把两种排列都归一成「跳过」；
  2. 学习模式生成的正则能匹配同一按钮在倒计时变化后的文本；
  3. 内置规则的两条倒计时条件覆盖两种排列；
  4. 不会误匹配无关文本（防误触侧的自检）。

注意：这是等价模型（Python re），用于快速回归；Python 与 Java 的正则在
本用例所用语法（字符类 / 交替 / 量词）上行为一致。
"""

import re
import sys

# ---- 与 SkipTextNormalizer 等价的实现 ----

FULL_WIDTH_DIGITS = "０-９"
FULL_WIDTH_SPACE = "\u3000"
DIGITS = f"0-9{FULL_WIDTH_DIGITS}"
# 连字符必须放字符类末尾：`】-—` 会被当作逆序范围而报错（这正是本次抓到的 bug）
CONNECTORS = f"\\s{FULL_WIDTH_SPACE}sS秒后·\\.:：,，、（）\\(\\)\\[\\]【】|/\\\\-—"
TRIM_EDGE = re.compile(f"^[{DIGITS}{CONNECTORS}]+|[{DIGITS}{CONNECTORS}]+$")

KEYWORDS = [
    "跳过广告", "跳過廣告", "关闭广告", "關閉廣告", "跳过按钮",
    "跳过", "跳過", "略过", "略過", "关闭", "關閉",
    "skip ad", "close ad", "skip", "close", "dismiss", "cancel",
    "✕", "✖", "✗", "×", "⨯", "╳", "❌", "❎",
]


def keyword_of(raw):
    if raw is None or not raw.strip():
        return None
    text = raw.strip()
    lower = text.lower()
    for kw in KEYWORDS:
        if kw in text or kw.lower() in lower:
            return kw
    stripped = TRIM_EDGE.sub("", text).strip()
    return stripped or None


def pattern_for(kw):
    escaped = re.escape(kw)
    if any(ch.isdigit() for ch in kw):
        return escaped
    return "|".join([
        escaped,
        f"[{DIGITS}]*[{CONNECTORS}]*{escaped}",
        f"{escaped}[{CONNECTORS}]*[{DIGITS}]+",
    ])


# ---- 内置规则：直接从 Kotlin 源码里的 JSON 读取，避免与规则定义漂移 ----

import json
import os

_BUILTIN_KT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "java", "com", "example", "skipstart", "data", "BuiltinRules.kt",
)


def load_builtin_conditions():
    """从 BuiltinRules.kt 抽出 AMAP_SPLASH_RULE_JSON 并返回其 conditions。"""
    with open(_BUILTIN_KT, encoding="utf-8") as f:
        src = f.read()
    m = re.search(r'AMAP_SPLASH_RULE_JSON: String = """(.*?)"""', src, re.S)
    if not m:
        raise SystemExit("未能从 BuiltinRules.kt 提取规则 JSON —— 源码结构变了，请同步本脚本")
    return json.loads(m.group(1))


_AMAP = load_builtin_conditions()
# 取所有 text_regex 条件作为「内置规则能命中的文字模式」
BUILTIN = {
    f"文字#{i + 1}": c["pattern"]
    for i, c in enumerate(c for c in _AMAP["conditions"] if c["type"] == "text_regex")
}

# ---- 用例 ----

# (按钮文本, 是否应被识别出「跳过」类关键词)
KEYWORD_CASES = [
    ("跳过", "跳过"),
    ("跳过3", "跳过"),
    ("跳过 3", "跳过"),
    ("跳过3s", "跳过"),
    ("跳过 5 秒", "跳过"),
    ("3跳过", "跳过"),
    ("3 跳过", "跳过"),
    ("5秒后跳过", "跳过"),
    ("3s跳过", "跳过"),
    ("3s后跳过", "跳过"),
    ("5秒跳过", "跳过"),
    ("关闭3", "关闭"),
    ("3关闭", "关闭"),
    ("跳过广告", "跳过广告"),
    ("跳过广告 3", "跳过广告"),
    ("skip", "skip"),
    ("skip 3", "skip"),
    ("3 skip", "skip"),
    ("Skip in 3s", "skip"),
    ("✕", "✕"),
    ("3", None),          # 纯数字：不该当关键词（否则会误点）
    ("", None),
    (None, None),
]

# 学习模式的关键场景：从某一瞬间学到，之后倒计时变化仍要命中
LEARN_CASES = [
    # 学到的文本, [(后续出现的文本, 期望是否命中)]
    ("跳过3", [("跳过3", True), ("跳过5", True), ("跳过", True),
               ("跳过2s", True), ("3跳过", True), ("5秒后跳过", True)]),
    ("3跳过", [("3跳过", True), ("5跳过", True), ("跳过", True),
               ("2跳过", True), ("跳过5", True)]),
    ("5秒后跳过", [("5秒后跳过", True), ("3秒后跳过", True), ("跳过", True),
                   ("跳过5", True), ("3跳过", True)]),
    ("跳过", [("跳过", True), ("跳过5", True), ("3跳过", True)]),
    ("关闭2", [("关闭2", True), ("关闭5", True), ("关闭", True), ("2关闭", True)]),
]

# 不该被倒计时条件误命中的文本（防误触侧自检）
NEGATIVE_CASES = [
    "立即下载", "同意并继续", "打开", "查看详情", "领取奖励", "5折优惠", "3件商品",
]


def re_search(pattern, text):
    """与 RuleMatcher 保持一致：Kotlin 侧用 RegexOption.IGNORE_CASE，这里必须同样忽略大小写。"""
    return re.search(pattern, text, re.IGNORECASE)


def main():
    ok = True
    print("=" * 78)
    print("1) keywordOf：两种排列都归一成同一关键词")
    print("=" * 78)
    for raw, expect in KEYWORD_CASES:
        got = keyword_of(raw)
        mark = "✓" if got == expect else "✗"
        if got != expect:
            ok = False
        print(f"{mark} {str(raw)!r:18s} -> {got!r:12s} (期望 {expect!r})")

    print()
    print("=" * 78)
    print("2) 学习模式：倒计时变化后仍能命中")
    print("=" * 78)
    for learned, followups in LEARN_CASES:
        kw = keyword_of(learned)
        pattern = pattern_for(kw)
        print(f"学到 {learned!r} → 关键词 {kw!r}")
        print(f"   生成正则: {pattern[:92]}{'...' if len(pattern) > 92 else ''}")
        for text, expect in followups:
            hit = re_search(pattern, text) is not None
            mark = "✓" if hit == expect else "✗"
            if hit != expect:
                ok = False
            print(f"   {mark} 后续出现 {text!r:12s} 命中={hit} (期望 {expect})")

    print()
    print("=" * 78)
    print("3) 内置规则（直接读 BuiltinRules.kt）：覆盖两种排列")
    print("=" * 78)
    for name, pattern in BUILTIN.items():
        print(f"-- {name}: {pattern[:104]}{'...' if len(pattern) > 104 else ''}")
    samples = ["跳过", "跳过3", "3跳过", "跳过 5 秒", "5秒后跳过", "3s跳过",
               "关闭2", "2关闭", "skip 3", "3 skip", "Skip in 3s"]
    for text in samples:
        hits = [n for n, p in BUILTIN.items() if re_search(p, text)]
        mark = "✓" if hits else "✗"
        if not hits:
            ok = False
        print(f"   {mark} {text!r:14s} 命中条件: {hits}")

    print()
    print("=" * 78)
    print("4) 防误触：无关文本不应被倒计时条件命中")
    print("=" * 78)
    for text in NEGATIVE_CASES:
        hits = [n for n, p in BUILTIN.items() if re_search(p, text)]
        good = not hits
        mark = "✓" if good else "✗"
        if not good:
            ok = False
        print(f"{mark} {text!r:14s} 命中: {hits if hits else '（无，正确）'}")

    print()
    print("全部通过 ✓" if ok else "存在不符合预期的用例 ✗")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
