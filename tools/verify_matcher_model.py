#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RuleMatcher v0.3.0 评分逻辑等价模型自检。

用途：本仓库没有单元测试基建（离线环境无法拉取 JUnit 依赖），
这里用 Python 复刻 RuleMatcher 的位置分/尺寸分/icon_button 判定，
对照验证「多角度」改造是否真的生效。

注意：这是**等价模型**，不是直接执行 Kotlin 代码。
若将来补上 JVM 单元测试，本脚本可作为期望值的来源。
"""

W, H = 1080, 1920


def position_score(b):
    """对应 RuleMatcher.positionScore：0..25"""
    cx, cy = (b[0] + b[2]) / 2, (b[1] + b[3]) / 2
    at_left, at_right = cx < W * 0.28, cx > W * 0.72
    at_top, at_bottom = cy < H * 0.18, cy > H * 0.82
    if (at_top or at_bottom) and (at_left or at_right):
        return 25
    if at_top:
        return 22
    if at_bottom:
        return 18
    if at_left or at_right:
        return 10
    return 0


def size_score(b):
    """对应 RuleMatcher.sizeScore：0..15"""
    r = (b[2] - b[0]) / W
    if r <= 0.12:
        return 15
    if r <= 0.22:
        return 10
    if r <= 0.35:
        return 5
    return 0


def in_edge_zone(b):
    cx, cy = (b[0] + b[2]) / 2, (b[1] + b[3]) / 2
    near_top, near_bottom = cy < H * 0.20, cy > H * 0.80
    near_side = cx < W * 0.25 or cx > W * 0.75
    return near_top or near_bottom or (near_side and (cy < H * 0.35 or cy > H * 0.65))


def is_icon_button(b, clickable=True, text=None, desc=None):
    """对应 RuleMatcher.isIconButtonCandidate"""
    if not clickable:
        return False
    if text:
        return False
    if desc:
        return False
    wr = (b[2] - b[0]) / W
    hr = (b[3] - b[1]) / H
    if wr > 0.22 or hr > 0.18:
        return False
    return in_edge_zone(b)


def total(base, b, clickable=True):
    return base + position_score(b) + (10 if clickable else 0) + size_score(b)


ICON_BASE = 25      # icon_button 条件分
TEXT_BASE = 55      # 「跳过/关闭/✕」文字条件分
THRESHOLD = 60      # 默认 minScore

CASES = [
    # 名称, bounds, 是否命中 icon_button, 期望得分, 期望达标
    #
    # 命中 icon_button 时得分 = 25(条件) + 位置分 + 10(可点击) + 尺寸分
    # 未命中时该条件不计分，得分记 0（= 这条规则单独匹配不到该节点）
    ("右上角 ✕ 图标(小)",       (940, 60, 1030, 150),    True,  75, True),   # 25+25+10+15
    ("左上角 ✕ 图标(小)",       (40, 60, 130, 150),      True,  75, True),   # 25+25+10+15
    ("右下角 ✕ 图标(小)",       (940, 1780, 1030, 1870), True,  75, True),   # 25+25+10+15
    ("左下角 ✕ 图标(小)",       (40, 1780, 130, 1870),   True,  75, True),   # 25+25+10+15
    ("顶部通栏右侧 ✕",          (960, 20, 1050, 90),     True,  75, True),   # 25+25+10+15
    ("底部横幅右上 ✕",          (950, 1700, 1040, 1790), True,  75, True),   # 25+25+10+15
    # 以下位置不在边缘区 / 尺寸过大 / 非可点击 → icon_button 不命中，得分 0
    ("屏幕正中 ✕ 图标",         (500, 900, 590, 990),    False,  0, False),
    ("正中偏下小图标",          (500, 1200, 590, 1290),  False,  0, False),
    ("左侧中部小图标",          (20, 900, 110, 990),     False,  0, False),
    ("大块可点击区域(中间)",    (100, 600, 980, 1400),   False,  0, False),
]

print("=" * 78)
print("icon_button 条件（无文字纯图标，靠位置分+尺寸分凑阈值）")
print("=" * 78)
ok = True
for name, b, exp_hit, exp_score, exp_pass in CASES:
    hit = is_icon_button(b)
    score = total(ICON_BASE, b) if hit else 0
    passed = hit and score >= THRESHOLD
    mark = "✓" if (hit == exp_hit and score == exp_score and passed == exp_pass) else "✗"
    if mark == "✗":
        ok = False
    print(f"{mark} {name:22s} icon={str(hit):5s} 得分={score:3d} 达标={str(passed):5s} "
          f"(期望 icon={exp_hit} 得分={exp_score} 达标={exp_pass})")

print()
print("=" * 78)
print("文字条件（全屏匹配，不看位置都能达标）")
print("=" * 78)
TEXT_CASES = [
    ("右上角「跳过」",   (900, 60, 1040, 140)),
    ("左上角「跳过」",   (40, 939, 180, 1019)),   # 故意放中部，验证位置分=0 也能靠文字分达标
    ("正中「跳过」文字", (470, 900, 610, 980)),
    ("底部「跳过」文字", (860, 1800, 1000, 1880)),
]
for name, b in TEXT_CASES:
    score = total(TEXT_BASE, b)
    passed = score >= THRESHOLD
    mark = "✓" if passed else "✗"
    if not passed:
        ok = False
    print(f"{mark} {name:18s} 位置分={position_score(b):2d} 尺寸分={size_score(b):2d} "
          f"总分={score:3d} 达标={passed}")

print()
print("=" * 78)
print("防误触底线检查")
print("=" * 78)
mid_icon = (500, 900, 590, 990)
checks = [
    ("屏幕正中纯图标拿不到位置分", position_score(mid_icon) == 0),
    ("屏幕正中纯图标总分低于阈值", total(ICON_BASE, mid_icon) < THRESHOLD),
    ("四个角落的位置分都是最高档 25", all(
        position_score(b) == 25 for b in [
            (40, 60, 130, 150), (940, 60, 1030, 150),
            (40, 1780, 130, 1870), (940, 1780, 1030, 1870)])),
    ("中部文字按钮仍可达标(位置分不加也不拦)", total(TEXT_BASE, (470, 900, 610, 980)) >= THRESHOLD),
]
for name, good in checks:
    mark = "✓" if good else "✗"
    if not good:
        ok = False
    print(f"{mark} {name}")

print()
print("全部通过 ✓" if ok else "存在不符合预期的用例 ✗")
raise SystemExit(0 if ok else 1)
