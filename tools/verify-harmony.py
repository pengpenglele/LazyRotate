#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HarmonyOS 工程静态校验（无 DevEco/SDK 环境下的自检脚本）。

检查项：
  1. JSON5 配置可解析（AppScope/app.json5、module.json5、build-profile、oh-package、hvigor-config、profile）
  2. 资源引用存在（$string/$color/$media/$profile）
  3. 入口 Ability / 页面路由 / 源文件路径存在
  4. .ets 相对 import 可解析
  5. 外部 Want action 常量与 module.json5 skills 一致
  6. 花括号/圆括号/方括号配对（跳过字符串与注释）
  7. ArkTS 禁忌扫描（any、@ts-ignore、http(s) 网络引用、系统接口 screen.* 出现在默认构建）
  8. 权限最小化（module.json5 不申请 requestPermissions）

用法：
  python tools/verify-harmony.py
退出码 0 = 全部通过；1 = 有失败项。
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / "harmony" / "LazyRotate"
ENTRY = PROJECT / "entry" / "src" / "main"
FAILS: list[str] = []
PASSES: list[str] = []
WARNS: list[str] = []


def ok(msg: str) -> None:
    PASSES.append(msg)


def fail(msg: str) -> None:
    FAILS.append(msg)


def warn(msg: str) -> None:
    WARNS.append(msg)


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def parse_json5(path: Path):
    """极简 JSON5 -> JSON：去注释、去尾逗号。本工程只用到这两类扩展。"""
    text = read_text(path)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"(^|[^:])//.*$", r"\1", text, flags=re.M)
    text = re.sub(r",\s*([}\]])", r"\1", text)
    return json.loads(text)


def check_json5_files() -> None:
    files = [
        PROJECT / "AppScope" / "app.json5",
        PROJECT / "build-profile.json5",
        PROJECT / "oh-package.json5",
        PROJECT / "hvigor" / "hvigor-config.json5",
        PROJECT / "entry" / "build-profile.json5",
        PROJECT / "entry" / "oh-package.json5",
        ENTRY / "module.json5",
        ENTRY / "resources" / "base" / "profile" / "main_pages.json",
    ]
    for path in files:
        if not path.exists():
            fail(f"缺少配置文件：{path.relative_to(ROOT)}")
            continue
        try:
            parse_json5(path)
            ok(f"JSON5 可解析：{path.relative_to(ROOT)}")
        except Exception as exc:  # noqa: BLE001
            fail(f"JSON5 解析失败：{path.relative_to(ROOT)} -> {exc}")


def load_strings(path: Path) -> set[str]:
    data = json.loads(read_text(path))
    return {item["name"] for item in data.get("string", [])}


def check_resources() -> None:
    app = parse_json5(PROJECT / "AppScope" / "app.json5")["app"]
    app_strings = load_strings(PROJECT / "AppScope" / "resources" / "base" / "element" / "string.json")

    def require_ref(ref: str, kind: str, names: set[str], where: str) -> None:
        prefix = f"${kind}:"
        if not ref.startswith(prefix):
            fail(f"{where} 的引用格式不正确：{ref}")
            return
        name = ref[len(prefix):]
        if name not in names:
            fail(f"{where} 引用了不存在的 {kind} 资源：{name}")

    require_ref(app["icon"], "media", {"app_icon"}, "AppScope/app.json5 icon")
    require_ref(app["label"], "string", app_strings, "AppScope/app.json5 label")

    entry_strings = load_strings(ENTRY / "resources" / "base" / "element" / "string.json")
    color_data = json.loads(read_text(ENTRY / "resources" / "base" / "element" / "color.json"))
    color_names = {item["name"] for item in color_data.get("color", [])}
    media_dir = ENTRY / "resources" / "base" / "media"
    media_names = {p.stem for p in media_dir.glob("*.png")} | {p.stem for p in media_dir.glob("*.svg")}

    module = parse_json5(ENTRY / "module.json5")["module"]
    require_ref(module["description"], "string", entry_strings, "module.json5 description")
    require_ref(module["pages"], "profile", {"main_pages"}, "module.json5 pages")

    for ability in module.get("abilities", []):
        where = f"module.json5 abilities[{ability['name']}]"
        require_ref(ability.get("description", ""), "string", entry_strings, where + " description")
        require_ref(ability.get("label", ""), "string", entry_strings, where + " label")
        require_ref(ability.get("icon", ""), "media", media_names, where + " icon")
        require_ref(ability.get("startWindowIcon", ""), "media", media_names, where + " startWindowIcon")
        require_ref(ability.get("startWindowBackground", ""), "color", color_names, where + " startWindowBackground")

    if app["icon"] == "$media:app_icon" and not (PROJECT / "AppScope" / "resources" / "base" / "media" / "app_icon.png").exists():
        fail("AppScope 图标 app_icon.png 不存在")

    if entry_strings:
        ok(f"资源引用检查通过（string {len(entry_strings)} / color {len(color_names)} / media {len(media_names)}）")


