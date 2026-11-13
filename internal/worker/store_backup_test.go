package worker

import (
	"bytes"
	"database/sql"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/riggedlab/hark/internal/protocol"
)

func newBackupTestStore(t *testing.T) *Store {
	t.Helper()
	s, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	if err := s.SaveTranscript(protocol.TranscriptPayload{Name: "recording.opus", Text: "original"}); err != nil {
		t.Fatal(err)
	}
	return s
}

func checkBackupDirectory(t *testing.T, path string) {
	t.Helper()
	entries, err := os.ReadDir(filepath.Dir(path))
	if err != nil || len(entries) != 1 || entries[0].Name() != filepath.Base(path) {
		t.Fatalf("backup directory contains unexpected files: %v, %v", entries, err)
	}
}

func TestBackupToReplacesOldBackup(t *testing.T) {
	s := newBackupTestStore(t)
	dest := filepath.Join(t.TempDir(), "backup's.db")
	if err := s.BackupTo(dest); err != nil {
		t.Fatal(err)
	}
	oldInfo, err := os.Stat(dest)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.SaveTranscript(protocol.TranscriptPayload{Name: "recording.opus", Text: "updated"}); err != nil {
		t.Fatal(err)
	}
	if err := s.BackupTo(dest); err != nil {
		t.Fatal(err)
	}
	newInfo, err := os.Stat(dest)
	if err != nil {
		t.Fatal(err)
	}
	if os.SameFile(oldInfo, newInfo) {
		t.Fatal("backup was overwritten in place instead of replaced")
	}
	db, err := sql.Open("sqlite", dest)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var text, integrity string
	if err := db.QueryRow(`SELECT text FROM transcripts WHERE name = 'recording.opus'`).Scan(&text); err != nil || text != "updated" {
		t.Fatalf("backup transcript = %q, %v", text, err)
	}
	if err := db.QueryRow(`PRAGMA integrity_check`).Scan(&integrity); err != nil || integrity != "ok" {
		t.Fatalf("backup integrity = %q, %v", integrity, err)
	}
	checkBackupDirectory(t, dest)
}

func TestBackupToRejectsActiveDatabaseFiles(t *testing.T) {
	for _, suffix := range []string{"", "-wal", "-shm"} {
		t.Run("database"+suffix, func(t *testing.T) {
			s := newBackupTestStore(t)
			source := s.DBPath() + suffix
			cwd, err := os.Getwd()
			if err != nil {
				t.Fatal(err)
			}
			relative, err := filepath.Rel(cwd, source)
			if err != nil {
				t.Fatal(err)
			}
			aliasDir := t.TempDir()
			symlink := filepath.Join(aliasDir, "symlink")
			hardlink := filepath.Join(aliasDir, "hardlink")
			dirLink := filepath.Join(aliasDir, "directory")
			if err := os.Symlink(source, symlink); err != nil {
				t.Fatal(err)
			}
			if err := os.Link(source, hardlink); err != nil {
				t.Fatal(err)
			}
			if err := os.Symlink(filepath.Dir(source), dirLink); err != nil {
				t.Fatal(err)
			}
			for name, dest := range map[string]string{
				"absolute":          source,
				"relative":          relative,
				"symlink":           symlink,
				"hardlink":          hardlink,
				"directory symlink": filepath.Join(dirLink, filepath.Base(source)),
			} {
				t.Run(name, func(t *testing.T) {
					// Reads can update SQLite's SHM bookkeeping. Snapshot each
					// attempt separately to isolate the effect of BackupTo.
					before, err := os.ReadFile(source)
					if err != nil {
						t.Fatal(err)
					}
					if err := s.BackupTo(dest); err == nil || !strings.Contains(err.Error(), "备份目标不能是当前数据库") {
						t.Fatalf("expected active database rejection, got %v", err)
					}
					after, err := os.ReadFile(source)
					if err != nil || !bytes.Equal(before, after) {
						t.Fatalf("active database file changed: %v", err)
					}
					if detail, err := s.GetDetail("recording.opus"); err != nil || detail.Text != "original" {
						t.Fatalf("live transcript changed: %+v, %v", detail, err)
					}
				})
			}
			if err := s.Close(); err != nil {
				t.Fatal(err)
			}
			reopened, err := OpenStore(filepath.Dir(s.DBPath()))
			if err != nil {
				t.Fatal(err)
			}
			defer reopened.Close()
			if detail, err := reopened.GetDetail("recording.opus"); err != nil || detail.Text != "original" {
				t.Fatalf("live database did not survive reopening: %+v, %v", detail, err)
			}
		})
	}
}

