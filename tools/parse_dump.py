#!/usr/bin/env python3
"""解析 uiautomator dump，输出可用于校准定位配置的节点摘要。

用法：python parse_dump.py <dump.xml> [--with-id-only] [--max N]
"""
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: parse_dump.py <dump.xml> [--all]")
        return 2
    path = sys.argv[1]
    show_all = "--all" in sys.argv
    max_n = 400
    for i, a in enumerate(sys.argv):
        if a == "--max" and i + 1 < len(sys.argv):
            max_n = int(sys.argv[i + 1])

    tree = ET.parse(path)
    root = tree.getroot()
    rows = []

    def walk(node, depth):
        a = node.attrib
        text = a.get("text", "").strip()
        desc = a.get("content-desc", "").strip()
        rid = a.get("resource-id", "").strip()
        cls = a.get("class", "").strip()
        clickable = a.get("clickable") == "true"
        bounds = a.get("bounds", "")
        if show_all or text or desc or rid:
            rows.append((depth, text, desc, rid, cls, clickable, bounds))
        for ch in node:
            walk(ch, depth + 1)

    walk(root, 0)
    print(f"# total listed: {len(rows)}  (file: {path})")
    print(f"{'d':>2} | {'text':<28} | {'desc':<22} | {'resource-id':<46} | clk | bounds")
    print("-" * 150)
    for depth, text, desc, rid, cls, clickable, bounds in rows[:max_n]:
        t = text[:28]
        d = desc[:22]
        r = rid.replace("com.ss.android.ugc.aweme:id/", "…/")[:46]
        print(f"{depth:>2} | {t:<28} | {d:<22} | {r:<46} | {'Y' if clickable else ' '}   | {bounds}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
