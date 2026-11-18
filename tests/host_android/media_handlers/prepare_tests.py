"""Replace Mockito-only Android contexts in temporary test copies, never production code."""

import re
import sys
from pathlib import Path

root, output = map(Path, sys.argv[1:])
tests = root / "android/src/android/app/src/test/java/ai/tama/media"

image = (tests / "MediaOffloadImageTest.kt").read_text(encoding="utf-8")
image = image.replace("import org.mockito.Mockito\n", "")
image, count = re.subn(
    r"    private fun context\(\): Context \{.*?\n    }\n\n    private fun request",
    "    private fun context(): Context = Context(temp.root)\n\n    private fun request",
    image,
    flags=re.S,
)
if count != 1 or "Mockito" in image:
    raise RuntimeError("Unexpected image context fixture; inspect the source before adapting it")
(output / "MediaOffloadImageTest.kt").write_text(image, encoding="utf-8")

video = (tests / "MediaOffloadVideoTest.kt").read_text(encoding="utf-8")
video = video.replace("import org.mockito.Mockito\n", "")
old = "private val context = Mockito.mock(Context::class.java)"
if video.count(old) != 1:
    raise RuntimeError("Unexpected video context fixture; inspect the source before adapting it")
video = video.replace(old, "private val context by lazy { Context(temp.root) }")
video, count = re.subn(r"^        Mockito\.`when`\(context\.[^\n]*\n", "", video, flags=re.M)
if count != 5 or "Mockito" in video:
    raise RuntimeError("Unexpected video stubbing; inspect the source before adapting it")
(output / "MediaOffloadVideoTest.kt").write_text(video, encoding="utf-8")
