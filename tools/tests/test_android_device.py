import io
import unittest
from unittest.mock import patch

from tools.check_android_device import main, select_device


class PhysicalDeviceGateTest(unittest.TestCase):
    def test_ready_physical_device_is_selected(self):
        self.assertEqual(
            "phone", select_device("List of devices attached\nphone\tdevice\n", None)
        )

    def test_missing_unauthorized_offline_or_ambiguous_device_is_rejected(self):
        for listing in (
            "",
            "phone\tunauthorized",
            "phone\toffline",
            "one\tdevice\ntwo\tdevice",
        ):
            with self.subTest(listing=listing), self.assertRaises(ValueError):
                select_device(listing, None)

    def test_requested_device_must_be_ready_and_not_emulated(self):
        self.assertEqual("two", select_device("one\tdevice\ntwo\tdevice", "two"))
        for requested in ("absent", "emulator-5554"):
            with self.subTest(requested=requested), self.assertRaises(ValueError):
                select_device("one\tdevice\nemulator-5554\tdevice", requested)

    @patch.dict("os.environ", {}, clear=True)
    @patch("subprocess.check_output", side_effect=["network-address\tdevice", "1"])
    def test_qemu_over_network_is_rejected(self, command):
        with patch("sys.stderr", new=io.StringIO()):
            self.assertEqual(1, main())
