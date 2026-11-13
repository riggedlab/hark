package protocol

import (
	"os"
	"path/filepath"
	"testing"
	"testing/synctest"
	"time"
)

func mailboxTestConfig(t *testing.T) ServerConfig {
	t.Helper()
	return ServerConfig{DataDir: t.TempDir(), PhoneToken: "test-phone-token", WorkerToken: "test-worker-token"}
}

func writeMailboxFile(t *testing.T, path string, age time.Duration) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("ciphertext"), 0o600); err != nil {
		t.Fatal(err)
	}
	mtime := time.Now().Add(-age)
	if err := os.Chtimes(path, mtime, mtime); err != nil {
		t.Fatal(err)
	}
}

func checkMailboxFile(t *testing.T, path string, exists bool) {
	t.Helper()
	content, err := os.ReadFile(path)
	if !exists {
		if !os.IsNotExist(err) {
			t.Fatalf("expected %s to be removed, got %v", path, err)
		}
	} else if err != nil || string(content) != "ciphertext" {
		t.Fatalf("expected %s to remain intact: %q, %v", path, content, err)
	}
}

func TestMailboxPeriodicCleanupWithoutRequests(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		cfg := mailboxTestConfig(t)
		s, err := NewMailboxServer(cfg)
		if err != nil {
			t.Fatal(err)
		}
		defer s.Close()
		// Files are created after startup and expire before the first hourly tick.
		audio := filepath.Join(s.inboxDir, "expired.bin")
		text := filepath.Join(s.outDir, "0000000001_expired.bin")
		writeMailboxFile(t, audio, 7*24*time.Hour-30*time.Minute)
		writeMailboxFile(t, text, 30*24*time.Hour-30*time.Minute)
		freshAudio := filepath.Join(s.inboxDir, "fresh.bin")
		freshText := filepath.Join(s.outDir, "0000000002_fresh.bin")
		writeMailboxFile(t, freshAudio, 6*24*time.Hour)
		writeMailboxFile(t, freshText, 29*24*time.Hour)
		var oldTemps, freshTemps []string
		for _, dir := range []string{cfg.DataDir, s.inboxDir, s.outDir, s.slotDir} {
			oldTemp := filepath.Join(dir, ".orphan.tmp")
			freshTemp := filepath.Join(dir, ".recent.tmp")
			writeMailboxFile(t, oldTemp, 24*time.Hour-30*time.Minute)
			writeMailboxFile(t, freshTemp, 0)
			oldTemps = append(oldTemps, oldTemp)
			freshTemps = append(freshTemps, freshTemp)
		}
		slot := filepath.Join(s.slotDir, SlotSpeakersPhone+".bin")
		writeMailboxFile(t, slot, 365*24*time.Hour)
		synctest.Wait()
		checkMailboxFile(t, audio, true)
		checkMailboxFile(t, text, true)

		time.Sleep(time.Hour)
		synctest.Wait()
		checkMailboxFile(t, audio, false)
		checkMailboxFile(t, text, false)
		for _, path := range oldTemps {
			checkMailboxFile(t, path, false)
		}
		for _, path := range append(freshTemps, freshAudio, freshText, slot) {
			checkMailboxFile(t, path, true)
		}
		// Verify that cleanup keeps running beyond the first scheduled tick.
		laterAudio := filepath.Join(s.inboxDir, "later.bin")
		writeMailboxFile(t, laterAudio, 8*24*time.Hour)
		time.Sleep(time.Hour)
		synctest.Wait()
		checkMailboxFile(t, laterAudio, false)
	})
}

func TestMailboxStartupCleansOrphanTemps(t *testing.T) {
	cfg := mailboxTestConfig(t)
	var oldTemps, freshTemps []string
	for _, name := range []string{"", "inbox", "outbox", "slots"} {
		dir := filepath.Join(cfg.DataDir, name)
		oldTemp := filepath.Join(dir, ".orphan.tmp")
		freshTemp := filepath.Join(dir, ".recent.tmp")
		writeMailboxFile(t, oldTemp, 25*time.Hour)
		writeMailboxFile(t, freshTemp, 23*time.Hour)
		oldTemps = append(oldTemps, oldTemp)
		freshTemps = append(freshTemps, freshTemp)
	}
	slot := filepath.Join(cfg.DataDir, "slots", SlotSpeakersMac+".bin")
	writeMailboxFile(t, slot, 365*24*time.Hour)
	s, err := NewMailboxServer(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	for _, path := range oldTemps {
		checkMailboxFile(t, path, false)
	}
	for _, path := range append(freshTemps, slot) {
		checkMailboxFile(t, path, true)
	}
}

func TestMailboxCloseStopsPeriodicCleanup(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, err := NewMailboxServer(mailboxTestConfig(t))
		if err != nil {
			t.Fatal(err)
		}
		defer s.Close()
		audio := filepath.Join(s.inboxDir, "expires-after-close.bin")
		writeMailboxFile(t, audio, 7*24*time.Hour-30*time.Minute)
		s.Close()
		s.Close()
		time.Sleep(2 * time.Hour)
		synctest.Wait()
		checkMailboxFile(t, audio, true)
	})
}
