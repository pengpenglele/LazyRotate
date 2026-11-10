"""把主文档 Markdown 转成排版良好的 Word 文档。

用法：
    <bundled python> tools/md_to_docx.py

说明：
  - 只处理主文档里实际用到的 Markdown 子集：标题、段落、**粗体**、`行内代码`、
    管道表格、围栏代码块（含 mermaid 源码与 ASCII 界面示意图）、引用块、有序/无序列表。
  - 刻意设置 w:eastAsia 字体：python-docx 只设 run.font.name 对中文不生效。
  - 这不是通用转换器，是一份"针对本项目主文档"的可复现工具，保留在仓库里。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

DOCS = Path(__file__).resolve().parent.parent / "docs"

SRC = DOCS / "屏幕方向控制App-需求与技术方案.md"
OUT = DOCS / "屏幕方向控制App-需求与技术方案.docx"

CJK = "微软雅黑"
MONO = "Consolas"
MONO_CJK = "微软雅黑"
BODY_PT = 10.5
TABLE_PT = 8.5
CODE_PT = 8.0


def set_run_font(run, ascii_font: str, cjk_font: str, size_pt: float | None = None,
                 bold: bool | None = None, italic: bool | None = None,
                 color: RGBColor | None = None) -> None:
    run.font.name = ascii_font
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = OxmlElement("w:rFonts")
        rpr.append(rfonts)
    rfonts.set(qn("w:ascii"), ascii_font)
    rfonts.set(qn("w:hAnsi"), ascii_font)
    rfonts.set(qn("w:eastAsia"), cjk_font)
    if size_pt is not None:
        run.font.size = Pt(size_pt)
    if bold is not None:
        run.font.bold = bold
    if italic is not None:
        run.font.italic = italic
    if color is not None:
        run.font.color.rgb = color


# w:pPr 的子元素有强制顺序，w:shd 必须排在下面这些之前。
# 直接 append 会写出乱序的 XML（Word 可能打不开或静默忽略），所以按顺序插入。
PPR_AFTER_SHD = {
    "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct", "topLinePunct",
    "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind",
    "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc", "textDirection",
    "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr",
    "pPrChange",
}
# w:tcPr 同理：w:shd 排在 tcW/gridSpan/tcBorders 之后、noWrap/vAlign 之前。
TCPR_AFTER_SHD = {"noWrap", "tcMar", "textDirection", "tcFitText", "vAlign", "hideMark"}


def _new_shd(fill: str):
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), fill)
    return shd


def _ordered_insert(pr, child, after_tags: set[str]) -> None:
    for existing in pr:
        if existing.tag.split("}")[-1] in after_tags:
            existing.addprevious(child)
            return
    pr.append(child)


def shade_paragraph(paragraph, fill: str) -> None:
    _ordered_insert(paragraph._p.get_or_add_pPr(), _new_shd(fill), PPR_AFTER_SHD)


def shade_cell(cell, fill: str) -> None:
    _ordered_insert(cell._tc.get_or_add_tcPr(), _new_shd(fill), TCPR_AFTER_SHD)


INLINE_RE = re.compile(r"(\*\*.+?\*\*|`[^`]+`)")


def add_inline(paragraph, text: str, *, base_pt: float = BODY_PT,
               base_bold: bool = False, base_color: RGBColor | None = None) -> None:
    """把 **粗体** 与 `行内代码` 渲染成不同 run。"""
    for chunk in INLINE_RE.split(text):
        if not chunk:
            continue
        if chunk.startswith("**") and chunk.endswith("**") and len(chunk) > 4:
            run = paragraph.add_run(chunk[2:-2])
            set_run_font(run, CJK, CJK, base_pt, True, None, base_color)
        elif chunk.startswith("`") and chunk.endswith("`") and len(chunk) > 2:
            run = paragraph.add_run(chunk[1:-1])
            set_run_font(run, MONO, MONO_CJK, base_pt - 0.5, base_bold, None,
                         RGBColor(0xA0, 0x30, 0x30))
        else:
            run = paragraph.add_run(chunk)
            set_run_font(run, CJK, CJK, base_pt, base_bold, None, base_color)


def split_row(line: str) -> list[str]:
    line = line.strip()
    if line.startswith("|"):
        line = line[1:]
    if line.endswith("|"):
        line = line[:-1]
    return [c.strip() for c in line.split("|")]


def is_separator_row(cells: list[str]) -> bool:
    return bool(cells) and all(re.fullmatch(r":?-{2,}:?", c) for c in cells)


def add_table(document: Document, rows: list[list[str]]) -> None:
    if not rows:
        return
    header, body = rows[0], rows[1:]
    table = document.add_table(rows=1, cols=len(header))
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = True

    for idx, cell_text in enumerate(header):
        cell = table.rows[0].cells[idx]
        cell.text = ""
        para = cell.paragraphs[0]
        add_inline(para, cell_text, base_pt=TABLE_PT, base_bold=True)
        shade_cell(cell, "E8EEF7")

    for row in body:
        cells = table.add_row().cells
        for idx in range(len(header)):
            value = row[idx] if idx < len(row) else ""
            cells[idx].text = ""
            add_inline(cells[idx].paragraphs[0], value, base_pt=TABLE_PT)

    document.add_paragraph()


def add_code_block(document: Document, lines: list[str], language: str) -> None:
    if language == "mermaid":
        caption = document.add_paragraph()
        add_inline(caption, "（上图为 Mermaid 源码；完整可渲染版本见 Markdown 原文档）",
                   base_pt=BODY_PT - 1.5, base_color=RGBColor(0x60, 0x60, 0x60))
    para = document.add_paragraph()
    para.paragraph_format.space_after = Pt(6)
    para.paragraph_format.left_indent = Cm(0.3)
    para.paragraph_format.line_spacing = 1.0
    shade_paragraph(para, "F4F4F4")
    for i, line in enumerate(lines):
        if i:
            para.add_run().add_break()
        run = para.add_run(line)
        set_run_font(run, MONO, MONO_CJK, CODE_PT)
    document.add_paragraph()


def main() -> int:
    if not SRC.exists():
        print(f"SOURCE MISSING: {SRC}", file=sys.stderr)
        return 2
    text = SRC.read_text(encoding="utf-8")

    document = Document()

    # 页面：A4 + 窄边距（本文件表格列多，窄边距才放得下）
    section = document.sections[0]
    section.page_width = Cm(21.0)
    section.page_height = Cm(29.7)
    for attr in ("top_margin", "bottom_margin", "left_margin", "right_margin"):
        setattr(section, attr, Cm(1.5))

    # 全局字体（含中文 eastAsia），否则中文会回退成默认字体
    normal = document.styles["Normal"]
    normal.font.name = CJK
    normal.font.size = Pt(BODY_PT)
    normal_rpr = normal.element.get_or_add_rPr()
    normal_rfonts = normal_rpr.find(qn("w:rFonts"))
    if normal_rfonts is None:
        normal_rfonts = OxmlElement("w:rFonts")
        normal_rpr.append(normal_rfonts)
    normal_rfonts.set(qn("w:ascii"), CJK)
    normal_rfonts.set(qn("w:hAnsi"), CJK)
    normal_rfonts.set(qn("w:eastAsia"), CJK)
    for name in ("Heading 1", "Heading 2", "Heading 3", "Heading 4", "Title"):
        try:
            style = document.styles[name]
        except KeyError:
            continue
        style.font.name = CJK
        rpr = style.element.get_or_add_rPr()
        rfonts = rpr.find(qn("w:rFonts"))
        if rfonts is None:
            rfonts = OxmlElement("w:rFonts")
            rpr.append(rfonts)
        rfonts.set(qn("w:eastAsia"), CJK)

    lines = text.splitlines()
    i = 0
    table_count = block_count = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if stripped.startswith("```"):
            language = stripped[3:].strip()
            i += 1
            buf = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                buf.append(lines[i])
                i += 1
            i += 1
            add_code_block(document, buf, language)
            block_count += 1
            continue

        if stripped.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                cells = split_row(lines[i])
                if not is_separator_row(cells):
                    rows.append(cells)
                i += 1
            add_table(document, rows)
            table_count += 1
            continue

        if stripped.startswith("#"):
            level = len(stripped) - len(stripped.lstrip("#"))
            title = stripped[level:].strip()
            heading = document.add_heading("", level=min(level, 4))
            add_inline(heading, title, base_pt={1: 20, 2: 15, 3: 12.5, 4: 11}.get(level, 11),
                       base_bold=True, base_color=RGBColor(0x1A, 0x1A, 0x1A))
            i += 1
            continue

        if stripped.startswith("> "):
            para = document.add_paragraph()
            para.paragraph_format.left_indent = Cm(0.75)
            add_inline(para, stripped[2:], base_pt=BODY_PT - 0.5,
                       base_color=RGBColor(0x44, 0x44, 0x44))
            i += 1
            continue

        if re.match(r"^[-*] ", stripped):
            para = document.add_paragraph(style="List Bullet")
            add_inline(para, stripped[2:])
            i += 1
            continue

        if re.match(r"^\d+\. ", stripped):
            para = document.add_paragraph(style="List Number")
            add_inline(para, re.sub(r"^\d+\. ", "", stripped))
            i += 1
            continue

        if stripped in ("---", "***", "___"):
            para = document.add_paragraph()
            run = para.add_run("─" * 40)
            set_run_font(run, CJK, CJK, 8, False, None, RGBColor(0xBB, 0xBB, 0xBB))
            para.alignment = WD_ALIGN_PARAGRAPH.CENTER
            i += 1
            continue

        if not stripped:
            i += 1
            continue

        para = document.add_paragraph()
        para.paragraph_format.space_after = Pt(4)
        add_inline(para, stripped)
        i += 1

    document.save(OUT)

    # 按技能要求：重新打开、核对内容与结构，而不是只看"保存成功"
    check = Document(OUT)
    headings = sum(1 for p in check.paragraphs if p.style.name.startswith("Heading"))
    chars = sum(len(p.text) for p in check.paragraphs)
    table_cells = sum(len(t.rows) * len(t.columns) for t in check.tables)
    print(f"SAVED {OUT}")
    print(f"  size={OUT.stat().st_size} bytes")
    print(f"  paragraphs={len(check.paragraphs)} headings={headings} "
          f"tables={len(check.tables)} table_cells={table_cells} inline_chars={chars}")
    print(f"  源文件行数={len(lines)}，代码/示意图块={block_count}，表格={table_count}")
    if len(check.tables) != table_count:
        print("  WARN: 表格数量不一致", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
