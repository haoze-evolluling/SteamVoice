package main

import (
	"context"
	"fmt"
	"log"
	"strings"
	"sync"

	"github.com/wailsapp/wails/v2/pkg/runtime"
	"steamvoice-desktop/internal/capture"
	"steamvoice-desktop/internal/discovery"
	"steamvoice-desktop/internal/stream"
)

type Device struct {
	Name, Host string
	Port       int
	ID         string
	Codec      string
	SampleRate int
	Channels   int
	Bitrate    int
	FrameMs    int
}
type Status struct {
	Connected bool
	Device    *Device
	Message   string
}

type App struct {
	ctx        context.Context
	mu         sync.Mutex
	discoverer *discovery.Browser
	sender     *stream.Sender
	capture    *capture.Loopback
	status     Status
}

func NewApp() *App                         { return &App{} }
func (a *App) Startup(ctx context.Context) { a.ctx = ctx }
func (a *App) Shutdown(context.Context)    { _ = a.Disconnect() }
func (a *App) DiscoverDevices() ([]Device, error) {
	if a.discoverer != nil {
		a.discoverer.Close()
	}
	b, err := discovery.NewBrowser(func(d discovery.Device) {
		runtime.EventsEmit(a.ctx, "device:found", Device{Name: d.Name, Host: d.Host, Port: d.Port, ID: d.ID, Codec: d.Codec, SampleRate: d.SampleRate, Channels: d.Channels, Bitrate: d.Bitrate, FrameMs: d.FrameMs})
	})
	if err != nil {
		return nil, err
	}
	a.discoverer = b
	return nil, b.Start(a.ctx)
}
func (a *App) Connect(device Device) error {
	a.mu.Lock()
	previousSender, previousCapture := a.sender, a.capture
	a.sender, a.capture = nil, nil
	a.mu.Unlock()
	if previousCapture != nil {
		previousCapture.Close()
	}
	if previousSender != nil {
		_ = previousSender.Close()
	}
	bitrate := device.Bitrate
	if bitrate == 0 {
		bitrate = 128000
	}
	if device.Codec != "" && !strings.EqualFold(device.Codec, "opus") && !strings.EqualFold(device.Codec, "pcm") {
		return fmt.Errorf("receiver does not support PCM/Opus (codec=%s)", device.Codec)
	}
	s, err := stream.NewSender(fmt.Sprintf("%s:%d", device.Host, device.Port), bitrate)
	if err != nil {
		return err
	}
	a.sender = s
	c, err := capture.Start(func(pcm []byte) {
		a.mu.Lock()
		sender := a.sender
		a.mu.Unlock()
		if sender != nil {
			if err := sender.SendPCM(pcm); err != nil {
				log.Printf("audio UDP send failed: %v", err)
			}
		}
	})
	if err != nil {
		_ = s.Close()
		return fmt.Errorf("启动 WASAPI 系统音频采集失败: %w", err)
	}
	a.mu.Lock()
	a.sender = s
	a.capture = c
	a.status = Status{Connected: true, Device: &device, Message: "正在传输系统音频"}
	a.mu.Unlock()
	runtime.EventsEmit(a.ctx, "stream:status", a.status)
	return nil
}
func (a *App) Disconnect() error {
	a.mu.Lock()
	capture, sender := a.capture, a.sender
	a.capture, a.sender = nil, nil
	a.status = Status{Message: "已断开连接"}
	status, ctx := a.status, a.ctx
	a.mu.Unlock()
	if capture != nil {
		capture.Close()
	}
	if sender != nil {
		_ = sender.Close()
	}
	if ctx != nil {
		runtime.EventsEmit(ctx, "stream:status", status)
	}
	return nil
}
func (a *App) GetStatus() Status { a.mu.Lock(); defer a.mu.Unlock(); return a.status }