def check_entries() -> None:
    module = parse_json5(ENTRY / "module.json5")["module"]
    for ability in module.get("abilities", []):
        src = ENTRY / ability["srcEntry"].lstrip("./")
        if not src.exists():
            fail(f"Ability 源文件不存在：{ability['srcEntry']}")
        else:
            ok(f"Ability 源文件存在：{ability['srcEntry']}")

    pages = json.loads(read_text(ENTRY / "resources" / "base" / "profile" / "main_pages.json"))["src"]
    for page in pages:
        page_file = ENTRY / "ets" / (page + ".ets")
        if not page_file.exists():
            fail(f"页面路由指向的文件不存在：{page}.ets")
        else:
            text = read_text(page_file)
            if "@Entry" not in text:
                fail(f"页面缺少 @Entry：{page}.ets")
            if "build()" not in text:
                fail(f"页面缺少 build()：{page}.ets")
            else:
                ok(f"页面入口正常：{page}.ets")

    # 外部指令 action 与源码常量一致性
    command_src = read_text(ENTRY / "ets" / "core" / "RotationCommand.ets")
    actions = set(re.findall(r"com\.lazyrotate\.app\.action\.[A-Z_]+", command_src))
    module_actions = set(re.findall(r"com\.lazyrotate\.app\.action\.[A-Z_]+", read_text(ENTRY / "module.json5")))
    if not actions:
        fail("RotationCommand.ets 中未找到 action 常量")
    else:
        missing = actions - module_actions
        if missing:
            fail(f"module.json5 skills 缺少 action：{sorted(missing)}")
        else:
            ok(f"外部指令 action 一致（{len(actions)} 个）")


def check_imports() -> None:
    count = 0
    for path in sorted((ENTRY / "ets").rglob("*.ets")):
        text = read_text(path)
        for match in re.finditer(r"from\s+['\"]([^'\"]+)['\"]", text):
            spec = match.group(1)
            count += 1
            if spec.startswith("."):
                base = (path.parent / spec).resolve()
                candidates = [base.with_suffix(".ets"), Path(str(base) + ".ets"), base / "index.ets"]
                if not any(c.exists() for c in candidates):
                    fail(f"{path.relative_to(ROOT)} 引用的相对模块不存在：{spec}")
            elif not (spec.startswith("@kit.") or spec.startswith("@ohos.")):
                warn(f"{path.relative_to(ROOT)} 使用了非系统 import：{spec}")
    ok(f"相对 import 检查完成（共 {count} 条 import）")


def check_balance() -> None:
    pairs = {"{": "}", "(": ")", "[": "]"}
    for path in sorted((ENTRY / "ets").rglob("*.ets")):
        text = read_text(path)
        stack: list[str] = []
        i = 0
        state = "code"  # code | line_comment | block_comment | single | double | template
        while i < len(text):
            ch = text[i]
            nxt = text[i + 1] if i + 1 < len(text) else ""
            if state == "code":
                if ch == "/" and nxt == "/":
                    state = "line_comment"
                    i += 2
                    continue
                if ch == "/" and nxt == "*":
                    state = "block_comment"
                    i += 2
                    continue
                if ch in ("'", '"', "`"):
                    state = {"'": "single", '"': "double", "`": "template"}[ch]
                    i += 1
                    continue
                if ch in pairs:
                    stack.append(pairs[ch])
                elif ch in ")]}":
                    if not stack or stack.pop() != ch:
                        fail(f"括号不配对：{path.relative_to(ROOT)} 位置 {i}")
                        break
                i += 1
                continue
            if state == "line_comment":
                if ch == "\n":
                    state = "code"
                i += 1
                continue
            if state == "block_comment":
                if ch == "*" and nxt == "/":
                    state = "code"
                    i += 2
                else:
                    i += 1
                continue
            # 字符串内部：处理转义与结束
            if ch == "\\":
                i += 2
                continue
            if (state == "single" and ch == "'") or (state == "double" and ch == '"') or (state == "template" and ch == "`"):
                state = "code"
            i += 1
        else:
            if stack:
                fail(f"括号未闭合：{path.relative_to(ROOT)}（剩余 {len(stack)} 个）")
            continue
        break
    ok("括号配对检查完成")


