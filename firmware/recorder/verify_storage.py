#!/usr/bin/env python3
"""检查构建后的分区和 HEX，防止程序或烧录镜像侵入配对存储区。仅标准库、只读。"""
import argparse
from pathlib import Path
import re


def partition(dts, label):
    node = re.search(rf"\b{label}:\s*partition@[0-9a-f]+\s*\{{(.*?)\}};", dts, re.S)
    if node is None:
        raise ValueError(f"missing partition: {label}")
    reg = re.search(r"\breg\s*=\s*<\s*(0x[0-9a-f]+|[0-9]+)\s+(0x[0-9a-f]+|[0-9]+)\s*>", node[1])
    if reg is None:
        raise ValueError(f"missing address/size: {label}")
    return tuple(int(value, 0) for value in reg.groups())


def verify(directory):
    dts = (directory / "zephyr.dts").read_text()
    config = (directory / ".config").read_text()
    code, code_size = partition(dts, "recorder_partition")
    storage, storage_size = partition(dts, "storage_partition")
    assert "CONFIG_USE_DT_CODE_PARTITION=y" in config
    assert "CONFIG_SETTINGS_ZMS=y" in config
    assert re.search(r"zephyr,code-partition\s*=\s*&recorder_partition", dts)
    assert re.search(r"zephyr,settings-partition\s*=\s*&storage_partition", dts)
    assert code == 0 and code_size > 0 and code + code_size <= storage
    assert storage_size >= 2 * 4096
    base = 0
    ranges = []
    for line in (directory / "zephyr.hex").read_text().splitlines():
        assert line.startswith(":"), "invalid HEX record"
        record = bytes.fromhex(line[1:])
        assert sum(record) % 256 == 0 and len(record) == record[0] + 5, "invalid HEX checksum/length"
        size, kind = record[0], record[3]
        offset = int.from_bytes(record[1:3], "big")
        if kind == 4:
            base = int.from_bytes(record[4:6], "big") << 16
        elif kind == 2:
            base = int.from_bytes(record[4:6], "big") << 4
        elif kind == 0 and size:
            start, end = base + offset, base + offset + size
            assert end <= storage or start >= storage + storage_size, "HEX overlaps pairing storage"
            if start < storage:
                assert code <= start and end <= code + code_size, "HEX exceeds program partition"
                ranges.append((start, end))
    assert ranges, "empty firmware image"
    print(f"program: 0x{code:06x}..0x{code + code_size - 1:06x} ({code_size // 1024} KiB)")
    print(f"settings: 0x{storage:06x}..0x{storage + storage_size - 1:06x} ({storage_size // 1024} KiB)")
    print(f"HEX program data: 0x{min(a for a, _ in ranges):06x}..0x{max(b for _, b in ranges) - 1:06x}")
    print("PASS: link partition and HEX exclude pairing storage")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zephyr_dir", type=Path, help="构建产物中包含 zephyr.dts/.config/zephyr.hex 的目录")
    verify(parser.parse_args().zephyr_dir)
