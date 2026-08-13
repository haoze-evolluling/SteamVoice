package protocol

import (
	"encoding/binary"
	"testing"
)

func TestRoundTrip(t *testing.T) {
	source := []byte{1, 2, 3, 4}
	b, e := Encode(Header{Session: 7, Sequence: 9, Bitrate: 128000}, source)
	if e != nil {
		t.Fatal(e)
	}
	h, got, e := Decode(b)
	if e != nil || h.Session != 7 || h.Sequence != 9 || h.Bitrate != 128000 || string(got) != string(source) {
		t.Fatalf("decoded %#v %v %v", h, got, e)
	}
}
func TestEncodedAudioFormatUsesBigEndianFields(t *testing.T) {
	b, err := Encode(Header{Bitrate: 96000}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got := binary.BigEndian.Uint32(b[6:10]); got != SampleRate {
		t.Fatalf("sample rate = %d", got)
	}
	if got := binary.BigEndian.Uint32(b[12:16]); got != 96000 {
		t.Fatalf("bitrate = %d", got)
	}
}
