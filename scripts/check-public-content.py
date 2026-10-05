#!/usr/bin/env python3
"""Check Git candidate/index content without printing potential secret values."""
import argparse
from pathlib import Path, PurePosixPath
import re
import subprocess
from urllib.parse import unquote, urlsplit

PATTERNS = {
    "private local path": re.compile(r"/(?:Users|home)/[A-Za-z0-9_.-]+/"),
    "private service domain": re.compile(r"\b(?:[\w.-]+\.)?baidu-int\.[\w.-]+", re.I),
    "private source reference": re.compile(r"\b(?:fe-mall[-]pc|dam[-]panda|com\.baidu\.ec)\b"),
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "GitHub token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})\b"),
    "provider key": re.compile(r"\bsk-(?:proj-|ant-)?[A-Za-z0-9_-]{24,}\b"),
    "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
}
LINK = re.compile(r"!?\[[^\]\n]*\]\(([^)\n]+)\)")
PUBLIC_ENV_TEMPLATES = {".env.yml.example"}


def is_process_artifact(name):
    parts = PurePosixPath(name).parts
    return bool(parts) and (parts[0] in {"docs", "doc"} or name == "horizen-agent-web/design-qa.md")


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def check(root, staged=False):
    args = ("diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z") if staged else (
        "ls-files", "--cached", "--others", "--exclude-standard", "-z")
    names = sorted(set(git(root, *args).decode().strip("\0").split("\0")) - {""})
    index_names = set(git(root, "ls-files", "--cached", "-z").decode().split("\0"))
    index_symlinks = {entry.split("\t", 1)[1] for entry in
                      git(root, "ls-files", "--stage", "-z").decode().split("\0")
                      if entry.startswith("120000 ")}
    problems = []
    for name in names:
        path = root / name
        parts = PurePosixPath(name).parts
        if is_process_artifact(name):
            problems.append((name, 0, "local process artifact"))
        if any(part in {".agentscope", "node_modules", "target", ".idea"} for part in parts):
            problems.append((name, 0, "private/generated directory"))
        public_template = len(parts) == 1 and name in PUBLIC_ENV_TEMPLATES
        if not public_template and any(part.startswith(".env") and part != ".env.example" for part in parts):
            problems.append((name, 0, "private environment file"))
        if staged:
            if name in index_symlinks:
                problems.append((name, 0, "symlink requires explicit distribution review"))
                continue
            content = git(root, "show", ":" + name)
        elif path.is_symlink():
            problems.append((name, 0, "symlink requires explicit distribution review"))
            continue
        elif path.is_file():
            content = path.read_bytes()
        else:
            continue
        if len(content) > 10 * 1024 * 1024:
            problems.append((name, 0, "large source asset over 10 MiB"))
        try:
            text = content.decode("utf-8")
        except UnicodeDecodeError:
            continue
        for number, line in enumerate(text.splitlines(), 1):
            for label, pattern in PATTERNS.items():
                if pattern.search(line):
                    problems.append((name, number, label))
            if path.suffix == ".md":
                for match in LINK.finditer(line):
                    destination = match.group(1).strip().split(' "', 1)[0].strip("<>")
                    parsed = urlsplit(destination)
                    if parsed.scheme or parsed.netloc or not parsed.path:
                        continue
                    target = (path.parent / unquote(parsed.path)).resolve()
                    inside = target.is_relative_to(root.resolve())
                    if inside and is_process_artifact(target.relative_to(root.resolve()).as_posix()):
                        problems.append((name, number, "Markdown link to local process artifact"))
                        continue
                    exists = str(target.relative_to(root.resolve())) in index_names if staged and inside else target.exists()
                    if not inside or not exists:
                        problems.append((name, number, "missing or external local Markdown target"))
    return names, problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged", action="store_true", help="Read staged changes from the Git index")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    options = parser.parse_args()
    names, problems = check(options.root, options.staged)
    for name, number, label in problems:
        print(f"{name}:{number}: {label}")
    if problems:
        print(f"Public-content check failed: {len(problems)} findings (values withheld).")
        raise SystemExit(1)
    print(f"Public-content check passed: {len(names)} {'staged' if options.staged else 'candidate'} files.")


if __name__ == "__main__":
    main()
