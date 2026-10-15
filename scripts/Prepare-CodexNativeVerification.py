"""Owned native /tmp bytecode cache; no source copy, global setting, move or deletion."""
import hashlib
import json
import os
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).resolve(strict=True)
if source.name != "Code-Pal-Codex" or not (source / "pom.xml").is_file():
    raise RuntimeError("Unexpected verification source")
base = pathlib.Path("/tmp/codepal-codex-verification")
if base.is_symlink():
    raise RuntimeError("Native cache parent is a symlink")
base.mkdir(mode=0o700, exist_ok=True)
target = base / hashlib.sha256(str(source).encode()).hexdigest()[:16]
if target.is_symlink() or target.resolve().parent != base.resolve():
    raise RuntimeError("Native cache escaped its owned parent")
target.mkdir(mode=0o700, exist_ok=True)
marker = target / "owned.json"
identity = {"source": str(source), "purpose": "codex-verification-bytecode-v1"}
if marker.exists():
    if marker.is_symlink() or json.loads(marker.read_text()) != identity:
        raise RuntimeError("Native cache ownership differs")
elif any(target.iterdir()):
    raise RuntimeError("Refusing unowned native cache contents")
else:
    with marker.open("x") as stream:
        json.dump(identity, stream)
print(target)
