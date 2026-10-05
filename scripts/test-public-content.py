#!/usr/bin/env python3
"""Exercise release-gate behavior using disposable Git repositories."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).with_name("check-public-content.py")
spec = importlib.util.spec_from_file_location("public_content", SOURCE)
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


class PublicContentTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def stage(self, *names):
        subprocess.run(["git", "-C", str(self.root), "add", "--", *names], check=True)

    def test_index_secret_is_detected_even_after_working_copy_is_sanitized(self):
        secret = "gh" + "p_" + "A" * 36
        self.write("settings.txt", secret)
        self.stage("settings.txt")
        self.write("settings.txt", "redacted")
        self.assertFalse(scanner.check(self.root)[1])
        _, problems = scanner.check(self.root, staged=True)
        self.assertTrue(any(label == "GitHub token" for _, _, label in problems))
        result = subprocess.run(["python3", str(SOURCE), "--root", str(self.root), "--staged"],
                                capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertNotIn(secret, result.stdout + result.stderr)

    def test_ignored_private_configuration_is_excluded_but_tracked_configuration_is_rejected(self):
        self.write(".gitignore", ".env.*\n")
        self.write(".env.yml", "local: true")
        names, problems = scanner.check(self.root)
        self.assertNotIn(".env.yml", names)
        self.assertFalse(problems)
        subprocess.run(["git", "-C", str(self.root), "add", "-f", ".env.yml"], check=True)
        self.assertTrue(scanner.check(self.root, staged=True)[1])

    def test_missing_document_target_fails_and_valid_index_target_passes(self):
        self.write("README.md", "[Guide](guides/guide.md)\n")
        self.assertTrue(scanner.check(self.root)[1])
        self.write("guides/guide.md", "Synthetic guide\n")
        self.stage("README.md", "guides/guide.md")
        (self.root / "guides/guide.md").unlink()
        self.assertFalse(scanner.check(self.root, staged=True)[1])

    def test_ignored_process_artifacts_are_local_but_forced_tracking_is_rejected(self):
        self.write(".gitignore", "/docs/\n/doc/\n/horizen-agent-web/design-qa.md\n")
        for name in ("docs/design.md", "doc/notes.md", "horizen-agent-web/design-qa.md"):
            with self.subTest(name=name):
                self.write(name, "Local process notes\n")
                names, problems = scanner.check(self.root)
                self.assertNotIn(name, names)
                self.assertFalse(problems)
                subprocess.run(["git", "-C", str(self.root), "add", "-f", name], check=True)
                self.assertTrue(any(label == "local process artifact"
                                    for _, _, label in scanner.check(self.root, staged=True)[1]))
                subprocess.run(["git", "-C", str(self.root), "rm", "--cached", name],
                               check=True, capture_output=True)
                self.assertTrue((self.root / name).is_file())

    def test_links_to_local_process_documents_fail_even_when_the_file_exists(self):
        self.write(".gitignore", "/docs/\n")
        self.write("docs/design.md", "Local process notes\n")
        self.write("README.md", "[Design](docs/design.md)\n")
        self.stage("README.md")
        for staged in (False, True):
            self.assertTrue(any(label == "Markdown link to local process artifact"
                                for _, _, label in scanner.check(self.root, staged=staged)[1]))

    def test_external_symlink_is_rejected_in_working_copy_and_index(self):
        (self.root / "external").symlink_to("/etc/hosts")
        self.assertTrue(scanner.check(self.root)[1])
        self.stage("external")
        self.assertTrue(scanner.check(self.root, staged=True)[1])

    def test_public_provider_examples_and_placeholder_keys_are_allowed(self):
        self.write(".env.yml.example", "api-key: ''\n")
        self.write("README.md", "[Provider](https://example.com/docs)\n")
        self.assertFalse(scanner.check(self.root)[1])

    def test_yaml_templates_are_allowed_but_private_and_nested_files_are_rejected(self):
        for name in scanner.PUBLIC_ENV_TEMPLATES:
            self.write(name, "enabled: false\n")
        self.assertFalse(scanner.check(self.root)[1])
        self.write(".env.yml", "api-key: ''\n")
        self.write("nested/.env.yml.example", "api-key: ''\n")
        _, problems = scanner.check(self.root)
        self.assertEqual(2, len(problems))

    def test_yaml_template_values_still_receive_secret_checks(self):
        self.write(".env.yml.example", "api-key: gh" + "p_" + "A" * 36 + "\n")
        self.stage(".env.yml.example")
        self.assertTrue(any(label == "GitHub token"
                            for _, _, label in scanner.check(self.root, staged=True)[1]))


if __name__ == "__main__":
    unittest.main()
