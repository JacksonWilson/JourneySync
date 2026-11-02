"""Compare the JourneyMap tiles of the two dev clients and the server's JourneySync store.

For each tile it counts explored (non-transparent) pixels in each copy, and how many pixels one client has that
the other lacks. After a sync the "only in" columns should be 0.
    python scripts/compare-maps.py [--dim 0] [--kind day]
"""
import argparse
import pathlib

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent


def jm_world_dir(client_dir):
    mp = client_dir / "journeymap" / "data" / "mp"
    dirs = [d for d in mp.glob("*") if d.is_dir()] if mp.is_dir() else []
    return max(dirs, key=lambda d: d.stat().st_mtime) if dirs else None


def tiles(base, dim, kind):
    folder = base / f"DIM{dim}" / kind if base else None
    return {p.name: p for p in folder.glob("*.png")} if folder and folder.is_dir() else {}


def opaque(path):
    if path is None:
        return set()
    alpha = Image.open(path).convert("RGBA").getchannel("A")
    return {i for i, a in enumerate(alpha.getdata()) if a}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dim", default="0")
    parser.add_argument("--kind", default="day")
    args = parser.parse_args()

    a_dir = jm_world_dir(ROOT / "run" / "client")
    b_dir = jm_world_dir(ROOT / "run" / "client2")
    s_dir = ROOT / "run" / "server" / "world" / "journeysync" / "cells"
    print(f"Developer: {a_dir}\nAlex:      {b_dir}\nServer:    {s_dir}\n")

    a, b, s = (tiles(d, args.dim, args.kind) for d in (a_dir, b_dir, s_dir))
    print(f"{'tile':<14}{'Developer':>10}{'Alex':>10}{'server':>10}{'only Dev':>10}{'only Alex':>10}{'srv lacks':>10}")
    for name in sorted(set(a) | set(b) | set(s)):
        pa, pb, ps = opaque(a.get(name)), opaque(b.get(name)), opaque(s.get(name))
        print(
            f"{name:<14}{len(pa):>10}{len(pb):>10}{len(ps):>10}"
            f"{len(pa - pb):>10}{len(pb - pa):>10}{len((pa | pb) - ps):>10}"
        )


if __name__ == "__main__":
    main()
