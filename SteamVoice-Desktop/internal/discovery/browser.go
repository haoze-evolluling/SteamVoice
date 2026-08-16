package discovery

import (
	"context"
	"github.com/grandcat/zeroconf"
	"strconv"
	"strings"
)

type Device struct {
	Name, Host       string
	Port             int
	ID               string
	Codec            string
	SampleRate       int
	Channels         int
	Bitrate          int
	FrameMs          int
	SupportedFrameMs []int
	UpdatedAtMs      int64
	SettingsDeviceID string
}

// SupportsOpus reports whether the receiver advertised the v2 codec.
func (d Device) SupportsOpus() bool { return strings.EqualFold(d.Codec, "opus") }

type Browser struct {
	resolver *zeroconf.Resolver
	cancel   context.CancelFunc
	onDevice func(Device)
}

func NewBrowser(onDevice func(Device)) (*Browser, error) {
	r, e := zeroconf.NewResolver()
	return &Browser{resolver: r, onDevice: onDevice}, e
}
func (b *Browser) Start(parent context.Context) error {
	ctx, cancel := context.WithCancel(parent)
	b.cancel = cancel
	entries := make(chan *zeroconf.ServiceEntry)
	go func() {
		for e := range entries {
			host := e.HostName
			if len(e.AddrIPv4) > 0 {
				host = e.AddrIPv4[0].String()
			}
			name := strings.TrimSpace(e.Instance)
			if name == "" {
				name = strings.TrimSuffix(strings.TrimSpace(e.HostName), ".")
			}
			values := map[string]string{}
			for _, raw := range e.Text {
				p := strings.SplitN(raw, "=", 2)
				if len(p) == 2 {
					values[strings.ToLower(p[0])] = p[1]
				}
			}
			if !strings.EqualFold(values["codec"], "opus") {
				continue
			}
			number := func(k string, fallback int) int {
				if n, err := strconv.Atoi(values[k]); err == nil && n > 0 {
					return n
				}
				return fallback
			}
			frameOptions := []int{}
			for _, raw := range strings.Split(values["frame_ms"], ",") {
				if n, err := strconv.Atoi(strings.TrimSpace(raw)); err == nil && (n == 10 || n == 20) {
					frameOptions = append(frameOptions, n)
				}
			}
			if len(frameOptions) == 0 {
				frameOptions = []int{10}
			}
			currentFrame := number("current_frame_ms", frameOptions[0])
			if currentFrame != 10 && currentFrame != 20 {
				currentFrame = frameOptions[0]
			}
			updatedAt, _ := strconv.ParseInt(values["settings_updated_at"], 10, 64)
			b.onDevice(Device{Name: name, Host: host, Port: e.Port, ID: e.Instance, Codec: values["codec"], SampleRate: number("sample_rate", 48000), Channels: number("channels", 2), Bitrate: number("bitrate", 128000), FrameMs: currentFrame, SupportedFrameMs: frameOptions, UpdatedAtMs: updatedAt, SettingsDeviceID: values["settings_device_id"]})
		}
	}()
	return b.resolver.Browse(ctx, "_steamvoice._udp", "local.", entries)
}
func (b *Browser) Close() {
	if b.cancel != nil {
		b.cancel()
	}
}