def strip_literals(text: str) -> str:
    """把字符串/注释内容替换为空格，避免误报（例如 UI 文案里提到系统接口名）。"""
    out: list[str] = []
    i = 0
    state = "code"
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                state = "line_comment"
                out.extend("  ")
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block_comment"
                out.extend("  ")
                i += 2
                continue
            if ch in ("'", '"', "`"):
                state = {"'": "single", '"': "double", "`": "template"}[ch]
                out.append(" ")
                i += 1
                continue
            out.append(ch)
            i += 1
            continue
        if state == "line_comment":
            if ch == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
            continue
        if state == "block_comment":
            if ch == "*" and nxt == "/":
                state = "code"
                out.extend("  ")
                i += 2
            else:
                out.append("\n" if ch == "\n" else " ")
                i += 1
            continue
        if ch == "\\":
            out.extend("  ")
            i += 2
            continue
        if (state == "single" and ch == "'") or (state == "double" and ch == '"') or (state == "template" and ch == "`"):
            state = "code"
        out.append(" ")
        i += 1
    return "".join(out)


def check_arkts_hygiene() -> None:
    forbidden = {
        r":\s*any\b": "使用 any（ArkTS 禁止）",
        r"@ts-ignore": "@ts-ignore（ArkTS 禁止）",
        r"https?://": "源码中出现网络地址（本应用不应联网）",
        r"\bscreen\.(setScreenRotationLocked|isScreenRotationLocked|getAllScreens)\b": "默认构建调用了系统接口 screen.*",
    }
    for path in sorted((ENTRY / "ets").rglob("*.ets")):
        code_only = strip_literals(read_text(path))
        for pattern, label in forbidden.items():
            if re.search(pattern, code_only):
                fail(f"{path.relative_to(ROOT)}：{label}")
    ok("ArkTS 卫生检查完成（any/@ts-ignore/网络/系统接口）")

    module = parse_json5(ENTRY / "module.json5")["module"]
    perms = module.get("requestPermissions", [])
    if perms:
        fail(f"module.json5 声明了权限（本版应为空）：{perms}")
    else:
        ok("权限最小化：未申请任何权限")


def check_no_third_party_deps() -> None:
    root_pkg = parse_json5(PROJECT / "oh-package.json5")
    deps = root_pkg.get("dependencies", {})
    dev = root_pkg.get("devDependencies", {})
    if deps or dev:
        fail(f"根 oh-package.json5 存在三方依赖：{deps or dev}")
    else:
        ok("无三方依赖（oh-package.json5 为空）")

    entry_pkg = parse_json5(PROJECT / "entry" / "oh-package.json5")
    if entry_pkg.get("dependencies"):
        fail(f"entry 存在三方依赖：{entry_pkg['dependencies']}")
    else:
        ok("entry 无三方依赖")


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:  # noqa: BLE001
        pass

    if not PROJECT.exists():
        print(f"找不到工程目录：{PROJECT}")
        return 1

    check_json5_files()
    check_resources()
    check_entries()
    check_imports()
    check_balance()
    check_arkts_hygiene()
    check_no_third_party_deps()

    print()
    for item in PASSES:
        print(f"[PASS] {item}")
    for item in WARNS:
        print(f"[WARN] {item}")
    for item in FAILS:
        print(f"[FAIL] {item}")
    print()
    print(f"结果：{len(PASSES)} 通过 / {len(WARNS)} 警告 / {len(FAILS)} 失败")
    return 0 if not FAILS else 1


if __name__ == "__main__":
    sys.exit(main())
