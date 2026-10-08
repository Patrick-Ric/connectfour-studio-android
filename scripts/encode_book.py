#!/usr/bin/env python3
"""Encode the BitBully "12-ply-dist" opening book as compact .cfb asset.

Input:  book_12ply_distances.dat from the Python package bitbully-databases
        (MIT licence, https://github.com/MarkusThill/bitbully-databases):
        4 200 899 records of 5 bytes (big-endian signed 32-bit Huffman key,
        signed 8-bit distance value), sorted by key.
Output: "CFB1" + entry count (big-endian int32) + zlib stream containing
        the key deltas as unsigned LEB128 varints (first delta relative to
        -2**31) followed by all value bytes.  ~5.4 MB instead of 21 MB.

The Kotlin side (core/.../OpeningBook.kt, BookCodec) decodes this losslessly;
the unit tests check the entry count and known positions.

Usage:
  python3 scripts/encode_book.py <book_12ply_distances.dat> app/src/main/assets/book/book_12ply_dist.cfb
"""

import struct
import sys
import zlib

ENTRY = 5
EXPECTED = 4_200_899


def main(src, dst):
    data = open(src, "rb").read()
    if len(data) % ENTRY:
        raise SystemExit("unexpected file size")
    n = len(data) // ENTRY
    if n != EXPECTED:
        print(f"warning: {n} entries, expected {EXPECTED}", file=sys.stderr)
    deltas = bytearray()
    values = bytearray(n)
    prev = -2 ** 31
    for i in range(n):
        key = int.from_bytes(data[i * ENTRY:i * ENTRY + 4], "big", signed=True)
        values[i] = data[i * ENTRY + 4]
        delta = key - prev
        if i and delta <= 0:
            raise SystemExit("keys not strictly ascending")
        prev = key
        while delta >= 0x80:
            deltas.append((delta & 0x7F) | 0x80)
            delta >>= 7
        deltas.append(delta)
    payload = zlib.compress(bytes(deltas) + bytes(values), 9)
    with open(dst, "wb") as f:
        f.write(b"CFB1")
        f.write(struct.pack(">i", n))
        f.write(payload)
    print(f"{n} entries -> {dst} ({8 + len(payload)} bytes)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
