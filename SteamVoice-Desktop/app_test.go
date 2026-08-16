package main

import (
	"strings"
	"testing"
	"time"

	"steamvoice-desktop/internal/stream"
)

func newTestSession(t *testing.T, id string, name string) *deviceSession {
	t.Helper()
	sender, err := stream.NewSender("127.0.0.1:1", 128000, 10)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sender.Close() })
	return &deviceSession{device: Device{ID: id, Name: name}, sender: sender, status: DeviceStatus{DeviceID: id, Name: name, Connected: true, Message: "正在传输系统音频", Bitrate: 128000, FrameMs: 10}}
}

func TestNewAppHasNoConnectedDevices(t *testing.T) {
	status := NewApp().GetStatus()
	if status.ConnectedCount != 0 {
		t.Fatalf("new app must have no connected devices, got %d", status.ConnectedCount)
	}
	if status.Message != "未连接接收端" {
		t.Fatalf("initial status message = %q, want %q", status.Message, "未连接接收端")
	}
	if len(status.Devices) != 0 {
		t.Fatalf("new app device list = %v, want empty", status.Devices)
	}
}

func TestGetStatusAggregatesSessions(t *testing.T) {
	a := NewApp()
	a.sessions["phone-b"] = newTestSession(t, "phone-b", "Pixel")
	a.sessions["phone-a"] = newTestSession(t, "phone-a", "Galaxy")
	status := a.GetStatus()
	if status.ConnectedCount != 2 {
		t.Fatalf("connected count = %d, want 2", status.ConnectedCount)
	}
	if len(status.Devices) != 2 || status.Devices[0].DeviceID != "phone-a" || status.Devices[1].DeviceID != "phone-b" {
		t.Fatalf("devices = %+v, want sorted phone-a then phone-b", status.Devices)
	}
	if !strings.Contains(status.Message, "2") {
		t.Fatalf("aggregate message = %q, want it to mention 2 devices", status.Message)
	}
}

func TestConnectRejectsInvalidParameters(t *testing.T) {
	a := NewApp()
	cases := []struct {
		name   string
		device Device
		want   string
	}{
		{"bitrate", Device{Host: "127.0.0.1", Port: 1, ID: "x", Codec: "opus", Bitrate: 123000, FrameMs: 10}, "unsupported bitrate"},
		{"frame", Device{Host: "127.0.0.1", Port: 1, ID: "x", Codec: "opus", Bitrate: 128000, FrameMs: 30}, "unsupported frame duration"},
		{"codec", Device{Host: "127.0.0.1", Port: 1, ID: "x", Codec: "pcm", Bitrate: 128000, FrameMs: 10}, "Opus"},
		{"unsupported by receiver", Device{Host: "127.0.0.1", Port: 1, ID: "x", Codec: "opus", Bitrate: 128000, FrameMs: 20, SupportedFrameMs: []int{10}}, "does not support"},
	}
	for _, c := range cases {
		err := a.Connect(c.device)
		if err == nil || !strings.Contains(err.Error(), c.want) {
			t.Fatalf("%s: err = %v, want containing %q", c.name, err, c.want)
		}
	}
	if a.GetStatus().ConnectedCount != 0 {
		t.Fatal("rejected connects must not leave sessions behind")
	}
}

func TestConnectRejectsFrameChangeWhileStreaming(t *testing.T) {
	a := NewApp()
	a.frameMs = 10
	err := a.Connect(Device{Host: "127.0.0.1", Port: 1, ID: "x", Codec: "opus", Bitrate: 128000, FrameMs: 20, SupportedFrameMs: []int{10, 20}})
	if err == nil || !strings.Contains(err.Error(), "10 ms") {
		t.Fatalf("err = %v, want frame duration conflict mentioning 10 ms", err)
	}
}

func TestDisconnectRemovesOnlyTargetSession(t *testing.T) {
	a := NewApp()
	first := newTestSession(t, "phone-a", "Pixel")
	second := newTestSession(t, "phone-b", "Galaxy")
	a.sessions["phone-a"] = first
	a.sessions["phone-b"] = second
	if err := a.Disconnect("phone-a"); err != nil {
		t.Fatal(err)
	}
	status := a.GetStatus()
	if status.ConnectedCount != 1 || status.Devices[0].DeviceID != "phone-b" {
		t.Fatalf("after disconnect status = %+v, want only phone-b", status)
	}
	if err := a.Disconnect("unknown"); err != nil {
		t.Fatalf("disconnecting unknown device must be a no-op, got %v", err)
	}
	if a.GetStatus().ConnectedCount != 1 {
		t.Fatal("unknown disconnect must not touch other sessions")
	}
}

func TestCheckStaleSessionsFlagsSilentReceiver(t *testing.T) {
	a := NewApp()
	a.staleAfter = 10 * time.Millisecond
	session := newTestSession(t, "phone-a", "Pixel")
	a.sessions["phone-a"] = session
	time.Sleep(20 * time.Millisecond)
	updated := a.checkStaleSessions()
	if len(updated) != 1 || updated[0].Message != "接收端无响应" {
		t.Fatalf("updated = %+v, want one 无响应 status", updated)
	}
	if session.status.Message != "接收端无响应" {
		t.Fatalf("session message = %q, want 接收端无响应", session.status.Message)
	}
	if again := a.checkStaleSessions(); len(again) != 0 {
		t.Fatalf("stable session re-reported: %+v", again)
	}
}
