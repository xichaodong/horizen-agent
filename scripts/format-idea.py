#!/usr/bin/env python3
"""用与 IDEA 编辑器相同的原生格式化器处理公开源码；运行状态与用户 IDE 隔离。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / '.idea/codeStyles/Project.xml'
SPEC = json.loads((ROOT / 'scripts/intellij-formatter.json').read_text())
EXTENSIONS = {'.java', '.xml', '.js', '.jsx', '.less', '.html', '.json', '.yml', '.yaml', '.svg'}
IDE_FILES = {'.idea/codeStyles/Project.xml', '.idea/codeStyles/codeStyleConfig.xml'}
PRIVATE_DIRECTORIES = {'.git', '.agentscope', 'node_modules', 'target', 'dist', '__pycache__'}


def metadata(home):
    """兼容 macOS 应用包与 Linux 解压目录，返回官方启动元数据。"""
    for relative in ('Contents/Resources/product-info.json', 'product-info.json'):
        path = home / relative
        if path.is_file():
            return path, json.loads(path.read_text())
    raise RuntimeError('IDEA installation has no product-info.json')


def cache_directory():
    """格式化器缓存独立于应用配置，也不会进入 Git 或镜像构建上下文。"""
    base = Path(os.environ.get('HORIZEN_FORMATTER_CACHE', str(Path.home() / '.cache/horizen-agent/intellij')))
    return base / SPEC['version']


def install():
    """仅显式请求时安装 CI 使用的 Linux 包，校验固定 SHA-256 后再解压。"""
    if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'amd64'):
        raise RuntimeError('--install supports Linux x64; set HORIZEN_IDEA_HOME to an installed IDEA elsewhere')
    cache = cache_directory()
    home = cache / 'idea'
    if (home / 'product-info.json').is_file():
        return home
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='download-', dir=cache) as directory:
        work = Path(directory)
        archive = work / 'idea.tar.gz'
        url = 'https://download.jetbrains.com/idea/idea-' + SPEC['version'] + '.tar.gz'
        print('Downloading IntelliJ IDEA ' + SPEC['version'] + ' for formatting', flush=True)
        subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error', '--retry', '2',
                        '--max-time', '600', url, '--output', str(archive)], check=True)
        digest = hashlib.sha256()
        with archive.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
        if digest.hexdigest() != SPEC['linux_x64_sha256']:
            raise RuntimeError('IDEA archive checksum mismatch')
        unpacked = work / 'unpacked'
        unpacked.mkdir()
        with tarfile.open(archive) as tar:
            # 兼容 Python 3.10；校验目录与链接边界，禁止归档写入解压目录之外。
            for item in tar.getmembers():
                target = unpacked / item.name
                if not target.resolve().is_relative_to(unpacked.resolve()) or item.isdev():
                    raise RuntimeError('Unsafe IDEA archive member')
                if item.issym() or item.islnk():
                    link = (target.parent if item.issym() else unpacked) / item.linkname
                    if not link.resolve().is_relative_to(unpacked.resolve()):
                        raise RuntimeError('Unsafe IDEA archive link')
            tar.extractall(unpacked)
        packages = list(unpacked.glob('*/product-info.json'))
        if len(packages) != 1:
            raise RuntimeError('Unexpected IDEA archive layout')
        shutil.move(str(packages[0].parent), home)
    return home


def locate(allow_install):
    """本地优先使用显式指定或已安装的 IDEA，严格核对与 CI 相同的版本。"""
    explicit = os.environ.get('HORIZEN_IDEA_HOME')
    candidates = [Path(explicit)] if explicit else [
        Path('/Applications/IntelliJ IDEA.app'), cache_directory() / 'idea']
    for home in candidates:
        if home.exists():
            path, info = metadata(home)
            if info['version'] != SPEC['version'] or info['buildNumber'] != SPEC['build']:
                raise RuntimeError('Formatter requires IDEA ' + SPEC['version'] + ' build ' + SPEC['build']
                                   + '; installed version is ' + info['version'])
            return home, path, info
    if allow_install:
        return locate_after_install()
    raise RuntimeError('Install IDEA ' + SPEC['version'] + ' or set HORIZEN_IDEA_HOME; Linux CI can use --install')


def locate_after_install():
    """下载后的包再次经过版本校验，不能用缓存中其他版本替代。"""
    home = install()
    path, info = metadata(home)
    if info['version'] != SPEC['version'] or info['buildNumber'] != SPEC['build']:
        raise RuntimeError('Downloaded IDEA version does not match the pinned formatter')
    return home, path, info


def source_files():
    """只选 Git 可公开候选源码；私有配置、过程文档和其他 IDEA 元数据始终排除。"""
    names = subprocess.check_output(
        ['git', '-C', str(ROOT), 'ls-files', '--cached', '--others', '--exclude-standard', '-z']
    ).decode().split('\0')
    selected = []
    for name in sorted(set(names)):
        if not name:
            continue
        path = ROOT / name
        parts = Path(name).parts
        if (parts[0] in ('docs', 'doc') or name == 'horizen-agent-web/design-qa.md'
                or any(part in PRIVATE_DIRECTORIES for part in parts)
                or ('.idea' in parts and name not in IDE_FILES)
                or any(part.startswith('.env') for part in parts)
                or path.name == 'package-lock.json'
                or path.suffix not in EXTENSIONS or not path.is_file()):
            continue
        if path.is_symlink():
            raise RuntimeError('Refusing to format a source symlink: ' + name)
        selected.append(path)
    return sorted(set(selected + [ROOT / name for name in IDE_FILES]))


def command(home, meta, info, directory):
    """按安装包启动元数据启动 JBR，使用临时配置避免影响正在运行的 IDEA。"""
    launch = next(item for item in info['launch'] if (meta.parent / item['javaExecutablePath']).is_file())
    base = home / 'Contents' if (home / 'Contents').is_dir() else home
    properties = ['-Djava.awt.headless=true']
    properties += ['-Didea.' + key + '.path=' + str(directory / key)
                   for key in ('config', 'system', 'log', 'plugins')]
    options = [line for line in (meta.parent / launch['vmOptionsFilePath']).resolve().read_text().splitlines()
               if line.strip() and not line.startswith('#')]
    arguments = [value.replace('$APP_PACKAGE', str(home)).replace('$IDE_HOME', str(base))
                 for value in launch['additionalJvmArguments']]
    if any('$' in value for value in arguments):
        raise RuntimeError('Unsupported IDEA launcher variable')
    return [str((meta.parent / launch['javaExecutablePath']).resolve()), *options, *arguments, *properties,
            '-cp', os.pathsep.join(str(base / 'lib' / name) for name in launch['bootClassPathJarNames']),
            launch['mainClass'], 'format', '-s', str(SETTINGS), '-charset', 'UTF-8']


def main():
    """check 不改源码；apply 只修改选中的公开文件，模板通过 YAML 副本识别文件类型。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('apply', 'check'))
    parser.add_argument('--install', action='store_true', help='Download the pinned Linux x64 formatter if needed')
    args = parser.parse_args()
    home, meta, info = locate(args.install)
    files = source_files()
    with tempfile.TemporaryDirectory(prefix='horizen-idea-format-') as directory:
        work = Path(directory)
        # 始终在公开源码副本中格式化，不读取当前工程的私人 IDEA 元数据。
        source = work / 'source'
        source.mkdir()
        (source / '.editorconfig').write_bytes((ROOT / '.editorconfig').read_bytes())
        copies = []
        originals = {}
        for path in files:
            if path.is_symlink():
                raise RuntimeError('Refusing to format a source symlink')
            copy = source / path.relative_to(ROOT)
            copy.parent.mkdir(parents=True, exist_ok=True)
            originals[path] = path.read_bytes()
            copy.write_bytes(originals[path])
            copies.append(copy)
        template = source / 'public-template.yml'
        original = (ROOT / '.env.yml.example').read_bytes()
        template.write_bytes(original)
        native = command(home, meta, info, work)
        native[native.index('-s') + 1] = str(source / '.idea/codeStyles/Project.xml')
        env = {key: value for key, value in os.environ.items()
               if key in ('PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'SYSTEMROOT')}
        result = subprocess.run(native + [str(path) for path in copies] + [str(template)],
                                cwd=ROOT, env=env, capture_output=True, text=True, timeout=180)
        output = result.stdout + result.stderr
        scanned = re.search(r'(\d+) file\(s\) scanned', output)
        if result.returncode:
            diagnostics = [line for line in output.splitlines()
                           if line.startswith(('Checking ', 'Formatting '))
                           and not line.endswith(('...Formatted well', '...OK'))]
            print(('\n'.join(diagnostics) if diagnostics else output[-5000:]).replace(str(source), '.').replace(str(ROOT), '.'))
            print('Run ./scripts/format.sh apply to use the shared IDEA style.')
            raise SystemExit(result.returncode)
        if not scanned or int(scanned[1]) != len(files) + 1 or '...Skipped' in output:
            print(output[-3000:].replace(str(source), '.').replace(str(ROOT), '.'))
            raise RuntimeError('IDEA did not process every selected source file')
        if any(path.read_bytes() != data for path, data in originals.items()):
            raise RuntimeError('Source was edited during formatting; rerun after saving edits')
        if (ROOT / '.env.yml.example').read_bytes() != original:
            raise RuntimeError('Public template was edited during formatting; rerun after saving edits')
        changed = [path for path, copy in zip(files, copies) if originals[path] != copy.read_bytes()]
        if original != template.read_bytes():
            changed.append(ROOT / '.env.yml.example')
        if args.mode == 'check' and changed:
            print('IDEA format differs: ' + ', '.join(str(path.relative_to(ROOT)) for path in changed[:20]))
            raise SystemExit('Run ./scripts/format.sh apply to use the shared IDEA style.')
        if args.mode == 'apply':
            for path, copy in zip(files, copies):
                # 格式化期间发生编辑时拒绝覆盖；避免写入不属于本次操作的修改。
                if path in changed:
                    if path.read_bytes() != originals[path]:
                        raise RuntimeError('Concurrent source edit; refusing to overwrite ' + str(path.relative_to(ROOT)))
                    path.write_bytes(copy.read_bytes())
            if original != template.read_bytes():
                (ROOT / '.env.yml.example').write_bytes(template.read_bytes())
        print('IDEA ' + SPEC['version'] + ' native formatter ' + args.mode + ' passed: '
              + str(len(files) + 1) + ' files; user IDE state and private configuration isolated.')


if __name__ == '__main__':
    main()
