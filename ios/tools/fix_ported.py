"""Mechanical rewrites that let ported Android Kotlin compile in commonMain.

Idempotent; run after every port_files.py:
    python ios/tools/fix_ported.py

Only touches files under com/music/bitchord that came from the Android app
(it skips the shim packages and anything marked "// ios-native").
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "ios", "shared", "src", "commonMain", "kotlin", "com", "music", "bitchord")

LOCALE_ARG = r"Locale\.(?:ROOT|US|ENGLISH|UK|getDefault\(\))"


def add_import(text, imp):
    line = f"import {imp}\n"
    if line in text:
        return text
    m = re.search(r"^package [\w.]+\s*\n", text, re.M)
    if not m:
        return text
    return text[: m.end()] + "\n" + line + text[m.end():] if not re.search(r"^import ", text, re.M) else \
        re.sub(r"^(import )", line + r"\1", text, count=1, flags=re.M)


def fix(text):
    original = text
    # Case conversion is locale-independent in Kotlin common.
    text = re.sub(r"\.(lowercase|uppercase|titlecase)\(\s*" + LOCALE_ARG + r"\s*\)", r".\1()", text)
    text = re.sub(r"\.(lowercase|uppercase)\(\s*it\.locale\s*\)", r".\1()", text)
    # "fmt".format(Locale.X, args)  ->  "fmt".format(args)  (format is provided by the compat extension)
    text = re.sub(r"\.format\(\s*" + LOCALE_ARG + r"\s*,\s*", ".format(", text)
    text = re.sub(r"String\.format\(\s*" + LOCALE_ARG + r"\s*,\s*", "String.format(", text)
    # System clock
    text = text.replace("System.currentTimeMillis()", "com.music.bitchord.platform.epochMillis()")
    text = text.replace("System.nanoTime()", "(com.music.bitchord.platform.elapsedMillis() * 1_000_000L)")
    # Charsets
    text = re.sub(r"\.toByteArray\(\s*(?:Charsets\.UTF_8|StandardCharsets\.UTF_8)?\s*\)", ".encodeToByteArray()", text)
    text = re.sub(r"String\(\s*([^,()]+(?:\([^()]*\))?)\s*,\s*(?:Charsets\.UTF_8|StandardCharsets\.UTF_8)\s*\)",
                  r"\1.decodeToString()", text)
    text = re.sub(r"\.toString\(\s*(?:Charsets\.UTF_8|StandardCharsets\.UTF_8)\s*\)", ".decodeToString()", text)

    if "Dispatchers.IO" in text:
        text = add_import(text, "kotlinx.coroutines.IO")
    if re.search(r"@Volatile\b", text):
        text = add_import(text, "kotlin.concurrent.Volatile")
    if re.search(r"(?<![\w.])synchronized\s*\(", text):
        text = add_import(text, "com.music.bitchord.compat.synchronized")
    if re.search(r"\.format\(|String\.format\(", text):
        text = add_import(text, "com.music.bitchord.compat.format")
    if "Locale" in text and not re.search(r"(?<![\w.])Locale\b(?!\.ROOT\b)", re.sub(r"^import .*$", "", text, flags=re.M)):
        text = text.replace("import java.util.Locale\n", "")
    if text.count("import java.util.Locale\n") == 0 and re.search(r"(?<![\w.])Locale\.", text):
        text = add_import(text, "java.util.Locale")
    return text if text != original else None


changed = 0
for root, _, names in os.walk(SRC):
    for name in names:
        if not name.endswith(".kt"):
            continue
        path = os.path.join(root, name)
        text = open(path, encoding="utf-8").read()
        rel = os.path.relpath(path, SRC).replace(os.sep, "/")
        if "// ios-native" in text or rel.startswith(("compat/", "platform/")):
            continue
        new = fix(text)
        if new is not None:
            open(path, "w", encoding="utf-8", newline="\n").write(new)
            changed += 1
print(f"fixed {changed} file(s)")
