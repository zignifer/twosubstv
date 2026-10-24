"""Convert the pinned official Lucide SVG subset to Android VectorDrawables.

Run from the project root: python research/icons/convert_lucide.py
Original paths are copied verbatim; SVG circle/rect primitives are expressed
as equivalent paths. Stroke width, round caps/joins and 24x24 grid are retained.
"""
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "research/icons/lucide"
DEST = ROOT / "app/src/main/res/drawable"


def number(value):
    return format(float(value), ".12g")


def geometry(element):
    tag = element.tag.rsplit("}", 1)[-1]
    attrs = element.attrib
    if tag == "path":
        assert set(attrs) == {"d"}
        return attrs["d"]
    if tag == "circle":
        assert set(attrs) == {"cx", "cy", "r"}
        x, y, r = [float(attrs[key]) for key in ("cx", "cy", "r")]
        return f"M{number(x+r)} {number(y)}a{number(r)} {number(r)} 0 1 0 {number(-2*r)} 0a{number(r)} {number(r)} 0 1 0 {number(2*r)} 0Z"
    if tag == "rect":
        assert set(attrs) <= {"x", "y", "width", "height", "rx", "ry"}
        x, y = float(attrs.get("x", 0)), float(attrs.get("y", 0))
        width, height = float(attrs["width"]), float(attrs["height"])
        rx = min(float(attrs.get("rx", attrs.get("ry", 0))), width / 2)
        ry = min(float(attrs.get("ry", attrs.get("rx", 0))), height / 2)
        n = number
        if rx == 0 or ry == 0:
            return f"M{n(x)} {n(y)}H{n(x+width)}V{n(y+height)}H{n(x)}Z"
        return (f"M{n(x+rx)} {n(y)}H{n(x+width-rx)}"
                f"A{n(rx)} {n(ry)} 0 0 1 {n(x+width)} {n(y+ry)}V{n(y+height-ry)}"
                f"A{n(rx)} {n(ry)} 0 0 1 {n(x+width-rx)} {n(y+height)}H{n(x+rx)}"
                f"A{n(rx)} {n(ry)} 0 0 1 {n(x)} {n(y+height-ry)}V{n(y+ry)}"
                f"A{n(rx)} {n(ry)} 0 0 1 {n(x+rx)} {n(y)}Z")
    raise ValueError(f"Unsupported SVG geometry: {tag}")


def convert(icon):
    svg = ET.parse(SOURCES / "svg" / icon["source_file"]).getroot()
    for attr, value in {"viewBox": "0 0 24 24", "fill": "none", "stroke": "currentColor",
                        "stroke-width": "2", "stroke-linecap": "round", "stroke-linejoin": "round"}.items():
        assert svg.get(attr) == value, (icon["name"], attr)
    assert all("transform" not in element.attrib for element in svg.iter())
    paths = ["    <path android:pathData=" + quoteattr(geometry(element))
             + ' android:fillColor="#00000000" android:strokeColor="#FFFFFFFF"'
             + ' android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" />'
             for element in svg]
    assert paths
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- Official Lucide artwork; full ISC/Feather notices: assets/licenses/lucide-LICENSE.txt. -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="24dp" android:height="24dp"\n'
           '    android:viewportWidth="24" android:viewportHeight="24">\n'
           + "\n".join(paths) + "\n</vector>\n")
    name = re.sub(r"(?<!^)(?=[A-Z])", "_", icon["name"]).lower()
    (DEST / f"ic_{name}_lucide.xml").write_text(xml, encoding="utf-8")


if __name__ == "__main__":
    manifest = json.loads((SOURCES / "source-manifest.json").read_text(encoding="utf-8"))
    for item in manifest["icons"]:
        convert(item)
    print(f'Converted {len(manifest["icons"])} official Lucide icons, 24dp and stroke width 2.')
