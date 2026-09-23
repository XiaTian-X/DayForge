import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class GitignoreAssetsTest(unittest.TestCase):
    def ignored(self, path):
        # Isolate from the developer's global excludes and local repository settings.
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["git", "init", "-q", directory], check=True)
            shutil.copyfile(
                Path(__file__).resolve().parents[2] / ".gitignore",
                Path(directory) / ".gitignore",
            )
            result = subprocess.run(
                ["git", "-c", "core.excludesFile=/dev/null", "check-ignore", "--no-index", path],
                cwd=directory, capture_output=True, text=True,
            )
            self.assertIn(result.returncode, (0, 1), result.stderr)
            return result.returncode == 0

    def test_application_and_document_images_remain_trackable(self):
        for path in ("android/app/src/main/res/drawable/icon.png", "docs/images/layout.png"):
            with self.subTest(path=path):
                self.assertFalse(self.ignored(path))

    def test_local_captures_and_build_images_remain_ignored(self):
        for path in ("screenshots/review.png", "android/captures/review.png", "android/app/build/review.png"):
            with self.subTest(path=path):
                self.assertTrue(self.ignored(path))
