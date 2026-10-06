#!/usr/bin/env python3
"""验证格式化候选文件的隐私边界，不依赖或启动用户的 IDEA。"""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('idea_formatter', Path(__file__).with_name('format-idea.py'))
formatter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(formatter)


class FormatterPrivacyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        self.previous = formatter.ROOT
        formatter.ROOT = self.root
        for name in formatter.IDE_FILES:
            self.write(name, '<component />')

    def tearDown(self):
        formatter.ROOT = self.previous
        self.directory.cleanup()

    def write(self, name, value='synthetic'):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)
        return path

    def test_private_configuration_and_process_files_are_never_selected(self):
        excluded = ['.env.yml', '.env.yml.example', 'docs/design.xml', 'doc/notes.java',
                    '.idea/workspace.xml', 'target/Generated.java', '.agentscope/state.json',
                    'web/node_modules/library.js', 'web/dist/bundle.js', 'web/package-lock.json']
        for name in excluded:
            self.write(name)
        subprocess.run(['git', '-C', str(self.root), 'add', '-f', '--', *excluded], check=True)
        selected = {str(path.relative_to(self.root)) for path in formatter.source_files()}
        self.assertTrue(selected.isdisjoint(excluded))

    def test_new_public_sources_and_only_shared_idea_settings_are_selected(self):
        source = self.write('module/src/main/java/Sample.java', 'class Sample {}')
        selected = set(formatter.source_files())
        self.assertIn(source, selected)
        self.assertTrue({self.root / name for name in formatter.IDE_FILES}.issubset(selected))

    def test_source_symlinks_cannot_copy_external_content(self):
        external = self.write('external.txt')
        (self.root / 'Escape.java').symlink_to(external)
        with self.assertRaisesRegex(RuntimeError, 'symlink'):
            formatter.source_files()


if __name__ == '__main__':
    unittest.main()
