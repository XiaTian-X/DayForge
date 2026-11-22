"""Fixed replica admission negatives; reuses existing synthetic file fixture, no device calls."""
import unittest
from tools.tests import test_command_build_admission as original

P = original.P


class ReplicaBuildAdmission(unittest.TestCase):
    def setUp(self):
        self.fixture = original.BuildAdmission()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def declare_replica(self):
        f = self.fixture
        for name in P.REPLICA_SOURCES:
            f.write(name, ("synthetic fixed replica source: " + name).encode())
            f.files[name] = P.sha(f.root / name)
        f.json("evidence/source.json", {"files": f.files})
        digest = P.sha(f.root / "evidence/source.json")
        f.body["sources"]["sha256"] = digest
        f.body["ordinary_results"][0]["sources_sha256"] = digest
        f.body.update(format="replica-final-build-admission/v1", driver_source=P.REPLICA_DRIVER_SOURCE,
            driver_source_sha256=f.files[P.REPLICA_DRIVER_SOURCE], host_groups={"replica-final": list(P.GROUPS["replica-final"])})
        f.pin()

    def load(self):
        return self.fixture.load(group="replica-final", driver_sha=self.fixture.files.get(P.REPLICA_DRIVER_SOURCE, "a"*64))

    def test_old_count_admission_or_cli_cannot_admit_replica(self):
        with self.assertRaises(P.Failed):
            self.load()
        self.declare_replica()
        self.fixture.body["format"] = "count-command-build-admission/v1"; self.fixture.pin()
        with self.assertRaises(P.Failed):
            self.load()

    def test_fixed_sources_complete_two_cases_and_apks_are_required(self):
        self.declare_replica()
        self.assertEqual(set(self.load()), {P.TARGET, P.TEST})
        self.fixture.body["host_groups"]["replica-final"] = ["file_repair"]; self.fixture.pin()
        with self.assertRaises(P.Failed):
            self.load()

    def test_unbound_peer_or_driver_and_modified_source_are_rejected(self):
        self.declare_replica()
        f = self.fixture
        f.body["driver_source"] = P.DRIVER_SOURCE; f.pin()
        with self.assertRaises(P.Failed):
            self.load()
        f.body["driver_source"] = P.REPLICA_DRIVER_SOURCE; f.pin()
        peer = next(name for name in P.REPLICA_SOURCES if name.endswith("ProcessPeer.kt"))
        f.write(peer, b"different peer")
        with self.assertRaises(P.Failed):
            self.load()


if __name__ == "__main__":
    unittest.main()
