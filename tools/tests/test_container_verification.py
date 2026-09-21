"""Rehearsal guards and independent recovery oracle; never execute Docker locally."""

from copy import deepcopy
import unittest
from unittest.mock import patch

from tools.verify_container import (
    compare_restored,
    require_hosted_ci,
    require_statuses,
    run,
)


class ContainerVerificationTest(unittest.TestCase):
    def test_only_hosted_ci_can_execute_docker(self):
        for environment in (
            {},
            {"GITHUB_ACTIONS": "true"},
            {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "self-hosted"},
        ):
            with self.subTest(environment=environment), self.assertRaises(RuntimeError):
                require_hosted_ci(environment)
        require_hosted_ci(
            {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted"}
        )
        with (
            patch.dict("os.environ", {}, clear=True),
            patch("subprocess.run") as process,
        ):
            with self.assertRaises(RuntimeError):
                run("sha256:" + "a" * 64)
            process.assert_not_called()

    def test_partial_empty_or_rejected_acknowledgements_fail(self):
        require_statuses({"results": [{"status": "applied"}] * 7}, "applied", 7)
        for values in ([], ["applied"] * 6, ["applied"] * 8, ["rejected"] * 7):
            with self.subTest(values=values), self.assertRaises(RuntimeError):
                require_statuses(
                    {"results": [{"status": value} for value in values]}, "applied", 7
                )

    def test_restore_checks_content_identity_and_epoch_not_only_counts(self):
        before = {
            "tables": {"events": {"rows": 1, "sha256": "original"}},
            "identity": "server",
            "epoch": "old",
        }
        after = dict(before, epoch="new")
        compare_restored(before, after)
        for broken in (before, dict(after, identity="other"), dict(after, tables={})):
            with self.subTest(broken=broken), self.assertRaises(RuntimeError):
                compare_restored(before, broken)
        changed = deepcopy(after)
        changed["tables"]["events"]["sha256"] = "same-count-different-fact"
        with self.assertRaises(RuntimeError):
            compare_restored(before, changed)


if __name__ == "__main__":
    unittest.main()
