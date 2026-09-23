"""Strict PNG framing and bounded deflate preflight before native pixel decoding."""

from dataclasses import dataclass
import hashlib
import re
import struct
import zlib

from src.v2.appearance import IconBlob


SIGNATURE = b"\x89PNG\r\n\x1a\n"
MAX_BYTES = 2_097_152
MAX_METADATA_CHUNK = 262_144
MAX_METADATA_TOTAL = 1_048_576
MAX_CHUNKS = 4096
DEPTHS = {0: {1, 2, 4, 8, 16}, 2: {8, 16}, 3: {1, 2, 4, 8}, 4: {8, 16}, 6: {8, 16}}
CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}
TEXT = {b"tEXt", b"zTXt", b"iTXt"}
BEFORE_PALETTE = {b"cHRM", b"gAMA", b"iCCP", b"sBIT", b"sRGB"}
BEFORE_DATA = BEFORE_PALETTE | {b"PLTE", b"tRNS", b"bKGD", b"hIST", b"pHYs"}
ALLOWED = BEFORE_DATA | TEXT | {b"IHDR", b"IDAT", b"IEND", b"tIME"}
PASSES = (
    (0, 0, 8, 8),
    (4, 0, 8, 8),
    (0, 4, 4, 8),
    (2, 0, 4, 4),
    (0, 2, 2, 4),
    (1, 0, 2, 2),
    (0, 1, 1, 2),
)


class PngValidationError(ValueError):
    """Invalid, unsupported or resource-exhausting PNG; no partial success."""


def require(value: bool, code: str) -> None:
    if not value:
        raise PngValidationError(code)


def inflate(data: bytes, limit: int) -> bytes:
    """One complete zlib stream, no dictionary, suffix, concatenation or overrun."""
    inflater = zlib.decompressobj()
    try:
        result = inflater.decompress(data, limit + 1)
    except zlib.error as error:
        raise PngValidationError("PNG_DEFLATE") from error
    require(len(result) <= limit, "PNG_EXPANSION_LIMIT")
    require(
        inflater.eof and not inflater.unused_data and not inflater.unconsumed_tail,
        "PNG_DEFLATE",
    )
    return result


def keyword(data: bytes) -> bytes:
    end = data.find(b"\0")
    require(1 <= end <= 79, "PNG_METADATA")
    name = data[:end]
    require(
        all(32 <= byte <= 126 or 161 <= byte <= 255 for byte in name)
        and not name.startswith(b" ")
        and not name.endswith(b" ")
        and b"  " not in name,
        "PNG_METADATA",
    )
    return data[end + 1 :]


def split_null(data: bytes) -> tuple[bytes, bytes]:
    before, separator, after = data.partition(b"\0")
    require(bool(separator), "PNG_METADATA")
    return before, after


def utf8(data: bytes) -> None:
    require(b"\0" not in data, "PNG_METADATA")
    try:
        data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise PngValidationError("PNG_METADATA") from error


