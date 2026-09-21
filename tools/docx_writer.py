#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
极简 OOXML(.docx) 生成器 —— 软著资料导出用。

为什么自己写而不用 python-docx：本机离线且未安装 python-docx，
而软著提交必须是 .docx / .pdf，纯文本或 Markdown 不行。

只实现软著材料需要的子集：
  - 页面设置（A4 / 页边距 / 页脚页码域）
  - 标题与正文段落（中文字体 + 西文字体分别指定）
  - 等宽字体代码段（源码清单用）
  - 首行缩进 / 行距 / 段前段后

生成的文件能被 Microsoft Word / WPS / LibreOffice 正常打开。
"""

import os
import zipfile

# ---------- XML 基础 ----------

XML_HEAD = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'

CONTENT_TYPES = XML_HEAD + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>
<Override PartName="/word/fontTable.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.fontTable+xml"/>
<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>"""

RELS = XML_HEAD + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""

DOC_RELS = XML_HEAD + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>
<Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/fontTable" Target="fontTable.xml"/>
<Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
</Relationships>"""

# 文档级设置：显式声明默认制表位与兼容性，避免 Word 用默认值时的排版差异
SETTINGS = XML_HEAD + """<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:zoom w:percent="100"/>
<w:defaultTabStop w:val="420"/>
<w:characterSpacingControl w:val="compressPunctuation"/>
<w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat>
</w:settings>"""

# 字体表：声明材料中用到的中英文字体，避免在缺少字体的机器上回退成方框
FONT_TABLE = XML_HEAD + """<w:fonts xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:font w:name="宋体"><w:charset w:val="86"/><w:family w:val="auto"/><w:pitch w:val="variable"/></w:font>
<w:font w:name="黑体"><w:charset w:val="86"/><w:family w:val="auto"/><w:pitch w:val="variable"/></w:font>
<w:font w:name="Courier New"><w:charset w:val="00"/><w:family w:val="modern"/><w:pitch w:val="fixed"/></w:font>
<w:font w:name="Times New Roman"><w:charset w:val="00"/><w:family w:val="roman"/><w:pitch w:val="variable"/></w:font>
</w:fonts>"""

# 页眉：左侧软件名称 + 版本，右侧页码（软著要求页眉标注软件名称与版本号，字体不小于五号=21半磅）
def _header_xml(left_text):
    return XML_HEAD + f"""<w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:p><w:pPr><w:tabs><w:tab w:val="right" w:pos="9070"/></w:tabs>
<w:spacing w:line="240" w:lineRule="auto" w:after="0"/>
<w:rPr><w:sz w:val="21"/></w:rPr></w:pPr>
<w:r><w:rPr><w:rFonts w:ascii="宋体" w:hAnsi="宋体" w:eastAsia="宋体"/><w:sz w:val="21"/></w:rPr>
<w:t xml:space="preserve">{esc(left_text)}</w:t></w:r>
<w:r><w:rPr><w:sz w:val="21"/></w:rPr><w:tab/></w:r>
<w:r><w:rPr><w:sz w:val="21"/></w:rPr><w:t xml:space="preserve">第 </w:t></w:r>
<w:r><w:rPr><w:sz w:val="21"/></w:rPr><w:fldChar w:fldCharType="begin"/></w:r>
<w:r><w:rPr><w:sz w:val="21"/></w:rPr><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>
<w:r><w:rPr><w:sz w:val="21"/></w:rPr><w:fldChar w:fldCharType="separate"/></w:r>
<w:r><w:rPr><w:sz w:val="21"/></w:rPr><w:t>1</w:t></w:r>
<w:r><w:rPr><w:sz w:val="21"/></w:rPr><w:fldChar w:fldCharType="end"/></w:r>
<w:r><w:rPr><w:sz w:val="21"/></w:rPr><w:t xml:space="preserve"> 页</w:t></w:r>
</w:p></w:hdr>"""


# 页脚：居中页码域（PAGE）
FOOTER = XML_HEAD + """<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:p><w:pPr><w:jc w:val="center"/><w:rPr><w:sz w:val="18"/></w:rPr></w:pPr>
<w:r><w:rPr><w:sz w:val="18"/></w:rPr><w:fldChar w:fldCharType="begin"/></w:r>
<w:r><w:rPr><w:sz w:val="18"/></w:rPr><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>
<w:r><w:rPr><w:sz w:val="18"/></w:rPr><w:fldChar w:fldCharType="separate"/></w:r>
<w:r><w:rPr><w:sz w:val="18"/></w:rPr><w:t>1</w:t></w:r>
<w:r><w:rPr><w:sz w:val="18"/></w:rPr><w:fldChar w:fldCharType="end"/></w:r>
</w:p></w:ftr>"""

STYLES = XML_HEAD + """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr>
<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="宋体"/>
<w:sz w:val="21"/><w:szCs w:val="21"/>
</w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:line="360" w:lineRule="auto"/></w:pPr></w:pPrDefault>
</w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
</w:styles>"""

CORE = XML_HEAD + """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
 xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/"
 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:title>{title}</dc:title><dc:creator>{author}</dc:creator>
