#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把《AI 开发说明书》(.docx) 的正文重新排版为软著资料中的「设计说明书」，
并生成申请信息表与材料清单的 Markdown 版本。

设计说明书的作用：作为软著「软件说明书」的补充材料，体现软件的设计思路与需求来源，
审查时可与用户说明书互相印证（一份讲怎么用，一份讲设计依据）。

用法：
  python tools/gen_copyright_extras.py
"""

import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from docx_writer import DocxBuilder, heading, body, para, page_break  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
OUT_DIR = os.path.join(ROOT, "软著申请资料")


def docx_to_text(path):
    with zipfile.ZipFile(path) as z:
        xml = z.read("word/document.xml").decode("utf-8", errors="replace")
    xml = re.sub(r"<w:br[^>]*/>", "\n", xml)
    xml = re.sub(r"<w:tab[^>]*/>", "\t", xml)
    xml = re.sub(r"</w:p>", "\n", xml)
    text = re.sub(r"<[^>]+>", "", xml)
    for a, b in (("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
                 ("&quot;", '"'), ("&apos;", "'")):
        text = text.replace(a, b)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def build_design_doc(src_docx, out_path):
    text = docx_to_text(src_docx)
    doc = DocxBuilder(title="开屏跳过助手 设计说明书", author="开屏跳过助手")

    doc.add(para("", size=21))
    doc.add(heading("开屏跳过助手", 0))
    doc.add(para("V0.4.0", bold=True, size=28, ascii_font="黑体",
                 east_font="黑体", align="center", after=480))
    doc.add(para("设 计 说 明 书", bold=True, size=32, ascii_font="黑体",
                 east_font="黑体", align="center", after=720))
    for label in ("软件名称：", "版 本 号：", "著作权人：", "开发完成日期："):
        doc.add(para(f"{label}＿＿＿＿＿＿＿＿＿＿＿＿", size=24, align="center", after=200))
    doc.add(page_break())

    # 正文按原文排版：章节标题用 heading，其余按正文段落
    chapter = re.compile(r"^[一二三四五六七八九十]+、")
    for raw in text.split("\n"):
        line = raw.strip()
        if not line:
            continue
        if chapter.match(line) and len(line) < 30:
            doc.add(heading(line, 1))
        elif re.match(r"^\d+[、.]", line) and len(line) < 40:
            doc.add(heading(line, 2))
        elif line.startswith("•"):
            doc.add(para("　" + line, size=21, after=40))
        else:
            doc.add(body(line))

    doc.save(out_path)
    return out_path


APPLY_INFO = """# 软件著作权申请信息表（填写指引）

> 本表用于你在版权保护中心线上/线下登记时逐项填写。
> **标 ⚠️ 的项需要你自己决策或提供，我不能替你编造。**

---

## 一、软件基本信息

| 项目 | 建议填写内容 | 说明 |
|---|---|---|
| 软件全称 | 开屏跳过助手 | ⚠️ 需你确认最终名称。注意：**软件名称一旦登记不可随意更改**，且不要包含「中国」「国家」「最佳」「第一」等字样 |
| 软件简称 | SkipStart | 可留空；若有，全称与简称需能对应 |
| 版本号 | V1.0 | ⚠️ **重要决策**：软著登记一般用 V1.0 作为首个版本。当前工程 `versionName` 是 `0.4.0`，两者可以不一致（软著版本号与实际发布版本号无强制绑定），但**所有材料必须统一**。若你希望用 V0.4.0，就把所有材料中的 V1.0 一并改掉 |
| 开发完成日期 | ⚠️ 你填写 | 建议填一个确定的较早日期（如首次构建成功日），不能晚于申请日 |
| 首次发表日期 | 未发表 / 或填写实际发布日期 | 若尚未公开上架，选「未发表」最省事，可免去发表证明材料 |
| 开发方式 | 独立开发 | 若为合作/委托开发，需另附合同 |
| 权利取得方式 | 原始取得 | |
| 权利范围 | 全部权利 | |
| 软件分类 | 应用软件 → 手机应用软件 | |

---

## 二、著作权人信息（⚠️ 全部由你提供）

