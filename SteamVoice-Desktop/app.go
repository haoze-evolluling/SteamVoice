package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"net"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/wailsapp/wails/v2/pkg/runtime"
	"steamvoice-desktop/internal/capture"
	"steamvoice-desktop/internal/codec"
	"steamvoice-desktop/internal/config"
	"steamvoice-desktop/internal/discovery"
	"steamvoice-desktop/internal/gateway"
	"steamvoice-desktop/internal/protocol"
	"steamvoice-desktop/internal/stream"
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

// DeviceStatus is the per-receiver streaming state pushed on stream:status.
type DeviceStatus struct {
	DeviceID  string
	Name      string
	Connected bool
	Message   string
	Bitrate   int
	FrameMs   int
}

// Status aggregates every active receiver for GetStatus.
type Status struct {
	ConnectedCount int
	Message        string
	Devices        []DeviceStatus
}

// ConnRequestInfo describes an inbound connection request awaiting the
// user's decision, pushed on conn:request.
type ConnRequestInfo struct {
	RequestID string
	DeviceID  string
	Name      string
	Host      string
}

// Identity exposes this desktop's stable device identity to the frontend.
type Identity struct {
	DeviceID string
	Name     string
}

// feedbackStaleAfter is how long without receiver feedback marks a session
// unresponsive; receivers report roughly every 200 ms while playing.
const feedbackStaleAfter = 3 * time.Second

// requestExpiry is how long an unanswered authorization prompt stays alive
// before the desktop gives up and cancels it.
const requestExpiry = 30 * time.Second

type deviceSession struct {
	device  Device
	sender  *stream.Sender
	encoder *codec.OpusEncoder
	stale   bool
	status  DeviceStatus
}

type pendingRequest struct {
	info  ConnRequestInfo
	peer  gateway.Peer
	timer *time.Timer
}

type App struct {
	ctx             context.Context
	mu              sync.Mutex
	discoverer      *discovery.Browser
	advertiser      *discovery.Advertiser
	sessions        map[string]*deviceSession
	capture         *capture.Loopback
	frameMs         int
	staleAfter      time.Duration
	requestTimeout  time.Duration
	store           *config.File
	listener        *gateway.Listener
	pending         map[string]*pendingRequest
	pendingByDevice map[string]string
	localBitrate    int
	localFrameMs    int
}

func NewApp() *App {
	var store *config.File
	if path, err := config.DefaultPath(); err == nil {
		if loaded, err := config.Load(path); err == nil {
			store = loaded
		} else {
			log.Printf("config load failed, using in-memory identity: %v", err)
		}
	}
	if store == nil {
		store = config.Memory()
	}
	return NewAppWithStore(store)
}

// NewAppWithStore wires an explicit identity/trust store; tests use it to
// avoid touching the real config file.
func NewAppWithStore(store *config.File) *App {
	return &App{sessions: map[string]*deviceSession{}, staleAfter: feedbackStaleAfter, requestTimeout: requestExpiry, pending: map[string]*pendingRequest{}, pendingByDevice: map[string]string{}, localBitrate: 128000, localFrameMs: 10, store: store}
}
func (a *App) Startup(ctx context.Context) {
	a.ctx = ctx
	if a.store.Name() == "" {
		if host, err := os.Hostname(); err == nil && strings.TrimSpace(host) != "" {
			_ = a.store.SetName(strings.TrimSpace(host))
		}
	}
	listener, err := gateway.Start(protocol.DesktopControlPort, a.store.DeviceID, a.onConnRequest, a.onConnBye)
	if err != nil {
		// The app still works as a sender; only inbound connections break.
		log.Printf("control listener unavailable (another instance running?): %v", err)
	} else {
		a.listener = listener
	}
	if advertiser, err := discovery.Advertise("SteamVoice-"+a.pcName(), a.store.DeviceID, protocol.DesktopControlPort); err != nil {
		log.Printf("mDNS advertise failed: %v", err)
	} else {
		a.advertiser = advertiser
	}
	go a.monitorLoop(ctx)
}

func (a *App) pcName() string {
	if name := a.store.Name(); name != "" {
		return name
	}
	return "PC"
}

