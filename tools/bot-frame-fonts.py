#!/usr/bin/env python3
"""Builds the Dynamic Island's animated bot faces as fonts.

Live Activities draw one frame and only a timer Text keeps updating, so the working bot is a
timer whose seconds digit is drawn in a font where `0`–`9` are the ten frames of its loop.
Two fonts per character shape, stacked in the widget: `Ink` (every dot, tinted grey) and
`Tint` (the lit dots, tinted in the bot's color).

The frames come from the app's own drawing code (CharacterAvatar.swift, `workingLoop`):

    cd apps/ios/Kit && TEST_RUNNER_CODYNC_FRAMES_FILE=/tmp/frames.json xcodebuild test \
      -scheme CodyncKit-Package -derivedDataPath ../../../build/dd \
      -destination 'platform=iOS Simulator,id=<sim>' '-only-testing:CodyncKitTests/exportBotFrames()'
    tools/bot-frame-fonts.py /tmp/frames.json apps/ios/Widgets/Resources/Fonts
"""

import json
import math
import sys
from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

EM = 1000
DIGITS = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]
# A lit dot joins the color layer above this much of the bot's color.
TINT_THRESHOLD = 0.35


def circle(pen, cx, cy, r):
    """A clockwise circle of eight off-curve points (TrueType quadratics)."""
    k = r / math.cos(math.pi / 8)
    points = [(cx + k * math.cos(-i * math.pi / 4), cy + k * math.sin(-i * math.pi / 4)) for i in range(8)]
    pen.qCurveTo(*[(round(x), round(y)) for x, y in points], None)
    pen.closePath()


def glyph(dots, size):
    pen = TTGlyphPen(None)
    scale = EM / size
    for d in dots:
        circle(pen, d["x"] * scale, (size - d["y"]) * scale, d["r"] * scale)
    return pen.glyph()


def empty():
    return TTGlyphPen(None).glyph()


def build(name, frames, size, out):
    separators = {"space": " ", "colon": ":", "period": ".", "comma": ","}
    order = [".notdef", *separators, *DIGITS]
    glyphs = {g: empty() for g in [".notdef", *separators]}
    for digit, dots in zip(DIGITS, frames):
        glyphs[digit] = glyph(dots, size)
    fb = FontBuilder(EM, isTTF=True)
    fb.setupGlyphOrder(order)
    cmap = {ord(c): g for g, c in separators.items()}
    cmap.update({ord(str(i)): DIGITS[i] for i in range(10)})
    fb.setupCharacterMap(cmap)
    fb.setupGlyf(glyphs)
    # Digits are one face wide; separators take no room, so the last digit sits flush right.
    metrics = {g: (EM if g in DIGITS else 0, 0) for g in order}
    fb.setupHorizontalMetrics(metrics)
    fb.setupHorizontalHeader(ascent=EM, descent=0)
    fb.setupOS2(sTypoAscender=EM, sTypoDescender=0, sTypoLineGap=0, usWinAscent=EM, usWinDescent=0)
    fb.setupNameTable({"familyName": name.replace("-", " "), "styleName": "Regular", "psName": name})
    fb.setupPost()
    fb.save(out / f"{name}.ttf")


def main():
    frames_file, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    data = json.loads(frames_file.read_text())
    size = data["size"]
    out_dir.mkdir(parents=True, exist_ok=True)
    for shape, frames in sorted(data["shapes"].items()):
        assert len(frames) == 10, f"{shape}: a timer digit needs 10 frames"
        build(f"CodyncBot-{shape}-Ink", frames, size, out_dir)
        lit = [[d for d in f if d["tint"] > TINT_THRESHOLD] for f in frames]
        build(f"CodyncBot-{shape}-Tint", lit, size, out_dir)
    print(f"wrote {2 * len(data['shapes'])} fonts to {out_dir}")


if __name__ == "__main__":
    main()
