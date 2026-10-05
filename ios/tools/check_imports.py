"""Cheap local pre-flight for the iOS port (no JDK needed).

Reports imports in ios/shared/src that point at project/compat code
(com.music.bitchord.*, android.*, java.*, javax.*, okhttp3.*, org.*) whose
symbol is not declared anywhere in the iOS sources yet - i.e. something still
to port or shim. External libraries (compose, kotlinx, ktor, coil...) are
assumed present.

    python ios/tools/check_imports.py
"""
import os
import re
import collections

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "ios", "shared", "src")

CHECKED = ("com.music.bitchord.", "android.", "androidx.appcompat", "androidx.core.", "androidx.media3",
           "androidx.activity", "androidx.annotation", "java.", "javax.", "okhttp3.", "okio.", "org.")
# Packages that really exist on iOS through dependencies.
PROVIDED = ("androidx.compose.", "androidx.lifecycle.", "org.jetbrains.", "org.intellij.")

DECL = re.compile(
    r"^\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:public|internal|private|protected|open|abstract|sealed|data|enum|"
    r"inline|value|annotation|actual|expect|const|override|lateinit|suspend|operator|infix|tailrec|fun)\s+)*"
    r"(?:class|interface|object|typealias|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(`?\w+`?)",
    re.M,
)

declared = collections.defaultdict(set)
files = []
for root, _, names in os.walk(SRC):
    for name in names:
        if not name.endswith(".kt"):
            continue
        path = os.path.join(root, name)
        text = open(path, encoding="utf-8").read()
        files.append((path, text))
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        pkg = pkg.group(1) if pkg else ""
        for m in DECL.finditer(text):
            declared[pkg].add(m.group(1).strip("`"))
        # nested declarations (Companion members, enum classes inside objects) reachable as Outer.Inner
        for m in re.finditer(r"(?:class|object|interface)\s+(\w+)", text):
            declared[pkg].add(m.group(1))

missing = collections.defaultdict(list)
for path, text in files:
    for m in re.finditer(r"^import\s+([\w.]+)(?:\s+as\s+\w+)?", text, re.M):
        full = m.group(1)
        if full.startswith(PROVIDED) or not full.startswith(CHECKED):
            continue
        parts = full.split(".")
        # find the longest package prefix that has declarations
        found = False
        for i in range(len(parts) - 1, 0, -1):
            pkg = ".".join(parts[:i])
            name = parts[i]
            # Below the direct parent, only a class can be the container (Outer.member).
            if i < len(parts) - 1 and not name[:1].isupper():
                continue
            if pkg in declared and name in declared[pkg]:
                found = True
                break
        if not found:
            missing[full].append(os.path.relpath(path, SRC).replace(os.sep, "/"))

for full in sorted(missing):
    users = missing[full]
    print(f"{full}  <- {len(users)}: {', '.join(sorted(set(u.split('/')[-1] for u in users))[:6])}")
print(f"\n{len(missing)} unresolved project/compat imports")