func (a *App) Shutdown(context.Context) {
	a.mu.Lock()
	sessions := a.sessions
	c := a.capture
	a.sessions = map[string]*deviceSession{}
	a.capture = nil
	a.frameMs = 0
	listener := a.listener
	advertiser := a.advertiser
	a.listener = nil
	a.advertiser = nil
	for _, entry := range a.pending {
		entry.timer.Stop()
	}
	a.pending = map[string]*pendingRequest{}
	a.pendingByDevice = map[string]string{}
	a.mu.Unlock()
	if advertiser != nil {
		advertiser.Close()
	}
	if listener != nil {
		listener.Close()
	}
	if c != nil {
		c.Close()
	}
	for _, s := range sessions {
		_ = s.sender.Close()
	}
}
func (a *App) DiscoverDevices() ([]Device, error) {
	if a.discoverer != nil {
		a.discoverer.Close()
	}
	b, err := discovery.NewBrowser(func(d discovery.Device) {
		runtime.EventsEmit(a.ctx, "device:found", Device{Name: d.Name, Host: d.Host, Port: d.Port, ID: d.ID, Codec: d.Codec, SampleRate: d.SampleRate, Channels: d.Channels, Bitrate: d.Bitrate, FrameMs: d.FrameMs, SupportedFrameMs: d.SupportedFrameMs, UpdatedAtMs: d.UpdatedAtMs, SettingsDeviceID: d.SettingsDeviceID})
	})
	if err != nil {
		return nil, err
	}
	a.discoverer = b
	return nil, b.Start(a.ctx)
}

// Connect starts streaming to device. Multiple receivers may be connected at
// the same time; every session encodes independently so per-receiver bitrate
// adaptation keeps working. Reconnecting an already-connected device replaces
// its session.
func (a *App) Connect(device Device) error {
	bitrate := device.Bitrate
	if bitrate == 0 {
		bitrate = 128000
	}
	if bitrate != 64000 && bitrate != 96000 && bitrate != 128000 && bitrate != 192000 {
		return fmt.Errorf("unsupported bitrate: %d", bitrate)
	}
	frameMs := device.FrameMs
	if frameMs == 0 {
		frameMs = 10
	}
	if frameMs != 10 && frameMs != 20 {
		return fmt.Errorf("unsupported frame duration: %d ms", frameMs)
	}
	if len(device.SupportedFrameMs) > 0 {
		supported := false
		for _, value := range device.SupportedFrameMs {
			if value == frameMs {
				supported = true
				break
			}
		}
		if !supported {
			return fmt.Errorf("receiver does not support %d ms audio frames", frameMs)
		}
	}
	if !strings.EqualFold(device.Codec, "opus") {
		return fmt.Errorf("receiver does not support Opus (codec=%s)", device.Codec)
	}
	a.mu.Lock()
	activeFrameMs := a.frameMs
	a.mu.Unlock()
	if activeFrameMs != 0 && activeFrameMs != frameMs {
		return fmt.Errorf("已连接的接收端正在使用 %d ms 音频帧，请先断开全部设备再更改帧时长", activeFrameMs)
	}
	sender, err := stream.NewSender(fmt.Sprintf("%s:%d", device.Host, device.Port), bitrate, frameMs)
	if err != nil {
		return err
	}
	encoder, err := codec.NewOpusEncoder(bitrate, frameMs)
	if err != nil {
		_ = sender.Close()
		return fmt.Errorf("初始化 Opus 编码器失败: %w", err)
	}
	session := &deviceSession{device: device, sender: sender, encoder: encoder, status: DeviceStatus{DeviceID: device.ID, Name: device.Name, Connected: true, Message: "正在传输系统音频", Bitrate: bitrate, FrameMs: frameMs}}
	sender.SetBitrateCallback(func(next int) {
		if err := encoder.SetBitrate(next); err != nil {
			log.Printf("opus bitrate update failed: %v", err)
		}
		a.mu.Lock()
		current, stillActive := a.sessions[device.ID]
		status := session.status
		ctx := a.ctx
		if stillActive && current == session {
			session.status.Bitrate = next
			status = session.status
		} else {
			stillActive = false
		}
		a.mu.Unlock()
		if stillActive && ctx != nil {
			runtime.EventsEmit(ctx, "stream:status", status)
		}
	})
	if device.UpdatedAtMs > 0 || device.SettingsDeviceID != "" {
		if err := sender.SendSettings(protocol.Settings{BitrateKbps: uint32(bitrate), FrameMs: uint16(frameMs), UpdatedAtMs: device.UpdatedAtMs, DeviceID: device.SettingsDeviceID}); err != nil {
			log.Printf("settings sync failed: %v", err)
		}
	}
	a.mu.Lock()
	if a.frameMs != 0 && a.frameMs != frameMs {
		a.mu.Unlock()
		_ = sender.Close()
		return fmt.Errorf("已连接的接收端正在使用 %d ms 音频帧，请先断开全部设备再更改帧时长", a.frameMs)
	}
	if a.capture == nil {
		c, err := capture.Start(frameMs, a.onPCM)
		if err != nil {
			a.mu.Unlock()
			_ = sender.Close()
			return fmt.Errorf("启动 WASAPI 系统音频采集失败: %w", err)
		}
		a.capture = c
		a.frameMs = frameMs
	}
	previous := a.sessions[device.ID]
	a.sessions[device.ID] = session
	a.mu.Unlock()
	if previous != nil {
		_ = previous.sender.Close()
	}
	a.emitStatus(session.status)
	return nil
}

