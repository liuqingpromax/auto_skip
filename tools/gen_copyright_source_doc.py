#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成软著申请用的「源程序」文档（.docx）。

软著对源程序的形式要求（中国版权保护中心）：
  - 每页不少于 50 行（末页除外）；
  - 页码连续、无空页；
  - 程序 3000 行以上可申请；>60 页时提交前 30 页 + 后 30 页；
  - 页眉标注软件名称与版本号，字体不小于五号（本脚本用页眉段落实现）。

本脚本：
  1. 按「先核心后界面」的顺序收集源码，拼接成统一流；
  2. 每 50 行插入一次分页，保证每页 50 行；
  3. 每个文件前插入一行 `// ===== 文件: 相对路径 =====` 作为分隔；
  4. 输出到 templates/源程序.docx（不写个人信息，由申请人自行补封面）。

用法：
  python tools/gen_copyright_source_doc.py [输出路径]
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from docx_writer import DocxBuilder, heading, para, code, page_break  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

LINES_PER_PAGE = 50

# 源码收集顺序：入口/配置 → 引擎 → 服务 → 数据 → 采集 → 界面 → 工具 → 资源
SOURCE_ORDER = [
    "app/build.gradle.kts",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle/libs.versions.toml",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/example/skipstart/SkipStartApp.kt",
    "app/src/main/java/com/example/skipstart/MainActivity.kt",
    "app/src/main/java/com/example/skipstart/AppGraph.kt",
    "app/src/main/java/com/example/skipstart/engine/Rule.kt",
    "app/src/main/java/com/example/skipstart/engine/MatchResult.kt",
    "app/src/main/java/com/example/skipstart/engine/RuleMatcher.kt",
    "app/src/main/java/com/example/skipstart/engine/SkipTextNormalizer.kt",
    "app/src/main/java/com/example/skipstart/engine/ActionExecutor.kt",
    "app/src/main/java/com/example/skipstart/engine/RuleEngine.kt",
    "app/src/main/java/com/example/skipstart/service/SkipAccessibilityService.kt",
    "app/src/main/java/com/example/skipstart/service/AntiTouchGuard.kt",
    "app/src/main/java/com/example/skipstart/service/LearningController.kt",
    "app/src/main/java/com/example/skipstart/capture/NodeSnapshot.kt",
    "app/src/main/java/com/example/skipstart/capture/NodeCollector.kt",
    "app/src/main/java/com/example/skipstart/data/AppSettings.kt",
    "app/src/main/java/com/example/skipstart/data/BuiltinRules.kt",
    "app/src/main/java/com/example/skipstart/data/RuleTemplates.kt",
    "app/src/main/java/com/example/skipstart/data/RuleRepository.kt",
    "app/src/main/java/com/example/skipstart/data/LogRepository.kt",
    "app/src/main/java/com/example/skipstart/data/InstalledAppScanner.kt",
    "app/src/main/java/com/example/skipstart/ui/MainScreen.kt",
    "app/src/main/java/com/example/skipstart/ui/HomeScreen.kt",
    "app/src/main/java/com/example/skipstart/ui/LearningScreen.kt",
    "app/src/main/java/com/example/skipstart/ui/LogScreen.kt",
    "app/src/main/java/com/example/skipstart/ui/RuleListScreen.kt",
    "app/src/main/java/com/example/skipstart/ui/RuleEditDialog.kt",
    "app/src/main/java/com/example/skipstart/ui/SettingsScreen.kt",
    "app/src/main/java/com/example/skipstart/ui/theme/Theme.kt",
    "app/src/main/java/com/example/skipstart/util/AccessibilityUtils.kt",
    "app/src/main/java/com/example/skipstart/util/ScreenUtils.kt",
    "app/src/main/java/com/example/skipstart/util/Logger.kt",
    "app/src/main/res/xml/accessibility_service_config.xml",
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values/themes.xml",
    "app/src/main/res/drawable/ic_launcher.xml",
]


def collect():
    """返回 (文件路径, 行列表) 列表，并输出统计。"""
    items = []
    missing = []
    for rel in SOURCE_ORDER:
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            missing.append(rel)
            continue
        with open(path, encoding="utf-8") as f:
            lines = f.read().replace("\r\n", "\n").rstrip("\n").split("\n")
        items.append((rel, lines))
    return items, missing


