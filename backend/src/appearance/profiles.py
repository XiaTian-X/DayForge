"""Runtime profile names; changes require explicit reader/backup compatibility."""

from types import MappingProxyType


IMAGE_PROFILES = MappingProxyType({"image/png": "png-v1", "image/svg+xml": "svg-v1"})