// Disconnect stops streaming to the receiver identified by deviceID.
func (a *App) Disconnect(deviceID string) error {
	a.mu.Lock()
	session, ok := a.sessions[deviceID]
	if ok {
		delete(a.sessions, deviceID)
	}
	var c *capture.Loopback
	if ok && len(a.sessions) == 0 {
		c = a.capture
		a.capture = nil
		a.frameMs = 0
	}
	a.mu.Unlock()
	if c != nil {
		c.Close()
	}
	if !ok {
		return nil
	}
	_ = session.sender.Close()
	a.emitStatus(DeviceStatus{DeviceID: deviceID, Name: session.device.Name, Message: "已断开连接"})
	return nil
}

func (a *App) GetStatus() Status {
	a.mu.Lock()
	defer a.mu.Unlock()
	status := Status{Devices: make([]DeviceStatus, 0, len(a.sessions))}
	for _, s := range a.sessions {
		status.Devices = append(status.Devices, s.status)
	}
	sort.Slice(status.Devices, func(i, j int) bool { return status.Devices[i].Name < status.Devices[j].Name })
	status.ConnectedCount = len(status.Devices)
	if status.ConnectedCount == 0 {
		status.Message = "未连接接收端"
	} else {
		status.Message = fmt.Sprintf("已连接 %d 台接收端，正在发送电脑音频", status.ConnectedCount)
	}
	return status
}

// GetIdentity exposes the stable desktop identity used for authorization.
func (a *App) GetIdentity() Identity {
	return Identity{DeviceID: a.store.DeviceID, Name: a.pcName()}
}

// ListAuthorizedDevices returns the remembered receivers that may connect
// without a confirmation prompt.
func (a *App) ListAuthorizedDevices() []config.AuthorizedDevice {
	return a.store.List()
}

// RemoveAuthorizedDevice drops a remembered receiver so its next connection
// asks for confirmation again.
func (a *App) RemoveAuthorizedDevice(deviceID string) error {
	return a.store.Remove(deviceID)
}

// SaveLocalSettings mirrors the frontend audio settings into the backend so
// inbound-initiated sessions encode with the same parameters the user picked.
func (a *App) SaveLocalSettings(bitrate int, frameMs int) {
	if bitrate != 64000 && bitrate != 96000 && bitrate != 128000 && bitrate != 192000 {
		return
	}
	if frameMs != 10 && frameMs != 20 {
		return
	}
	a.mu.Lock()
	a.localBitrate = bitrate
	a.localFrameMs = frameMs
	a.mu.Unlock()
}

// RespondConnection answers a pending inbound request. remember stores the
// device so future requests are auto-accepted.
func (a *App) RespondConnection(requestID string, allow bool, remember bool) error {
	a.mu.Lock()
	entry, ok := a.pending[requestID]
	if !ok {
		a.mu.Unlock()
		return fmt.Errorf("连接请求已过期或已处理")
	}
	delete(a.pending, requestID)
	delete(a.pendingByDevice, entry.peer.DeviceID)
	entry.timer.Stop()
	listener := a.listener
	a.mu.Unlock()
	if listener == nil {
		return fmt.Errorf("控制通道不可用")
	}
	if remember && allow {
		if err := a.store.Authorize(entry.peer.DeviceID, entry.peer.Name); err != nil {
			log.Printf("persisting authorization failed: %v", err)
		}
	}
	if err := listener.Respond(entry.peer, allow); err != nil {
		return fmt.Errorf("发送授权结果失败: %w", err)
	}
	if allow {
		go func() {
			if err := a.Connect(a.deviceFromPeer(entry.peer)); err != nil {
				log.Printf("inbound connect to %s failed: %v", entry.peer.Name, err)
				a.emitStatus(DeviceStatus{DeviceID: entry.peer.DeviceID, Name: entry.peer.Name, Message: "连接失败: " + err.Error()})
			}
		}()
	}
	return nil
}

