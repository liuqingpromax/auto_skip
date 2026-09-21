#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验生成的 .docx 是否为合法 OOXML —— 打不开就等于白做。

逐项检查：
  1. 每个 XML 部件都能被解析（well-formed）；
  2. [Content_Types].xml 覆盖了包内所有部件；
  3. _rels/.rels 与 word/_rels/document.xml.rels 的 Target 都真实存在；
  4. document.xml 中引用的 r:id 都在 rels 里有定义；
  5. 根元素名称正确。
"""

import os
import posixpath
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

CT_NS = "{http://schemas.openxmlformats.org/package/2006/content-types}"
R_NS = "{http://schemas.openxmlformats.org/package/2006/relationships}"


def resolve(base_dir, target):
    """
    按 OOXML 规则把 rels 里的 Target 解析成包内路径。

    注意：真实 Word 文件会用相对路径，例如 word/_rels/document.xml.rels 里写
    `../customXml/item1.xml`（相对于 word/ 目录），必须规范化后才能比对，
    否则会产生假报警。
    """
    if target.startswith("/"):
        return target.lstrip("/")
    joined = posixpath.join(base_dir, target) if base_dir else target
    return posixpath.normpath(joined)


def check(path):
    problems = []
    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())

        # 1) XML 可解析
        for n in sorted(names):
            if n.endswith(".xml") or n.endswith(".rels"):
                try:
                    ET.fromstring(z.read(n))
                except ET.ParseError as e:
                    problems.append(f"XML 解析失败 {n}: {e}")

        # 2) Content_Types 覆盖所有部件
        ct = ET.fromstring(z.read("[Content_Types].xml"))
        defaults = {d.get("Extension").lower() for d in ct.findall(f"{CT_NS}Default")}
        overrides = {o.get("PartName").lstrip("/") for o in ct.findall(f"{CT_NS}Override")}
        for n in names:
            if n == "[Content_Types].xml":
                continue
            ext = n.rsplit(".", 1)[-1].lower() if "." in n else ""
            if n in overrides or ext in defaults:
                continue
            problems.append(f"Content_Types 未声明部件: {n}")

        # 3) rels 的 Target 存在（按 OOXML 规则解析相对路径）
        for rels_name, base in (("_rels/.rels", ""), ("word/_rels/document.xml.rels", "word")):
            if rels_name not in names:
                problems.append(f"缺少关系文件: {rels_name}")
                continue
            rels = ET.fromstring(z.read(rels_name))
            for rel in rels.findall(f"{R_NS}Relationship"):
                if rel.get("TargetMode") == "External":
                    continue
                resolved = resolve(base, rel.get("Target"))
                if resolved not in names:
                    problems.append(f"{rels_name} 指向不存在的部件: {resolved}")

        # 4) document.xml 里用到的 r:id 都有定义
        doc = z.read("word/document.xml").decode("utf-8")
        used = set(re.findall(r'r:id="([^"]+)"', doc))
        rels = ET.fromstring(z.read("word/_rels/document.xml.rels"))
        defined = {rel.get("Id") for rel in rels.findall(f"{R_NS}Relationship")}
        for rid in sorted(used - defined):
            problems.append(f"document.xml 使用了未定义的 r:id: {rid}")

        # 5) 根元素
        if not doc.startswith("<?xml"):
            problems.append("document.xml 缺少 XML 声明")
        if "<w:document" not in doc:
            problems.append("document.xml 根元素不是 w:document")
        if "<w:body>" not in doc or "</w:body>" not in doc:
            problems.append("document.xml 缺少 w:body")

    return problems, len(names)


def main():
    targets = sys.argv[1:]
    if not targets:
        base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "软著申请资料")
        targets = []
        for root, _, files in os.walk(base):
            for f in files:
                if f.endswith(".docx"):
                    targets.append(os.path.join(root, f))

    ok = True
    for t in targets:
        problems, parts = check(t)
        rel = os.path.relpath(t, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
        if problems:
            ok = False
            print(f"✗ {rel}  （{parts} 个部件）")
            for p in problems:
                print(f"    - {p}")
        else:
            print(f"✓ {rel}  （{parts} 个部件，结构合法）")

    print()
    print("全部合法 ✓" if ok else "存在结构问题 ✗")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