<cp:lastModifiedBy>{author}</cp:lastModifiedBy>
<dcterms:created xsi:type="dcterms:W3CDTF">{date}</dcterms:created>
<dcterms:modified xsi:type="dcterms:W3CDTF">{date}</dcterms:modified>
</cp:coreProperties>"""

APP = XML_HEAD + """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
<Application>SkipStart DocxWriter</Application></Properties>"""


def esc(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


# ---------- 段落构造 ----------

def para(text="", *, bold=False, size=None, ascii_font=None, east_font=None,
         align=None, indent_first=None, line=None, before=None, after=None,
         page_break_before=False, keep_next=False):
    """构造一个 w:p 段落。size 单位为半磅（21 = 10.5pt）。"""
    ppr = []
    if page_break_before:
        ppr.append('<w:pageBreakBefore/>')
    if keep_next:
        ppr.append('<w:keepNext/>')
    if align:
        ppr.append(f'<w:jc w:val="{align}"/>')
    spacing = []
    if line:
        spacing.append(f'w:line="{line}" w:lineRule="auto"')
    if before is not None:
        spacing.append(f'w:before="{before}"')
    if after is not None:
        spacing.append(f'w:after="{after}"')
    if spacing:
        ppr.append(f'<w:spacing {" ".join(spacing)}/>')
    if indent_first:
        ppr.append(f'<w:ind w:firstLine="{indent_first}"/>')
    ppr_xml = f"<w:pPr>{''.join(ppr)}</w:pPr>" if ppr else ""

    rpr = []
    if ascii_font or east_font:
        rpr.append(
            f'<w:rFonts w:ascii="{ascii_font or "Times New Roman"}" '
            f'w:hAnsi="{ascii_font or "Times New Roman"}" '
            f'w:eastAsia="{east_font or "宋体"}"/>'
        )
    if bold:
        rpr.append("<w:b/><w:bCs/>")
    if size:
        rpr.append(f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/>')
    rpr_xml = f"<w:rPr>{''.join(rpr)}</w:rPr>" if rpr else ""

    # 保留前导空格（源码清单依赖）
    run = ""
    if text:
        run = (f'<w:r>{rpr_xml}<w:t xml:space="preserve">{esc(text)}</w:t></w:r>')
    return f"<w:p>{ppr_xml}{run}</w:p>"


def heading(text, level=1):
    """标题（软著材料里用加粗+字号区分层级，不依赖内置 Heading 样式）。"""
    if level == 0:
        return para(text, bold=True, size=36, ascii_font="黑体", east_font="黑体",
                    align="center", before=0, after=240, line=360)
    if level == 1:
        return para(text, bold=True, size=28, ascii_font="黑体", east_font="黑体",
                    before=240, after=120, keep_next=True)
    return para(text, bold=True, size=24, ascii_font="黑体", east_font="黑体",
                before=180, after=100, keep_next=True)


def body(text):
    """正文段落：宋体 10.5pt、1.5 倍行距、首行缩进 2 字符（420 twips）。"""
    return para(text, size=21, indent_first=420)


def code(text, size=18):
    """代码行：等宽字体、单倍行距、不缩进。size 18 = 9pt。"""
    return para(text, size=size, ascii_font="Courier New", east_font="宋体",
                line=240, before=0, after=0)


def page_break():
    return para("", page_break_before=True)


# ---------- 文档写出 ----------

class DocxBuilder:
    def __init__(self, title="", author="", date="2026-01-01T00:00:00Z", header_text=""):
        self.title = esc(title)
        self.author = esc(author)
        self.date = date
        self.header_text = header_text
        self._parts = []

    def add(self, xml_fragment):
        self._parts.append(xml_fragment)
        return self

    def extend(self, fragments):
        self._parts.extend(fragments)
        return self

    def _document_xml(self):
        # A4: 11906 x 16838 twips；页边距 上/下 1440、左/右 1418（约 2.5cm）
        sect = (
            '<w:sectPr>'
            '<w:headerReference w:type="default" r:id="rId5"/>'
            '<w:footerReference w:type="default" r:id="rId2"/>'
            '<w:pgSz w:w="11906" w:h="16838"/>'
            '<w:pgMar w:top="1440" w:right="1418" w:bottom="1440" w:left="1418" '
            'w:header="851" w:footer="992" w:gutter="0"/>'
            '</w:sectPr>'
        )
        return (XML_HEAD +
                '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
                'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                "<w:body>" + "".join(self._parts) + sect + "</w:body></w:document>")

    def save(self, path):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        core = CORE.format(title=self.title, author=self.author, date=self.date)
        header = _header_xml(self.header_text) if self.header_text else _header_xml(self.title)
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("[Content_Types].xml", CONTENT_TYPES)
            z.writestr("_rels/.rels", RELS)
            z.writestr("word/document.xml", self._document_xml())
            z.writestr("word/_rels/document.xml.rels", DOC_RELS)
            z.writestr("word/styles.xml", STYLES)
            z.writestr("word/settings.xml", SETTINGS)
            z.writestr("word/fontTable.xml", FONT_TABLE)
            z.writestr("word/header1.xml", header)
            z.writestr("word/footer1.xml", FOOTER)
            z.writestr("docProps/core.xml", core)
            z.writestr("docProps/app.xml", APP)
        return path