func TestBackupToRejectsAbsentSidecars(t *testing.T) {
	s := newBackupTestStore(t)
	if _, err := s.db.Exec(`PRAGMA journal_mode=DELETE`); err != nil {
		t.Fatal(err)
	}
	alias := filepath.Join(t.TempDir(), "live")
	if err := os.Symlink(filepath.Dir(s.DBPath()), alias); err != nil {
		t.Fatal(err)
	}
	for _, suffix := range []string{"-wal", "-shm"} {
		dest := filepath.Join(alias, filepath.Base(s.DBPath())+suffix)
		if _, err := os.Stat(dest); !os.IsNotExist(err) {
			t.Fatalf("expected absent sidecar: %v", err)
		}
		if err := s.BackupTo(dest); err == nil || !strings.Contains(err.Error(), "备份目标不能是当前数据库") {
			t.Fatalf("expected absent sidecar rejection, got %v", err)
		}
		if _, err := os.Stat(dest); !os.IsNotExist(err) {
			t.Fatalf("created a sidecar file: %v", err)
		}
	}
}

func TestBackupToRejectsActiveDatabaseAfterWorkingDirectoryChange(t *testing.T) {
	root := t.TempDir()
	t.Chdir(root)
	s, err := OpenStore("live")
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.SaveTranscript(protocol.TranscriptPayload{Name: "recording.opus", Text: "original"}); err != nil {
		t.Fatal(err)
	}
	t.Chdir(t.TempDir())
	// A different file at the original relative path must not be mistaken for
	// the database still open in SQLite after the working directory changes.
	if err := os.Mkdir("live", 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join("live", "hark.db"), []byte("unrelated"), 0o600); err != nil {
		t.Fatal(err)
	}
	dest := filepath.Join(root, "live", "hark.db")
	if err := s.BackupTo(dest); err == nil || !strings.Contains(err.Error(), "备份目标不能是当前数据库") {
		t.Fatalf("expected active database rejection after chdir, got %v", err)
	}
	if detail, err := s.GetDetail("recording.opus"); err != nil || detail.Text != "original" {
		t.Fatalf("live transcript changed after chdir: %+v, %v", detail, err)
	}
}

func TestBackupToVacuumFailurePreservesOldBackup(t *testing.T) {
	s := newBackupTestStore(t)
	dest := filepath.Join(t.TempDir(), "backup.db")
	before := []byte("previous backup must survive")
	if err := os.WriteFile(dest, before, 0o600); err != nil {
		t.Fatal(err)
	}
	// VACUUM is forbidden inside a transaction. The store uses one connection,
	// so this fails after the temporary file has been created, without relying
	// on filesystem permissions or the user running the test.
	if _, err := s.db.Exec(`BEGIN`); err != nil {
		t.Fatal(err)
	}
	defer s.db.Exec(`ROLLBACK`)
	if err := s.BackupTo(dest); err == nil {
		t.Fatal("expected VACUUM to fail inside a transaction")
	}
	after, err := os.ReadFile(dest)
	if err != nil || !bytes.Equal(before, after) {
		t.Fatalf("failed backup changed the previous backup: %q, %v", after, err)
	}
	checkBackupDirectory(t, dest)
}

func TestBackupToRenameFailureCleansTemporaryFile(t *testing.T) {
	s := newBackupTestStore(t)
	dest := filepath.Join(t.TempDir(), "backup.db")
	if err := os.Mkdir(dest, 0o700); err != nil {
		t.Fatal(err)
	}
	marker := filepath.Join(dest, "keep")
	if err := os.WriteFile(marker, []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := s.BackupTo(dest); err == nil {
		t.Fatal("expected replacing a nonempty directory to fail")
	}
	if content, err := os.ReadFile(marker); err != nil || string(content) != "keep" {
		t.Fatalf("failed replacement changed the destination: %q, %v", content, err)
	}
	checkBackupDirectory(t, dest)
}

func TestBackupToUnwritableDirectoryPreservesOldBackup(t *testing.T) {
	s := newBackupTestStore(t)
	dir := t.TempDir()
	dest := filepath.Join(dir, "backup.db")
	before := []byte("previous backup must survive")
	if err := os.WriteFile(dest, before, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(dir, 0o500); err != nil {
		t.Fatal(err)
	}
	defer os.Chmod(dir, 0o700)
	// Some platforms and privileged users bypass directory permission bits.
	if probe, err := os.CreateTemp(dir, "probe-"); err == nil {
		probe.Close()
		os.Remove(probe.Name())
		t.Skip("directory permissions do not prevent writes for this user")
	}
	if err := s.BackupTo(dest); err == nil {
		t.Fatal("expected backup to an unwritable directory to fail")
	}
	after, err := os.ReadFile(dest)
	if err != nil || !bytes.Equal(before, after) {
		t.Fatalf("unwritable directory changed the previous backup: %q, %v", after, err)
	}
	checkBackupDirectory(t, dest)
}
