#!/usr/bin/env python3
"""Builds the Dynamic Island's animations as fonts.

Live Activities draw one frame and only a timer Text keeps updating, so each animation is a
timer whose last digit is drawn in a font where `0`–`9` are the ten frames of a loop (every
other character the timer prints shapes to nothing). One font per layer, stacked and tinted in
the widget: the working bot (`CodyncBot-<shape>-Ink` / `-Tint`) and the thinking orb
(`CodyncOrb-Ghost` / `-Dot`).

The frames come from the app's own drawing code (`DottedBody.workingLoop`,
`ThinkingOrbGeometry.workingLoop`):

    cd apps/ios/Kit && TEST_RUNNER_CODYNC_FRAMES_FILE=/tmp/frames.json xcodebuild test \
      -scheme CodyncKit-Package -derivedDataPath ../../../build/dd \
      -destination 'platform=iOS Simulator,id=<sim>' '-only-testing:CodyncKitTests/exportBotFrames()'
    tools/timer-fonts.py /tmp/frames.json apps/ios/Widgets/Resources/Fonts
"""

import json
import math
import sys
from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

EM = 1000
DIGITS = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]


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


# Every character a timer can print besides digits: ASCII and the ratio colon, the narrow and
# no-break spaces and the full-width colon some locales use.
SEPARATORS = {"space": 0x20, "colon": 0x3A, "period": 0x2E, "comma": 0x2C, "ratio": 0x2236,
              "nbsp": 0xA0, "nnbsp": 0x202F, "thinsp": 0x2009, "fwcolon": 0xFF1A}
# Only the last digit draws: a digit followed by anything the timer prints becomes `blank`
# (contextual alternates, on by default), so the whole timer is exactly one face wide and
# nothing depends on how the system aligns or clips timer text.
FEATURES = """
@DIGIT = [zero one two three four five six seven eight nine];
@NEXT = [@DIGIT space colon period comma ratio nbsp nnbsp thinsp fwcolon];
feature calt {
    sub @DIGIT' @NEXT by blank;
} calt;
"""


def build(name, frames, size, out):
    order = [".notdef", "blank", *SEPARATORS, *DIGITS]
    glyphs = {g: empty() for g in [".notdef", "blank", *SEPARATORS]}
    for digit, dots in zip(DIGITS, frames):
        glyphs[digit] = glyph(dots, size)
    fb = FontBuilder(EM, isTTF=True)
    fb.setupGlyphOrder(order)
    cmap = {code: g for g, code in SEPARATORS.items()}
    cmap.update({ord(str(i)): DIGITS[i] for i in range(10)})
    fb.setupCharacterMap(cmap)
    fb.setupGlyf(glyphs)
    metrics = {g: (EM if g in DIGITS else 0, 0) for g in order}
    fb.setupHorizontalMetrics(metrics)
    fb.setupHorizontalHeader(ascent=EM, descent=0)
    fb.setupOS2(sTypoAscender=EM, sTypoDescender=0, sTypoLineGap=0, usWinAscent=EM, usWinDescent=0)
    fb.setupNameTable({"familyName": name.replace("-", " "), "styleName": "Regular", "psName": name})
    fb.setupPost()
    fb.addOpenTypeFeatures(FEATURES)
    fb.save(out / f"{name}.ttf")


def main():
    frames_file, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    data = json.loads(frames_file.read_text())
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, frames in sorted(data["fonts"].items()):
        assert len(frames) == 10, f"{name}: a timer digit needs 10 frames"
        build(name, frames, data["size"], out_dir)
    print(f"wrote {len(data['fonts'])} fonts to {out_dir}")


if __name__ == "__main__":
    main()