| 项目 | 填写内容 |
|---|---|
| 著作权人类型 | 自然人 / 法人（二选一） |
| 姓名或单位名称 | ⚠️ |
| 证件类型与号码 | ⚠️ 身份证号 / 统一社会信用代码 |
| 联系地址 | ⚠️ |
| 邮政编码 | ⚠️ |
| 联系人 / 电话 / 邮箱 | ⚠️ |
| 代理机构 | 无（自行申请）或填写代理机构名称 |

> 自然人申请需提供身份证复印件；法人申请需提供营业执照副本复印件（加盖公章）。

---

## 三、技术特征（可直接抄用）

| 项目 | 填写内容 |
|---|---|
| 开发硬件环境 | PC（x86_64），内存 16GB，硬盘 256GB 以上 |
| 运行硬件环境 | Android 手机，ARM 架构处理器，内存 2GB 及以上，存储空间 100MB 以上 |
| 开发操作系统 | Windows 10 / 11（64 位） |
| 运行操作系统 | Android 8.0（API 26）及以上 |
| 开发工具 | Android Studio、Gradle 8.9、JDK 17 |
| 开发语言 | Kotlin |
| 运行平台 | Android |
| 源程序量 | 约 5236 行（40 个源文件） |
| 软件用途 | 在用户授权并指定目标应用的前提下，自动点击目标应用开屏广告上的「跳过/关闭/✕」按钮，减少重复手动操作 |
| 主要功能 | 无障碍服务状态管理、规则驱动的自动跳过、学习模式生成规则、规则管理（含 JSON 导入导出）、日志记录与节点调试、多重防误触保护 |

---

## 四、需要提交的材料清单（一般要求）

| 序号 | 材料 | 份数 | 来源 |
|---|---|---|---|
| 1 | 软件著作权登记申请表 | 1 | 版权中心系统在线填写后打印 |
| 2 | 身份证明文件 | 1 | ⚠️ 你提供（身份证 / 营业执照） |
| 3 | 源程序文档 | 1 | 本资料 `02_源程序/源程序-提交版（前30页+后30页）.docx` |
| 4 | 软件说明书文档 | 1 | 本资料 `03_软件说明书/软件说明书.docx`（**需补截图**） |
| 5 | 设计说明书（可选补充） | — | 本资料 `03_软件说明书/设计说明书.docx` |
| 6 | 其他证明（如委托书） | 按需 | 非独立开发时才需要 |

> 具体要求以中国版权保护中心当期公告为准；线上提交流程与格式可能调整，
> 建议在提交前到官网核对最新要求。

---

## 五、提交前必须处理的 4 件事（⚠️ 重要）

### 1. 包名建议更换（强烈建议）

当前 Android 包名是 `com.example.skipstart`。`com.example.*` 是 Google 官方文档中
**专门用于示例代码的保留前缀**，在软著审查、应用市场上架时都可能被质疑「非正式产品」。
建议改为你自己的域名反写，例如 `com.liuqing.skipstart` 或 `cn.skipstart.app`。

**改包名会牵动**：`app/build.gradle.kts` 的 `applicationId` 与 `namespace`、
`AndroidManifest.xml` 中的组件声明、所有 Kotlin 文件的 `package` 声明与 import、
`accessibility_service_config.xml` 的引用、以及无障碍服务的组件名
（`AccessibilityUtils` 中的状态判定逻辑依赖组件名，改完要重新验证「服务是否已开启」）。
**这是一次有风险的改动，建议改完后完整跑一遍真机验收再申请。**

如果你决定保留 `com.example.skipstart`，也能提交，但被要求补正的概率更高。

### 2. 版本号统一

选定 V1.0 或 V0.4.0 之后，检查以下位置是否一致：
- 本资料三份 docx 的封面；
- 申请信息表的「版本号」；
- 线上申请表填写的版本号。

### 3. 补充界面截图（说明书必须）

`03_软件说明书/软件说明书.docx` 的第四章预留了 **16 处**「［此处粘贴截图］」图位，
每处下方标注了该图应该展示的内容。请安装 APK 后逐页截图并粘贴替换。

