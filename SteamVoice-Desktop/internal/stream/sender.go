package stream

import (
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"net"
	"steamvoice-desktop/internal/protocol"
	"sync"
	"time"
)

const minBitrate = 48000
const bitrateStep = 16000

type Sender struct {
	conn         *net.UDPConn
	session, seq uint32
	bitrate      uint32
	frameMs      uint16
	mu           sync.Mutex
	feedbackDone chan struct{}
	closeOnce    sync.Once
	feedbackWG   sync.WaitGroup
	onBitrate    func(int)
	lastFeedback time.Time
}

func NewSender(address string, args ...int) (*Sender, error) {
	a, e := net.ResolveUDPAddr("udp", address)
	if e != nil {
		return nil, e
	}
	c, e := net.DialUDP("udp", nil, a)
	if e != nil {
		return nil, e
	}
	var raw [4]byte
	_, _ = rand.Read(raw[:])
	br := 128000
	if len(args) > 0 && args[0] > 0 {
		br = args[0]
	}
	frameMs := 10
	if len(args) > 1 && args[1] > 0 {
		frameMs = args[1]
	}
	if frameMs != 10 && frameMs != 20 {
		_ = c.Close()
		return nil, fmt.Errorf("unsupported frame duration: %d ms", frameMs)
	}
	s := &Sender{conn: c, session: binary.BigEndian.Uint32(raw[:]), bitrate: uint32(br), frameMs: uint16(frameMs), feedbackDone: make(chan struct{}), lastFeedback: time.Now()}
	s.feedbackWG.Add(1)
	go s.feedbackLoop()
	return s, nil
}

func (s *Sender) SetBitrateCallback(fn func(int)) { s.mu.Lock(); s.onBitrate = fn; s.mu.Unlock() }

// FeedbackIdle reports how long ago the last valid receiver feedback arrived,
// counted from sender creation when no feedback has been received yet.
func (s *Sender) FeedbackIdle() time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	return time.Since(s.lastFeedback)
}

// LocalAddr reports the UDP source address feedback should be sent to.
func (s *Sender) LocalAddr() net.Addr { return s.conn.LocalAddr() }

func (s *Sender) SendSettings(settings protocol.Settings) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.conn.Write(protocol.EncodeSettings(settings))
	return err
}
func (s *Sender) feedbackLoop() {
	defer s.feedbackWG.Done()
	buf := make([]byte, 256)
	lowSince := time.Time{}
	for {
		s.conn.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
		n, _, err := s.conn.ReadFromUDP(buf)
		if err != nil {
			select {
			case <-s.feedbackDone:
				return
			default:
			}
			continue
		}
		f, err := protocol.DecodeFeedback(buf[:n])
		if err != nil || f.Session != s.session {
			continue
		}
		s.mu.Lock()
		s.lastFeedback = time.Now()
		cur := int(s.bitrate)
		loss := float64(f.Lost) / float64(max32(f.Received+f.Lost, 1))
		next := cur
		if loss > 0.05 || f.Queue > 1 {
			next -= bitrateStep
			lowSince = time.Time{}
		} else if loss < 0.01 {
			if lowSince.IsZero() {
				lowSince = time.Now()
			} else if time.Since(lowSince) >= 2*time.Second {
				next += bitrateStep
				lowSince = time.Time{}
			}
		} else {
			lowSince = time.Time{}
		}
		next = clamp(next)
		if next != cur {
			s.bitrate = uint32(next)
			cb := s.onBitrate
			s.mu.Unlock()
			if cb != nil {
				cb(next)
			}
		} else {
			s.mu.Unlock()
		}
	}
}
func max32(a, b uint32) uint32 {
	if a > b {
		return a
	}
	return b
}
func clamp(b int) int {
	if b < minBitrate {
		return minBitrate
	}
	if b > 192000 {
		return 192000
	}
	return minBitrate + ((b-minBitrate+8000)/16000)*16000
}

// SendOpus sends one already encoded 20 ms Opus frame.
func (s *Sender) SendOpus(opus []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, e := protocol.Encode(protocol.Header{Codec: protocol.CodecOpus, Bitrate: s.bitrate, Session: s.session, Sequence: s.seq, FrameMilliseconds: s.frameMs, Flags: protocol.FlagFEC | protocol.FlagDTX}, opus)
	if e == nil {
		_, e = s.conn.Write(b)
		s.seq++
	}
	return e
}

// SendPCM is retained only for tests and is not used by the application path.
func (s *Sender) SendPCM(pcm []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, e := protocol.Encode(protocol.Header{Codec: protocol.CodecPCM, Bitrate: s.bitrate, Session: s.session, Sequence: s.seq, FrameMilliseconds: s.frameMs}, pcm)
	if e == nil {
		_, e = s.conn.Write(b)
		s.seq++
	}
	return e
}

func (s *Sender) Close() error {
	s.closeOnce.Do(func() {
		close(s.feedbackDone)
		_ = s.conn.Close()
	})
	s.feedbackWG.Wait()
	return nil
}
