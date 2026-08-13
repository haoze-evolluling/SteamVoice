package discovery

import (
	"context"
	"github.com/grandcat/zeroconf"
	"strconv"
	"strings"
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
			number := func(k string, fallback int) int {
				if n, err := strconv.Atoi(values[k]); err == nil && n > 0 {
					return n
				}
				return fallback
			}
			b.onDevice(Device{Name: name, Host: host, Port: e.Port, ID: e.Instance, Codec: values["codec"], SampleRate: number("sample_rate", 48000), Channels: number("channels", 2), Bitrate: number("bitrate", 128000), FrameMs: number("frame_ms", 20)})
		}
	}()
	return b.resolver.Browse(ctx, "_steamvoice._udp", "local.", entries)
}
func (b *Browser) Close() {
	if b.cancel != nil {
		b.cancel()
	}
}
