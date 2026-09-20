#!/usr/bin/env python3
"""Select one authorized physical device; never fall back to an emulator."""

import os
import subprocess
import sys
from pathlib import Path


def select_device(listing: str, requested: str | None) -> str:
    devices = {}
    for line in listing.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0] != "List" and not line.startswith("*"):
            devices[parts[0]] = parts[1]
    if requested:
        if devices.get(requested) != "device":
            raise ValueError(
                "Selected Android device is missing, offline, or unauthorized"
            )
        selected = requested
    else:
        ready = [serial for serial, state in devices.items() if state == "device"]
        if len(ready) != 1:
            raise ValueError(
                "Connect exactly one authorized physical device or set ANDROID_SERIAL"
            )
        selected = ready[0]
    if selected.startswith("emulator-"):
        raise ValueError("Android tests require a physical device; emulator rejected")
    return selected


def main() -> int:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb = str(Path(sdk) / "platform-tools/adb") if sdk else "adb"
    try:
        listing = subprocess.check_output([adb, "devices"], text=True)
        serial = select_device(listing, os.environ.get("ANDROID_SERIAL"))
        for prop in ("ro.kernel.qemu", "ro.boot.qemu"):
            value = subprocess.check_output(
                [adb, "-s", serial, "shell", "getprop", prop], text=True
            ).strip()
            if value == "1":
                raise ValueError(
                    "Android tests require a physical device; QEMU device rejected"
                )
        print(serial)
        return 0
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"Android device check failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
