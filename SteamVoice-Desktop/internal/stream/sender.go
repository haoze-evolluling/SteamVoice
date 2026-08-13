package stream

import (
	"crypto/rand"
	"encoding/binary"
	"net"
	"steamvoice-desktop/internal/protocol"
	"sync"
)

type Sender struct {
	conn         *net.UDPConn
	session, seq uint32
	bitrate      uint32
	mu           sync.Mutex
	muted        bool
}

func NewSender(address string, bitrate ...int) (*Sender, error) {
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
	if len(bitrate) > 0 && bitrate[0] > 0 {
		br = bitrate[0]
	}
	return &Sender{conn: c, session: binary.BigEndian.Uint32(raw[:]), bitrate: uint32(br)}, nil
}

// SendOpus sends one already encoded 20 ms Opus frame.
func (s *Sender) SendOpus(opus []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, e := protocol.Encode(protocol.Header{Codec: protocol.CodecOpus, Bitrate: s.bitrate, Session: s.session, Sequence: s.seq, Muted: s.muted}, opus)
	if e == nil {
		_, e = s.conn.Write(b)
		s.seq++
	}
	return e
}

// SendPCM is retained as an API boundary; callers must provide Opus payloads in v2.
func (s *Sender) SendPCM(pcm []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, e := protocol.Encode(protocol.Header{Codec: protocol.CodecPCM, Bitrate: s.bitrate, Session: s.session, Sequence: s.seq, Muted: s.muted}, pcm)
	if e == nil {
		_, e = s.conn.Write(b)
		s.seq++
	}
	return e
}

func (s *Sender) SetMuted(muted bool) { s.mu.Lock(); s.muted = muted; s.mu.Unlock() }
func (s *Sender) Close() error        { return s.conn.Close() }
