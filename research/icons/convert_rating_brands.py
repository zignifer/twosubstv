"""Convert saved rating brand artwork, preserving paths, proportions and colors."""
from pathlib import Path
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "research/icons/rating-brands"
DEST = ROOT / "app/src/main/res/drawable"
SVG = "{http://www.w3.org/2000/svg}"


def convert(name, filename):
    svg = ET.parse(SOURCE / filename).getroot()
    assert all("transform" not in element.attrib for element in svg.iter())
    box = svg.attrib.get("viewBox", "0 0 " + svg.get("width", "0") + " " + svg.get("height", "0")).split()
    assert box[:2] == ["0", "0"]
    width, height = map(float, box[2:])
    gradient = svg.find(".//" + SVG + "linearGradient")
    lines = []
    for path in svg.iter(SVG + "path"):
        assert "transform" not in path.attrib
        attrs = "android:pathData=" + quoteattr(path.attrib["d"])
        if path.get("fill-rule") == "evenodd":
            attrs += ' android:fillType="evenOdd"'
        if gradient is not None:
            lines += ["    <path " + attrs + ">", '        <aapt:attr name="android:fillColor">',
                      '            <gradient android:type="linear" android:startX="0" '
                      + 'android:startY=' + quoteattr(gradient.get("y1", "0"))
                      + ' android:endX=' + quoteattr(gradient.get("x2", "0"))
                      + ' android:endY=' + quoteattr(gradient.get("y2", "0")) + ">"]
            for stop in gradient:
                lines.append('                <item android:offset=' + quoteattr(stop.attrib["offset"])
                             + ' android:color=' + quoteattr(stop.attrib["stop-color"]) + " />")
            lines += ["            </gradient>", "        </aapt:attr>", "    </path>"]
        else:
            fill = path.attrib["fill"]
            assert fill == "#fff"
            lines.append("    <path " + attrs + ' android:fillColor="#FFFFFFFF" />')
    assert lines
    text = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Original brand artwork; see research/UX-ICONS.md for provenance. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{16 * width / height:.6f}dp" android:height="16dp"\n'
            f'    android:viewportWidth="{width}" android:viewportHeight="{height}">\n'
            + "\n".join(lines) + "\n</vector>\n")
    (DEST / f"brand_{name}.xml").write_text(text, encoding="utf-8")


if __name__ == "__main__":
    convert("kp", "kp.svg")
    convert("imdb", "imd_big.svg")
    convert("tmdb", "tmdb-official-blue-square-1.svg")
    print("Converted three rating brand marks without redrawing their geometry.")
