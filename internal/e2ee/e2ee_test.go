package e2ee

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"strings"
	"testing"
)

func TestFixedVectorMatchesAndroidAndPython(t *testing.T) {
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(i)
	}
	const name = "20261112-123000.opus"
	aad := []byte("text:3a2e641c9b0f")
	plain := []byte("你好，谛听")
	wantHex := "010102030405060708090a0b0ce157fa3049291f3ac04ad3dcf583461f34b5c5b47754ab690856ab26f8491f"
	wantPacket, _ := hex.DecodeString(wantHex)

	iid, err := ItemID(key, name)
	if err != nil {
		t.Fatal(err)
	}
	if iid != "dccc04d892bab8cd0700141f1784459a" {
		t.Fatalf("ItemID mismatch: got %s", iid)
	}

	gotPlain, err := Decrypt(key, wantPacket, aad)
	if err != nil || !bytes.Equal(gotPlain, plain) {
		t.Fatalf("Decrypt fixed vector failed: err=%v plain=%q", err, gotPlain)
	}

	nonce := make([]byte, 12)
	for i := range nonce {
		nonce[i] = byte(i + 1)
	}
	gotPacket, err := Encrypt(key, plain, aad, nonce)
	if err != nil || !bytes.Equal(gotPacket, wantPacket) {
		t.Fatalf("Encrypt fixed vector mismatch:\ngot  %x\nwant %x", gotPacket, wantPacket)
	}
}

func TestKeyFormatsAndTamperRejection(t *testing.T) {
	special := bytes.Repeat([]byte{0xfb}, 32)
	for _, enc := range []string{
		"  " + hex.EncodeToString(special) + "\n",
		base64.StdEncoding.EncodeToString(special),
		base64.RawURLEncoding.EncodeToString(special),
	} {
		got, err := DecodeKey(enc)
		if err != nil || !bytes.Equal(got, special) {
			t.Fatalf("DecodeKey(%q) failed: %v", enc, err)
		}
	}

	for _, bad := range []string{"", "abc", "!!!!", strings.Repeat("00", 16), "AA=="} {
		if _, err := DecodeKey(bad); err == nil {
			t.Fatalf("expected error for %q", bad)
		}
	}

	pkt, err := PackAudio(special, "20261112-123000.opus", []byte("OggS-audio"))
	if err != nil {
		t.Fatal(err)
	}
	iid, _ := ItemID(special, "20261112-123000.opus")
	gotName, gotAudio, err := UnpackAudio(special, iid, pkt)
	if err != nil || gotName != "20261112-123000.opus" || string(gotAudio) != "OggS-audio" {
		t.Fatalf("UnpackAudio failed: %v", err)
	}

	// Wrong itemID must fail AEAD check
	if _, _, err := UnpackAudio(special, strings.Repeat("0", 32), pkt); err == nil {
		t.Fatal("expected AEAD failure on wrong itemID")
	}
	// Bit flip must fail AEAD check
	pkt[len(pkt)-1] ^= 1
	if _, _, err := UnpackAudio(special, iid, pkt); err == nil {
		t.Fatal("expected AEAD failure on tampered packet")
	}
}
