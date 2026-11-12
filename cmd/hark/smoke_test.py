#!/usr/bin/env python3
"""Exercise a built hark binary using only disposable synthetic SQLite data.

Usage: python3 cmd/hark/smoke_test.py /tmp/hark
"""

import datetime as dt
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading


def main():
    binary = str(Path(sys.argv[1]).resolve())

    def run(*args, code=0, as_json=True):
        command = [binary, *map(str, args)]
        if as_json:
            command.append("--json")
        result = subprocess.run(command, capture_output=True, text=True, timeout=3)
        assert result.returncode == code, (args, result.returncode, result.stderr)
        if code:
            assert result.stdout == "" and result.stderr, args
            return result.stderr
        assert result.stderr == "", (args, result.stderr)
        return json.loads(result.stdout) if as_json else result.stdout

    with tempfile.TemporaryDirectory(prefix="hark-cli-smoke-") as root:
        root = Path(root)
        empty = root / "missing"
        for args in [("status",), ("speakers",), ("timeline",), ("search", "接口")]:
            assert run(*args, "--data-dir", empty)["db_exists"] is False
            assert "本地暂无录音存档" in run(*args, "--data-dir", empty, as_json=False)
        assert "本地暂无录音存档" in run("get", "missing", "--data-dir", empty, code=1)
        assert not empty.exists(), "empty queries created a directory"

        data = root / "live"
        data.mkdir()
        db_path = data / "hark.db"
        db = sqlite3.connect(db_path, isolation_level=None)
        try:
            assert db.execute("PRAGMA journal_mode=WAL").fetchone()[0] == "wal"
            db.execute("PRAGMA wal_autocheckpoint=0")
            db.executescript("""
                CREATE TABLE transcripts (name TEXT PRIMARY KEY, started_at TEXT, duration_s REAL,
                    speech_s REAL, engine TEXT, model TEXT, text TEXT, segments TEXT);
                CREATE TABLE speakers (id TEXT PRIMARY KEY, weight REAL, merged_into TEXT,
                    first_seen TEXT, sample TEXT, sample_name TEXT, sample_start REAL, display_name TEXT);
                INSERT INTO speakers VALUES ('spk_001', 50, NULL, '2026-11-01T10:00:00',
                    '讨论接口', '20261101-100000.opus', 2.5, '老王 Wang');
                INSERT INTO speakers VALUES ('spk_002', 10, 'spk_001', NULL, NULL, NULL, NULL, '旧人物');
                INSERT INTO speakers VALUES ('spk_003', 5, 'spk_002', NULL, NULL, NULL, NULL, '');
                INSERT INTO speakers VALUES ('spk_004', 20, NULL, NULL, NULL, NULL, NULL, 'Alice');
            """)
            now = dt.datetime.now().replace(hour=14, minute=30, second=0, microsecond=0)
            names = []
            for ago, duration, speech, speaker in [(0, 30, 20, "spk_001"), (1, 40, 30, "spk_003"), (40, 20, 10, None)]:
                day = now - dt.timedelta(days=ago)
                name = day.strftime("%Y%m%d-%H%M%S.opus")
                segments = [] if speaker is None else [
                    {"start": 0, "end": 2, "text": "开场白", "speaker": speaker},
                    {"start": 2, "end": 20, "text": "接口明天上线", "speaker": speaker},
                ]
                text = "纯文本存档关键词" if speaker is None else "开场白。接口明天上线。"
                db.execute("INSERT INTO transcripts VALUES (?, ?, ?, ?, '', '', ?, ?)",
                           (name, day.isoformat(), duration, speech, text, json.dumps(segments, ensure_ascii=False)))
                names.append(name)
            assert Path(str(db_path) + "-wal").stat().st_size > 0

            st = run("status", "--data-dir", data)
            assert (st["total_recordings"], st["total_duration_s"], st["total_speech_s"], st["speaker_count"]) == (3, 90, 60, 2)
            assert st["latest_name"] == names[0] and st["db_exists"] is True
            people = run("speakers", "--data-dir", data)["items"]
            assert [sp["id"] for sp in people] == ["spk_001", "spk_004"]
            assert people[0]["merged_ids"] == ["spk_002", "spk_003"]
            assert people[0]["sample_start"] == 2.5
            timeline = run("timeline", "--data-dir", data)
            assert len(timeline["items"]) == 1 and timeline["limit"] == 200
            for who in ("老王", "wAnG", "spk_003"):
                timeline = run("timeline", "--date", "7d", "--speaker", who, "--data-dir", data)
                assert [it["name"] for it in timeline["items"]] == [names[1], names[0]]
                assert timeline["items"][0]["segments"][0]["speaker_id"] == "spk_001"
            assert run("timeline", "--date", "yesterday", "--data-dir", data)["items"][0]["name"] == names[1]
            assert run("timeline", "--date", "all", "--limit", "1", "--offset", "1", "--data-dir", data)["items"][0]["name"] == names[1]

            for args in [("search", "接口", "--date", "7d"), ("search", "--date=7d", "接口")]:
                page = run(*args, "--data-dir", data)
                assert page["total"] == 2 and len(page["items"]) == 2
                assert page["items"][0]["matched_segments"][0]["text"] == "接口明天上线"
                assert len(page["items"][0]["matched_segments"]) == 1
            assert run("search", "", "--speaker", "老王", "--data-dir", data)["total"] == 2
            assert "纯文本" in run("search", "关键词", "--date", "all", "--data-dir", data)["items"][0]["matched_segments"][0]["text"]
            assert run("get", names[0][:-5], "--data-dir", data)["items"][0]["name"] == names[0]
            assert len(run("get", "--json", names[0], "--data-dir", data, names[1])["items"]) == 2
            assert "录音不存在" in run("get", names[0], "absent", "--data-dir", data, code=1)
            for args in [("status",), ("speakers",), ("timeline", "--date", "7d"), ("search", "接口"), ("get", names[0])]:
                output = run(*args, "--data-dir", data, as_json=False)
                assert output.strip()
                if args[0] == "timeline":
                    print("CLI_SAMPLE:\n" + output, end="")

            # Keep a real write transaction open while launching separate reader processes.
            pending = now.replace(hour=15).strftime("%Y%m%d-%H%M%S.opus")
            db.execute("BEGIN IMMEDIATE")
            db.execute("INSERT INTO transcripts VALUES (?, ?, 10, 8, '', '', '并发提交', '[]')", (pending, now.isoformat()))
            assert run("status", "--data-dir", data)["total_recordings"] == 3
            assert run("search", "并发提交", "--data-dir", data)["total"] == 0
            assert run("timeline", "--date", "all", "--data-dir", data)["total"] == 3
            assert len(run("speakers", "--data-dir", data)["items"]) == 2
            assert run("get", names[0], "--data-dir", data)["items"]
            assert "录音不存在" in run("get", pending, "--data-dir", data, code=1)
            db.execute("COMMIT")
            assert run("status", "--data-dir", data)["total_recordings"] == 4
            assert run("get", pending, "--data-dir", data)["items"][0]["text"] == "并发提交"

            errors = []

            def write_live():
                try:
                    with sqlite3.connect(db_path, isolation_level=None) as writer:
                        for i in range(40):
                            writer.execute("INSERT INTO transcripts VALUES (?, ?, 1, 1, '', '', '实时写入', '[]')",
                                           (f"live-{i:03d}.opus", now.isoformat()))
                except Exception as exc:
                    errors.append(exc)

            writer = threading.Thread(target=write_live)
            writer.start()
            try:
                for _ in range(5):
                    run("status", "--data-dir", data)
                    run("timeline", "--date", "today", "--limit", "5", "--data-dir", data)
                    run("search", "实时", "--data-dir", data)
            finally:
                writer.join(timeout=3)
            assert not writer.is_alive() and not errors, errors
            assert run("status", "--data-dir", data)["total_recordings"] == 44
        finally:
            db.close()
    print("CLI_SMOKE_OK: empty archive; status/speakers/timeline/search/get text+JSON; interspersed flags; merged speakers; WAL isolation/commit visibility; 40 concurrent writes")


if __name__ == "__main__":
    main()