// onConnRequest handles a receiver-initiated connection: trusted or already
// streaming devices are accepted instantly, everything else waits for the
// authorization modal.
func (a *App) onConnRequest(peer gateway.Peer) {
	a.mu.Lock()
	session, streaming := a.sessions[peer.DeviceID]
	trusted := a.store.IsAuthorized(peer.DeviceID)
	if streaming || trusted {
		// Request retransmissions must not churn the live session; only
		// rebuild it when the peer shows up from a different address.
		sameAddress := streaming && session.device.Host == peer.Addr.IP.String()
		a.mu.Unlock()
		if err := a.listener.Respond(peer, true); err != nil {
			log.Printf("responding to %s failed: %v", peer.DeviceID, err)
			return
		}
		if !sameAddress {
			go func() {
				if err := a.Connect(a.deviceFromPeer(peer)); err != nil {
					log.Printf("inbound connect to %s failed: %v", peer.DeviceID, err)
				}
			}()
		}
		return
	}
	if oldID, dup := a.pendingByDevice[peer.DeviceID]; dup {
		if old, ok := a.pending[oldID]; ok {
			old.timer.Stop()
			delete(a.pending, oldID)
		}
	}
	requestID := newRequestID()
	entry := &pendingRequest{info: ConnRequestInfo{RequestID: requestID, DeviceID: peer.DeviceID, Name: peer.Name, Host: peer.Addr.IP.String()}, peer: peer}
	entry.timer = time.AfterFunc(a.requestTimeout, func() { a.expireRequest(requestID, entry) })
	a.pending[requestID] = entry
	a.pendingByDevice[peer.DeviceID] = requestID
	ctx := a.ctx
	a.mu.Unlock()
	if ctx != nil {
		runtime.EventsEmit(ctx, "conn:request", entry.info)
	}
}

// onConnBye drops the session when a receiver says goodbye.
func (a *App) onConnBye(deviceID string, _ *net.UDPAddr) {
	if err := a.Disconnect(deviceID); err != nil {
		log.Printf("disconnect after bye from %s failed: %v", deviceID, err)
	}
}

func (a *App) expireRequest(requestID string, entry *pendingRequest) {
	a.mu.Lock()
	current, ok := a.pending[requestID]
	if !ok || current != entry {
		a.mu.Unlock()
		return
	}
	delete(a.pending, requestID)
	delete(a.pendingByDevice, entry.peer.DeviceID)
	ctx := a.ctx
	a.mu.Unlock()
	if ctx != nil {
		runtime.EventsEmit(ctx, "conn:cancelled", entry.info.RequestID)
	}
}

func (a *App) deviceFromPeer(peer gateway.Peer) Device {
	name := strings.TrimSpace(peer.Name)
	if name == "" {
		name = "Android 设备"
	}
	a.mu.Lock()
	bitrate, frameMs := a.localBitrate, a.localFrameMs
	a.mu.Unlock()
	return Device{Name: name, Host: peer.Addr.IP.String(), Port: protocol.ReceiverAudioPort, ID: peer.DeviceID, Codec: "opus", SampleRate: protocol.SampleRate, Channels: protocol.Channels, Bitrate: bitrate, FrameMs: frameMs, SupportedFrameMs: []int{10, 20}}
}

func newRequestID() string {
	var raw [8]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(raw[:])
}

// onPCM runs on the WASAPI capture thread: encode once per session and send.
func (a *App) onPCM(pcm []byte) {
	a.mu.Lock()
	sessions := make([]*deviceSession, 0, len(a.sessions))
	for _, s := range a.sessions {
		sessions = append(sessions, s)
	}
	a.mu.Unlock()
	for _, s := range sessions {
		encoded, err := s.encoder.EncodePCM(pcm)
		if err == nil {
			err = s.sender.SendOpus(encoded)
		}
		if err != nil {
			log.Printf("audio UDP send to %s failed: %v", s.device.Name, err)
		}
	}
}

// monitorLoop flags sessions whose receiver stopped reporting feedback.
func (a *App) monitorLoop(ctx context.Context) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		for _, status := range a.checkStaleSessions() {
			a.emitStatus(status)
		}
	}
}

// checkStaleSessions toggles the unresponsive flag of every session whose
// receiver feedback went silent or resumed, returning the changed statuses.
func (a *App) checkStaleSessions() []DeviceStatus {
	a.mu.Lock()
	defer a.mu.Unlock()
	var updated []DeviceStatus
	for _, s := range a.sessions {
		stale := s.sender.FeedbackIdle() > a.staleAfter
		if stale == s.stale {
			continue
		}
		s.stale = stale
		if stale {
			s.status.Message = "接收端无响应"
		} else {
			s.status.Message = "正在传输系统音频"
		}
		updated = append(updated, s.status)
	}
	return updated
}

func (a *App) emitStatus(status DeviceStatus) {
	a.mu.Lock()
	ctx := a.ctx
	a.mu.Unlock()
	if ctx != nil {
		runtime.EventsEmit(ctx, "stream:status", status)
	}
}
