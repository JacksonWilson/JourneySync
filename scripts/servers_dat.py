"""Write a Minecraft 1.7.10 servers.dat holding a single entry.

    python servers_dat.py <path to servers.dat> <name> <address>

Used by pack-test.ps1 so test instances list only the local test server: one click to join, and no way to join a real
server from a test instance by accident.
"""
import struct
import sys


def tag_string(name, value):
    data = value.encode("utf-8")
    key = name.encode("utf-8")
    return b"\x08" + struct.pack(">H", len(key)) + key + struct.pack(">H", len(data)) + data


def main(path, name, address):
    entry = tag_string("name", name) + tag_string("ip", address) + b"\x00"
    servers = b"\x09" + struct.pack(">H", 7) + b"servers" + b"\x0a" + struct.pack(">i", 1) + entry
    root = b"\x0a" + struct.pack(">H", 0) + servers + b"\x00"
    with open(path, "wb") as f:
        f.write(root)


if __name__ == "__main__":
    main(*sys.argv[1:4])
