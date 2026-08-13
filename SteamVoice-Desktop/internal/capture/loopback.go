// Package capture owns the Windows WASAPI loopback device. miniaudio selects
// WASAPI on Windows and exposes the default render endpoint as a capture source.
package capture

import (
	"sync"

	"github.com/gen2brain/malgo"
)

type Loopback struct { ctx *malgo.AllocatedContext; device *malgo.Device; once sync.Once }

func Start(onPCM func([]byte)) (*Loopback, error) {
	ctx, err := malgo.InitContext([]malgo.Backend{malgo.BackendWasapi}, malgo.ContextConfig{}, nil)
	if err != nil { return nil, err }
	config := malgo.DefaultDeviceConfig(malgo.Loopback)
	config.Capture.Format = malgo.FormatS16
	config.Capture.Channels = 2
	config.SampleRate = 48000
	config.PerformanceProfile = malgo.LowLatency
	device, err := malgo.InitDevice(ctx.Context, config, malgo.DeviceCallbacks{Data: func(_, input []byte, _ uint32) { if len(input)>0 { frame:=append([]byte(nil), input...); onPCM(frame) } }})
	if err != nil { _ = ctx.Uninit(); ctx.Free(); return nil, err }
	if err = device.Start(); err != nil { device.Uninit(); _=ctx.Uninit(); ctx.Free(); return nil, err }
	return &Loopback{ctx:ctx,device:device}, nil
}
func (l *Loopback) Close() { l.once.Do(func(){ l.device.Uninit(); _=l.ctx.Uninit(); l.ctx.Free() }) }
