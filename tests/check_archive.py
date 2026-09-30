#!/usr/bin/env python3
"""Validate a synthetic Android archive using only Python's standard library."""

import json
import sys
import zipfile
from pathlib import Path


def main(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None, "ZIP CRC mismatch"
        names = archive.namelist()
        assert names == [
            "items.jsonl", "plain.txt", "manifest.json",
            "assets/asset_1.png", "assets/asset_2.txt",
        ]
        items = [json.loads(row) for row in archive.read("items.jsonl").decode("utf-8").splitlines()]
        assert [item["t"] for item in items] == ["text", "text", "asset", "text", "asset"]
        assert items[0]["v"] == "甲\n2026-01-01 10:00\n看这张图"
        assert items[2] == {"t": "asset", "file": "assets/asset_1.png", "name": "截图.png"}
        assert items[3]["v"] == "文件后续\t\"内容\"\\尾\x01\n"
        assert archive.read("plain.txt").decode("utf-8") == (
            "甲\n2026-01-01 10:00\n看这张图\n\n乙\n2026-01-01 10:00\n[图片]\n"
            "文件后续\t\"内容\"\\尾\x01\n"
        )
        manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
        assert manifest["device"] == "Android"
        assert manifest["textItems"] == 3
        assert manifest["assets"] == [
            {"file": "asset_1.png", "originalName": "截图.png", "status": "ok"},
            {"file": "asset_2.txt", "originalName": "附件.txt", "status": "ok"},
        ]
        assert archive.read("assets/asset_1.png") == bytes([0, 1, 2, 3])
        assert archive.read("assets/asset_2.txt") == b"sample file"
    print("Android ZIP and iOS-compatible v0.6 manifest passed")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
