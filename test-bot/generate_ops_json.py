#!/usr/bin/env python3
"""Generate ops.json for Minecraft server with offline-mode UUIDs."""

import argparse
import hashlib
import json
import sys


def generate_offline_uuid(username: str) -> str:
    """Generate an offline-mode UUID for a Minecraft username.

    Minecraft offline-mode UUIDs are version 3 UUIDs derived from
    MD5 hash of 'OfflinePlayer:<username>'.
    """
    data = f"OfflinePlayer:{username}".encode()
    digest = bytearray(hashlib.md5(data).digest())

    # Set version to 3 (MD5 hash)
    digest[6] = (digest[6] & 0x0f) | 0x30
    # Set variant to RFC 4122
    digest[8] = (digest[8] & 0x3f) | 0x80

    h = digest.hex()
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"


def generate_ops_json(usernames: list[str]) -> list[dict]:
    """Generate ops.json entries for the given usernames."""
    return [
        {
            "uuid": generate_offline_uuid(name),
            "name": name,
            "level": 4,
            "bypassesPlayerLimit": False
        }
        for name in usernames
    ]


def main():
    parser = argparse.ArgumentParser(description="Generate ops.json for Minecraft server")
    parser.add_argument("usernames", nargs="+", help="Usernames to add as operators")
    parser.add_argument("-o", "--output", help="Output file (default: stdout)")
    args = parser.parse_args()

    ops = generate_ops_json(args.usernames)
    output = json.dumps(ops, indent=2)

    if args.output:
        with open(args.output, "w") as f:
            f.write(output + "\n")
    else:
        print(output)


if __name__ == "__main__":
    main()
