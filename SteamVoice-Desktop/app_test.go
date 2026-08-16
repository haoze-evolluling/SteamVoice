package main

import "testing"

func TestNewAppHasDisconnectedStatus(t *testing.T) {
	status := NewApp().GetStatus()
	if status.Connected {
		t.Fatal("new app must not be connected")
	}
	if status.Message != "未连接接收端" {
		t.Fatalf("initial status message = %q, want %q", status.Message, "未连接接收端")
	}
}