**截图注意**：
- 截图中不要出现个人隐私信息（通知栏的短信/微信预览、真实姓名、手机号等）；
- 建议把状态栏时间调成一个固定时间，整体更整洁；
- 学习模式与日志页的截图最好包含一次真实成功的记录，更有说服力。

### 4. 确认软件名称与功能表述的合规性

本软件依赖 Android 无障碍服务。在软著登记层面，无障碍服务属于系统公开 API，
正常申请没有问题；但**应用商店**对无障碍权限审核极严，登记成功后若打算上架，
需另行评估（无障碍类应用常被要求提供额外说明或被限制上架）。
这一点与软著申请本身无关，但提前告知你。

---

## 六、本资料目录结构

```
软著申请资料/
├── README-材料清单与填写指引.md            ← 本文件
├── 01_申请表格/
│   └── 申请信息表.md                        ← 需填写项的汇总
├── 02_源程序/
│   ├── 源程序-提交版（前30页+后30页）.docx   ← 提交这份（原文档 121 页，>60 页故取首尾）
│   └── 源程序-完整版.docx                   ← 备查，含全部 121 页
└── 03_软件说明书/
    ├── 软件说明书.docx                      ← 提交这份（需补 16 处截图）
    └── 设计说明书.docx                      ← 可选补充材料
```

---

## 七、文档格式说明（已按软著形式要求排版）

| 要求 | 本资料的实现 |
|---|---|
| 每页不少于 50 行（末页除外） | 源程序按 **50 行/页**排版，已逐页校验；末页 24 行（规定允许） |
| 页码连续、无空页 | 页码用页脚 **PAGE 域**自动生成，不占正文行数；提交版为原文档的第 1–30 页与第 92–121 页 |
| 页眉标注软件名称与版本号 | 页眉内容为「开屏跳过助手 V0.4.0」，字体 **五号（10.5pt）**，满足"不小于五号"的要求 |
| 字体 | 正文宋体 10.5pt / 1.5 倍行距；源码 Courier New 9pt 等宽；标题黑体 |
| 页面 | A4，页边距上下 2.54cm、左右 2.5cm |
| 源程序规模 | 40 个文件、5236 行实际代码（含文件分隔行共 6024 行） |

### 如何重新生成

材料由脚本从**项目真实源码与文档**生成，改代码后重新生成即可保持一致：

```bat
python tools\gen_copyright_source_doc.py    :: 源程序（完整版 + 提交版）
python tools\gen_copyright_manual_doc.py    :: 软件说明书（含截图图位）
python tools\gen_copyright_extras.py        :: 设计说明书 + 本清单
python tools\verify_docx.py                 :: 校验 docx 结构是否合法（能否被 Word 打开）
```

> ⚠️ 一旦你在 Word 里手动补了截图，**不要再运行生成脚本**，否则会覆盖你的修改。
> 建议：先补截图并另存为「软件说明书-已补图.docx」，保留生成的原始文件作为备份。

---

## 八、免责说明

以上内容是基于本项目实际情况整理的**材料准备指引**，不构成法律意见。
软著审查标准与提交要求以中国版权保护中心的最新公告为准；
材料中的「著作权人」「日期」等信息必须由你本人据实填写，本资料刻意留空，
未替你编造任何个人信息或时间。
"""


def main():
    # 1) 设计说明书
    src = os.path.join(ROOT, "排版版 Word 文档（.docx）.docx")
    design_out = os.path.join(OUT_DIR, "03_软件说明书", "设计说明书.docx")
    if os.path.isfile(src):
        build_design_doc(src, design_out)
        print(f"已生成: {design_out}")
    else:
        print(f"未找到源文档，跳过设计说明书: {src}")

    # 2) 申请信息表 / 材料清单
    os.makedirs(os.path.join(OUT_DIR, "01_申请表格"), exist_ok=True)
    info_out = os.path.join(OUT_DIR, "01_申请表格", "申请信息表.md")
    with open(info_out, "w", encoding="utf-8") as f:
        f.write(APPLY_INFO)
    print(f"已生成: {info_out}")

    readme_out = os.path.join(OUT_DIR, "README-材料清单与填写指引.md")
    with open(readme_out, "w", encoding="utf-8") as f:
        f.write(APPLY_INFO)
    print(f"已生成: {readme_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
