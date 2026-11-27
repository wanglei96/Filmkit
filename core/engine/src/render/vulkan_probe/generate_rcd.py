"""Reuse the exact full-size production P1/P2 math; only adapt IO and residency."""
import pathlib
import re
import sys

cpp, source, out, stage = sys.argv[1:]
name = {"1": "kFsRcdDir", "2": "kFsRcdG"}[stage]
text = re.search(rf'const char\* {name} = R"\((.*?)\)";', pathlib.Path(cpp).read_text(), re.S).group(1)
text = re.sub(r'^#version.*|^precision .*|^layout\(location.*|^uniform .*', '', text, flags=re.M)
text = re.sub(r'float gainAt\(.*?\n\}', '', text, flags=re.S)
text = text.replace("ivec2 p = ivec2(gl_FragCoord.xy);",
                    ("loadTile();\n    " if stage == "1" else "") +
                    "ivec2 p = ivec2(gl_GlobalInvocationID.xy);\n    if (any(greaterThanEqual(p, uSize))) return;")
text = text.replace("oColor = ", "vec4 outputPixel = ")
text, n = re.subn(r'(vec4 outputPixel =.*?;)\s*\n\}', r'\1\n    store(p, outputPixel);\n}', text, flags=re.S)
assert n == 1
if stage == "2":
    text = text.replace("texelFetch(uDir, clamp(p, ivec2(0), uSize - 1), 0)", "directionAt(p)")
pre = pathlib.Path(source).read_text()
pre = pre.replace("@BODY@", text).replace("@STAGE@", stage)
pathlib.Path(out).write_text(pre)
