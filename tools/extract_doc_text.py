#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
提取 Word 文档正文为纯文本（软著资料整理用）。

- .docx：直接用 zipfile 读 word/document.xml（无需 python-docx）
- .doc （Word 97-2003 二进制）：能提取到多少算多少（按可打印字符粗略切分），
  若效果不好会明确提示改用 Word 另存为 .docx

用法：
  python tools/extract_doc_text.py "排版版 Word 文档（.docx）.docx" out.txt
"""

import re
import sys
import zipfile


def docx_to_text(path):
    with zipfile.ZipFile(path) as z:
        xml = z.read("word/document.xml").decode("utf-8", errors="replace")
    # 段落 / 换行 / 制表符
    xml = re.sub(r"<w:br[^>]*/>", "\n", xml)
    xml = re.sub(r"<w:tab[^>]*/>", "\t", xml)
    xml = re.sub(r"</w:p>", "\n", xml)
    # 去掉所有标签后做实体还原
    text = re.sub(r"<[^>]+>", "", xml)
    for a, b in (("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
                 ("&quot;", '"'), ("&apos;", "'")):
        text = text.replace(a, b)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def doc_to_text(path):
    """Word 97-2003 二进制：抽取 UTF-16LE 可读片段，属于尽力而为。"""
    raw = open(path, "rb").read()
    # WordDocument 正文多为 UTF-16LE；以可打印字符判定
    try:
        text = raw.decode("utf-16-le", errors="ignore")
    except Exception:
        text = raw.decode("latin-1", errors="ignore")
    # 仅保留中日韩、常用标点、字母数字
    text = "".join(
        ch if (ch.isprintable() or ch in "\n\t") else "\n" for ch in text
    )
    text = re.sub(r"[ \t]{2,}", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    src, dst = sys.argv[1], sys.argv[2]
    if src.lower().endswith(".docx"):
        text = docx_to_text(src)
    elif src.lower().endswith(".doc"):
        text = doc_to_text(src)
        print("注意：.doc 为二进制格式，提取结果可能包含噪声；建议用 Word 另存为 .docx 后重跑。")
    else:
        print("仅支持 .doc / .docx")
        return 1

    with open(dst, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"已提取 {len(text)} 字符 -> {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