def metadata_size(
    kind: bytes, data: bytes, color: int, depth: int, palette: int
) -> int:
    require(len(data) <= MAX_METADATA_CHUNK, "PNG_METADATA_LIMIT")
    if kind in TEXT | {b"iCCP"}:
        tail = keyword(data)
        if kind == b"tEXt":
            require(b"\0" not in tail, "PNG_METADATA")
        elif kind in {b"zTXt", b"iCCP"}:
            require(tail[:1] == b"\0", "PNG_METADATA")
            expanded = inflate(tail[1:], MAX_METADATA_CHUNK)
            if kind == b"zTXt":
                require(b"\0" not in expanded, "PNG_METADATA")
            else:
                require(
                    len(expanded) >= 132
                    and expanded[36:40] == b"acsp"
                    and struct.unpack_from(">I", expanded)[0] == len(expanded)
                    and expanded[16:20] == (b"GRAY" if color in {0, 4} else b"RGB "),
                    "PNG_METADATA",
                )
                count = struct.unpack_from(">I", expanded, 128)[0]
                require(
                    count <= 4096 and 132 + 12 * count <= len(expanded), "PNG_METADATA"
                )
                for index in range(count):
                    offset, size = struct.unpack_from(">II", expanded, 136 + index * 12)
                    require(
                        offset >= 132 + 12 * count
                        and size > 0
                        and offset + size <= len(expanded)
                        and offset % 4 == 0,
                        "PNG_METADATA",
                    )
            return len(data) + len(expanded)
        else:
            require(
                len(tail) >= 2 and tail[0] in {0, 1} and tail[1] == 0, "PNG_METADATA"
            )
            language, rest = split_null(tail[2:])
            translated, body = split_null(rest)
            require(
                not language
                or re.fullmatch(rb"[A-Za-z]{1,8}(?:-[A-Za-z0-9]{1,8})*", language)
                is not None,
                "PNG_METADATA",
            )
            utf8(translated)
            expanded = inflate(body, MAX_METADATA_CHUNK) if tail[0] else body
            utf8(expanded)
            return len(data) + (len(expanded) if tail[0] else 0)
    elif kind == b"gAMA":
        require(len(data) == 4 and int.from_bytes(data) > 0, "PNG_METADATA")
    elif kind == b"cHRM":
        require(len(data) == 32, "PNG_METADATA")
        require(
            all(value <= 100000 for value in struct.unpack(">8I", data)), "PNG_METADATA"
        )
    elif kind == b"sRGB":
        require(len(data) == 1 and data[0] <= 3, "PNG_METADATA")
    elif kind == b"pHYs":
        require(len(data) == 9 and data[-1] in {0, 1}, "PNG_METADATA")
    elif kind == b"sBIT":
        require(
            len(data) == (3 if color == 3 else CHANNELS[color])
            and all(1 <= value <= (8 if color == 3 else depth) for value in data),
            "PNG_METADATA",
        )
    elif kind == b"hIST":
        require(palette > 0 and len(data) == 2 * palette, "PNG_METADATA")
    elif kind == b"bKGD":
        require(
            len(data) == (1 if color == 3 else 2 if color in {0, 4} else 6),
            "PNG_METADATA",
        )
        if color == 3:
            require(palette > 0 and data[0] < palette, "PNG_METADATA")
        else:
            require(
                all(
                    value < 2**depth
                    for value in struct.unpack(">" + "H" * (len(data) // 2), data)
                ),
                "PNG_METADATA",
            )
    elif kind == b"tIME":
        require(
            len(data) == 7
            and 1 <= data[2] <= 12
            and 1 <= data[3] <= 31
            and data[4] <= 23
            and data[5] <= 59
            and data[6] <= 60,
            "PNG_METADATA",
        )
    return len(data)


def scanlines(
    width: int, height: int, depth: int, color: int, interlace: int
) -> list[tuple[int, int, int]]:
    result = []
    for x, y, dx, dy in PASSES if interlace else ((0, 0, 1, 1),):
        columns = max(0, (width - x + dx - 1) // dx)
        rows = max(0, (height - y + dy - 1) // dy)
        if columns and rows:
            result.append((columns, rows, (columns * depth * CHANNELS[color] + 7) // 8))
    return result


def validate_rows(
    raw: bytes, passes: list[tuple[int, int, int]], depth: int, color: int, palette: int
) -> None:
    position = 0
    for columns, rows, row_bytes in passes:
        previous = bytearray(row_bytes)
        for _ in range(rows):
            filtering = raw[position]
            require(filtering <= 4, "PNG_FILTER")
            position += 1
            if color == 3:
                # Validate palette indices after reversing filters. Decoders may
                # otherwise silently replace out-of-range indices with black.
                row = bytearray(raw[position : position + row_bytes])
                for index, value in enumerate(row):
                    left = row[index - 1] if index else 0
                    above = previous[index]
                    corner = previous[index - 1] if index else 0
                    if filtering == 1:
                        predictor = left
                    elif filtering == 2:
                        predictor = above
                    elif filtering == 3:
                        predictor = (left + above) // 2
                    elif filtering == 4:
                        p = left + above - corner
                        a, b, c = abs(p - left), abs(p - above), abs(p - corner)
                        predictor = (
                            left if a <= b and a <= c else above if b <= c else corner
                        )
                    else:
                        predictor = 0
                    row[index] = (value + predictor) & 255
                for pixel in range(columns):
                    shift = 8 - depth - pixel * depth % 8
                    require(
                        (row[pixel * depth // 8] >> shift) & (2**depth - 1) < palette,
                        "PNG_PALETTE",
                    )
                previous = row
            position += row_bytes
    require(position == len(raw), "PNG_SCANLINES")


@dataclass(frozen=True)
class PngInspection:
    width: int
    height: int
    depth: int
    color: int
    interlace: int


def inspect_png_structure(data: bytes, expected: IconBlob) -> PngInspection:
    require(expected.media_type == "image/png", "PNG_MEDIA_TYPE")
    require(
        len(data) <= MAX_BYTES and len(data) == expected.byte_length, "PNG_BYTE_LENGTH"
    )
    require(hashlib.sha256(data).hexdigest() == expected.sha256, "PNG_HASH")
    require(data.startswith(SIGNATURE), "PNG_SIGNATURE")
    position, chunks, metadata, palette = 8, 0, 0, 0
    width = height = depth = color = interlace = 0
    seen: set[bytes] = set()
    compressed = bytearray()
    data_ended = False
    while position < len(data):
        require(position + 12 <= len(data), "PNG_TRUNCATED")
        length = struct.unpack_from(">I", data, position)[0]
        require(length <= len(data) - position - 12, "PNG_TRUNCATED")
        kind = data[position + 4 : position + 8]
        body = data[position + 8 : position + 8 + length]
        crc = struct.unpack_from(">I", data, position + 8 + length)[0]
        require(zlib.crc32(body, zlib.crc32(kind)) == crc, "PNG_CRC")
        require(kind not in {b"acTL", b"fcTL", b"fdAT"}, "PNG_ANIMATION")
        require(kind in ALLOWED, "PNG_CHUNK")
        chunks += 1
        require(chunks <= MAX_CHUNKS, "PNG_CHUNK_LIMIT")
        require(chunks != 1 or kind == b"IHDR", "PNG_ORDER")
        require(kind not in seen or kind in TEXT | {b"IDAT"}, "PNG_DUPLICATE")
        require(not (kind in BEFORE_DATA and b"IDAT" in seen), "PNG_ORDER")
        require(not (kind in BEFORE_PALETTE and b"PLTE" in seen), "PNG_ORDER")
        if kind == b"IHDR":
            require(chunks == 1 and length == 13, "PNG_HEADER")
            width, height, depth, color, compression, filtering, interlace = (
                struct.unpack(">IIBBBBB", body)
            )
            require(
                (width, height) == (expected.width, expected.height), "PNG_DIMENSIONS"
            )
            require(
                color in DEPTHS
                and depth in DEPTHS[color]
                and compression == filtering == 0
                and interlace in {0, 1},
                "PNG_HEADER",
            )
        elif kind == b"PLTE":
            require(
                color not in {0, 4} and 0 < length <= 768 and length % 3 == 0,
                "PNG_PALETTE",
            )
            require(
                b"tRNS" not in seen and b"bKGD" not in seen and b"hIST" not in seen,
                "PNG_ORDER",
            )
            palette = length // 3
            require(color != 3 or palette <= 2**depth, "PNG_PALETTE")
        elif kind == b"tRNS":
            require(color in {0, 2, 3}, "PNG_TRANSPARENCY")
            if color == 3:
                require(palette > 0 and 0 < length <= palette, "PNG_TRANSPARENCY")
            else:
                require(length == (2 if color == 0 else 6), "PNG_TRANSPARENCY")
                require(
                    all(
                        value < 2**depth
                        for value in struct.unpack(">" + "H" * (length // 2), body)
                    ),
                    "PNG_TRANSPARENCY",
                )
        elif kind == b"IDAT":
            require(not data_ended and (color != 3 or palette > 0), "PNG_ORDER")
            compressed.extend(body)
        elif kind == b"IEND":
            require(
                length == 0 and b"IDAT" in seen and position + 12 == len(data),
                "PNG_END",
            )
        else:
            require(
                not (
                    kind == b"sRGB"
                    and b"iCCP" in seen
                    or kind == b"iCCP"
                    and b"sRGB" in seen
                ),
                "PNG_METADATA",
            )
            metadata += metadata_size(kind, body, color, depth, palette)
            require(metadata <= MAX_METADATA_TOTAL, "PNG_METADATA_LIMIT")
        if kind != b"IDAT" and b"IDAT" in seen:
            data_ended = True
        seen.add(kind)
        position += length + 12
    require(b"IEND" in seen, "PNG_END")
    passes = scanlines(width, height, depth, color, interlace)
    expected_length = sum(rows * (row_bytes + 1) for _, rows, row_bytes in passes)
    raw = inflate(bytes(compressed), expected_length)
    require(len(raw) == expected_length, "PNG_SCANLINES")
    validate_rows(raw, passes, depth, color, palette)
    return PngInspection(width, height, depth, color, interlace)
