"""Copy Android sources into the iOS shared module, unchanged, under the same packages.

    python ios/tools/port_files.py <relative path or dir> [...]   (relative to app/.../bitchord/)
    --force   overwrite files already ported (default: keep the ported copy)

Ported files then compile against the compat shims in commonMain; see the
memory/notes for the strategy. Prints what it copied.
"""
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
A = os.path.join(ROOT, "app", "src", "main", "java", "com", "music", "bitchord")
T = os.path.join(ROOT, "ios", "shared", "src", "commonMain", "kotlin", "com", "music", "bitchord")

SKIP = {
    "ui/replay/ReplayPoster.kt",
    "ui/replay/ReplayShareSheet.kt",
    "ui/components/AudioPipelineDialog.kt",
    "ui/components/DownloadManagerSheet.kt",
    "ui/components/UpdateAvailableDialog.kt",
    "ui/components/AppLanguageDialog.kt",
}

force = "--force" in sys.argv
targets = [a for a in sys.argv[1:] if not a.startswith("--")]

copied = []
for target in targets:
    src = os.path.join(A, target)
    paths = []
    if os.path.isdir(src):
        for root, _, names in os.walk(src):
            for name in names:
                if name.endswith(".kt"):
                    paths.append(os.path.join(root, name))
    else:
        paths.append(src)
    for path in paths:
        rel = os.path.relpath(path, A).replace(os.sep, "/")
        if rel in SKIP:
            continue
        dst = os.path.join(T, rel)
        if os.path.exists(dst) and not force:
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy(path, dst)
        copied.append(rel)

for rel in copied:
    print(rel)
print(f"copied {len(copied)} file(s)")