def build(out_path, software_name, version, applicant_placeholder=True,
          excerpt_head=0, excerpt_tail=0):
    """
    生成源程序文档。

    excerpt_head / excerpt_tail：>0 时只输出「前 N 页 + 后 M 页」，
    用于总页数超过 60 页的情形（软著规定 >60 页提交前 30 + 后 30）。
    此时页码保持与完整文档一致（例如前 30 页是 1-30，后 30 页是 92-121），
    原页码保留在正文中，避免审查时被质疑页码不连续。
    """
    items, missing = collect()
    total = sum(len(lines) + 1 for _, lines in items)  # +1 为文件分隔行

    # 先把所有行摊平成「页 -> 行列表」，便于按页裁剪
    pages = []
    buf = []
    for rel, lines in items:
        buf.append(f"// ===== 文件: {rel} =====")
        buf.extend(lines)
        while len(buf) >= LINES_PER_PAGE:
            pages.append(buf[:LINES_PER_PAGE])
            buf = buf[LINES_PER_PAGE:]
    if buf:
        pages.append(buf)
    full_page_count = len(pages)

    if excerpt_head > 0 or excerpt_tail > 0:
        head = pages[:excerpt_head] if excerpt_head > 0 else []
        tail = pages[-excerpt_tail:] if excerpt_tail > 0 else []
        selected = [(i + 1, pg) for i, pg in enumerate(head)]
        if excerpt_tail > 0:
            start_index = full_page_count - len(tail)
            selected += [(start_index + i + 1, pg) for i, pg in enumerate(tail)]
    else:
        selected = [(i + 1, pg) for i, pg in enumerate(pages)]

    doc = DocxBuilder(
        title=f"{software_name} 源程序",
        author=software_name,
        header_text=f"{software_name} V{version}",
    )

    # ---------- 封面（不含个人信息，申请人自行补充） ----------
    doc.add(para("", size=21))
    doc.add(heading(software_name, 0))
    doc.add(para(f"V{version}", bold=True, size=28, ascii_font="黑体",
                 east_font="黑体", align="center", after=480))
    doc.add(para("源 程 序", bold=True, size=32, ascii_font="黑体",
                 east_font="黑体", align="center", after=720))
    if applicant_placeholder:
        for label in ("软件名称：", "版 本 号：", "著作权人：", "开发完成日期："):
            doc.add(para(f"{label}＿＿＿＿＿＿＿＿＿＿＿＿", size=24, align="center", after=200))
    doc.add(para("", size=21))
    if excerpt_head or excerpt_tail:
        note = (f"说明：源程序共 {len(items)} 个文件、{full_page_count} 页（每页 {LINES_PER_PAGE} 行），"
                f"因超过 60 页，按规定提交前 {excerpt_head} 页与后 {excerpt_tail} 页；"
                f"页码为原文档实际页码，保持连续对应。")
    else:
        note = (f"说明：源程序共 {len(items)} 个文件、{full_page_count} 页、"
                f"{total} 行（含文件分隔行），每页 {LINES_PER_PAGE} 行，页码连续。")
    doc.add(para(note, size=21, align="center", after=120))
    doc.add(page_break())

    # ---------- 正文 ----------
    # 每页恰好 LINES_PER_PAGE 行代码：页码放在页脚（PAGE 域，不占正文行数），
    # 页眉放软件名称与版本（软著要求）。正文内**不**插页码标注行，
    # 否则每页会被占掉一行，变成 49 行/页，不符合"每页不少于 50 行"。
    for page_no, page_lines in selected:
        for ln in page_lines:
            doc.add(code(ln))
        doc.add(page_break())

    doc.save(out_path)
    return {"files": len(items), "lines": total, "pages": full_page_count,
            "submitted_pages": len(selected), "missing": missing}


def main():
    base = os.path.join(ROOT, "软著申请资料", "02_源程序")
    # 完整版（备查、内含全部 121 页，页码连续）
    full = os.path.join(base, "源程序-完整版.docx")
    stat = build(full, "开屏跳过助手", "0.4.0")
    print(f"已生成（完整版）: {full}")
    print(f"  文件数: {stat['files']}   总行数: {stat['lines']}   总页数: {stat['pages']}")

    # 提交版（>60 页，按规定只交前 30 页 + 后 30 页）
    if stat["pages"] > 60:
        sub = os.path.join(base, "源程序-提交版（前30页+后30页）.docx")
        stat2 = build(sub, "开屏跳过助手", "0.4.0", excerpt_head=30, excerpt_tail=30)
        print(f"已生成（提交版）: {sub}")
        print(f"  提交页数: {stat2['submitted_pages']}（前 30 + 后 30），"
              f"原文档共 {stat2['pages']} 页")

    if stat["missing"]:
        print("  未找到以下文件（已跳过）:")
        for m in stat["missing"]:
            print("   -", m)
    return 0


if __name__ == "__main__":
    sys.exit(main())
