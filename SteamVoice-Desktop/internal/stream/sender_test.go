package stream

import (
	"net"
	"testing"
	"time"

	"steamvoice-desktop/internal/protocol"
)

func TestSenderAddsConfiguredBitrate(t *testing.T) {
	listener, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	sender, err := NewSender(listener.LocalAddr().String(), 96000)
	if err != nil {
		t.Fatal(err)
	}
	defer sender.Close()
	if err = sender.SendOpus([]byte{0xf8, 0xff}); err != nil {
		t.Fatal(err)
	}
	if err = listener.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 256)
	n, _, err := listener.ReadFromUDP(buf)
	if err != nil {
		t.Fatal(err)
	}
	h, payload, err := protocol.Decode(buf[:n])
	if err != nil {
		t.Fatal(err)
	}
	if h.Bitrate != 96000 || string(payload) != string([]byte{0xf8, 0xff}) {
		t.Fatalf("header=%+v payload=%v", h, payload)
	}
}

func TestSenderSendsPCMCodec(t *testing.T) {
	listener, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	sender, err := NewSender(listener.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer sender.Close()
	want := []byte{0, 1, 2, 3}
	if err = sender.SendPCM(want); err != nil {
		t.Fatal(err)
	}
	_ = listener.SetReadDeadline(time.Now().Add(time.Second))
	buf := make([]byte, 256)
	n, _, err := listener.ReadFromUDP(buf)
	if err != nil {
		t.Fatal(err)
	}
	h, payload, err := protocol.Decode(buf[:n])
	if err != nil {
		t.Fatal(err)
	}
	if h.Codec != protocol.CodecPCM || string(payload) != string(want) {
		t.Fatalf("header=%+v payload=%v", h, payload)
	}
}
