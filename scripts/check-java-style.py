#!/usr/bin/env python3
"""Reject redundant package-qualified Java references, allowing real name conflicts."""
from pathlib import Path
import re


LITERALS = re.compile(
    r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
)
QUALIFIED_TYPE = re.compile(
    r"\b((?:java|javax|jakarta|lombok|dev|io|org|com|reactor|okhttp3)"
    r"(?:\.[a-z_$][\w$]*)*\.([A-Z][\w$]*))"
)


def findings(source):
    code = LITERALS.sub(
        lambda match: "".join("\n" if char == "\n" else " " for char in match[0]), source
    )
    imports = {
        name.rsplit(".", 1)[-1]: name
        for name in re.findall(r"\bimport\s+([\w.$]+)\s*;", code)
    }
    declared = set(re.findall(r"\b(?:class|interface|enum|record)\s+(\w+)", code))
    problems = []
    for number, line in enumerate(code.splitlines(), 1):
        if line.lstrip().startswith(("package ", "import ")):
            continue
        for match in QUALIFIED_TYPE.finditer(line):
            qualified, simple = match.groups()
            if simple in declared or (simple in imports and imports[simple] != qualified):
                continue
            problems.append((number, qualified))
    return problems


def main():
    root = Path(__file__).resolve().parents[1]
    sources = sorted(root.glob("*/src/**/*.java"))
    count = 0
    for path in sources:
        for number, qualified in findings(path.read_text(encoding="utf-8")):
            print(f"{path.relative_to(root)}:{number}: import {qualified} and use its simple name")
            count += 1
    if count:
        raise SystemExit(f"Java import style check failed: {count} redundant qualified references.")
    print(f"Java import style check passed: {len(sources)} source files.")


if __name__ == "__main__":
    main()
