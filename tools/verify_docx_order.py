"""校验 md_to_docx.py 产出的 DOCX：包结构、XML 合法性、以及 w:pPr 子元素顺序。

为什么单独查顺序：OOXML 的 w:pPr / w:tcPr 子元素有强制顺序，w:shd 放错位置时
Word 可能直接报"内容有问题"或静默忽略底色。python-docx 不检查这一点，所以自己查。

用法：<bundled python> tools/verify_docx_order.py <docx路径>
"""

from __future__ import annotations

import sys
import zipfile

from docx import Document
from docx.oxml.ns import qn

PPR_ORDER = [
    "pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr",
    "suppressLineNumbers", "pBdr", "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap",
    "overflowPunct", "topLinePunct", "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd",
    "snapToGrid", "spacing", "ind", "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc",
    "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr",
    "sectPr", "pPrChange",
]
TCPR_ORDER = [
    "cnfStyle", "tcW", "gridSpan", "hMerge", "vMerge", "tcBorders", "shd", "noWrap", "tcMar",
    "textDirection", "tcFitText", "vAlign", "hideMark",
]


def check_order(pr, order: list[str], where: str, problems: list[str]) -> None:
    rank = {name: i for i, name in enumerate(order)}
    seen = -1
    for child in pr:
        tag = child.tag.split("}")[-1]
        if tag not in rank:
            continue
        if rank[tag] < seen:
            problems.append(f"{where}: 子元素顺序错误 {[c.tag.split('}')[-1] for c in pr]}")
            return
        seen = rank[tag]


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: verify_docx_order.py <docx>", file=sys.stderr)
        return 2
    path = sys.argv[1]
    problems: list[str] = []

    with zipfile.ZipFile(path) as zf:
        names = zf.namelist()
        for required in ("word/document.xml", "[Content_Types].xml"):
            if required not in names:
                problems.append(f"缺少包成员 {required}")
        # 每个成员都必须能独立解析，且不能重名（重名会损坏包）
        if len(names) != len(set(names)):
            problems.append("包成员重名")
        for name in names:
            if name.endswith(".xml") or name.endswith(".rels"):
                import xml.dom.minidom as minidom

                try:
                    minidom.parseString(zf.read(name))
                except Exception as exc:  # noqa: BLE001
                    problems.append(f"{name} XML 非法: {exc}")

    doc = Document(path)
    for para in doc.paragraphs:
        ppr = para._p.find(qn("w:pPr"))
        if ppr is not None:
            check_order(ppr, PPR_ORDER, "paragraph", problems)
    for table in doc.tables:
        for row in table.rows:
            for cell in row.cells:
                tcpr = cell._tc.find(qn("w:tcPr"))
                if tcpr is not None:
                    check_order(tcpr, TCPR_ORDER, "tableCell", problems)

    shaded = sum(
        1 for p in doc.paragraphs
        if p._p.find(qn("w:pPr")) is not None
        and p._p.find(qn("w:pPr")).find(qn("w:shd")) is not None
    )
    print(f"paragraphs={len(doc.paragraphs)} tables={len(doc.tables)} "
          f"headings={sum(1 for p in doc.paragraphs if p.style.name.startswith('Heading'))} "
          f"shaded_paragraphs={shaded}")
    if problems:
        print(f"PROBLEMS ({len(problems)}):")
        for item in problems[:20]:
            print("  -", item)
        return 1
    print("OK: 包结构完整、XML 全部合法、w:pPr / w:tcPr 子元素顺序合规")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
