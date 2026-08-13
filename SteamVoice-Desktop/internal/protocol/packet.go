package protocol

import (
	"encoding/binary"
	"errors"
)

const (
	Magic             = "SV01"
	Version           = 2
	CodecOpus         = 1
	HeaderSize        = 32
	SampleRate        = 48000
	Channels          = 2
	FrameMilliseconds = 20
)

// Header is the metadata carried by every v2 Opus datagram.
type Header struct {
	Codec             uint8
	SampleRate        uint32
	Channels          uint8
	Bitrate           uint32
	Session           uint32
	Sequence          uint32
	PayloadLength     uint16
	FrameMilliseconds uint16
}

func Encode(h Header, opus []byte) ([]byte, error) {
	if len(opus) > 65535 {
		return nil, errors.New("opus frame exceeds UDP packet limit")
	}
	if h.Codec == 0 {
		h.Codec = CodecOpus
	}
	if h.SampleRate == 0 {
		h.SampleRate = SampleRate
	}
	if h.Channels == 0 {
		h.Channels = Channels
	}
	if h.FrameMilliseconds == 0 {
		h.FrameMilliseconds = FrameMilliseconds
	}
	b := make([]byte, HeaderSize+len(opus))
	copy(b, Magic)
	b[4] = Version
	b[5] = h.Codec
	binary.BigEndian.PutUint32(b[6:], h.SampleRate)
	b[10] = h.Channels
	binary.BigEndian.PutUint32(b[12:], h.Bitrate)
	binary.BigEndian.PutUint32(b[16:], h.Session)
	binary.BigEndian.PutUint32(b[20:], h.Sequence)
	binary.BigEndian.PutUint16(b[24:], uint16(len(opus)))
	binary.BigEndian.PutUint16(b[26:], h.FrameMilliseconds)
	copy(b[HeaderSize:], opus)
	return b, nil
}

func Decode(b []byte) (Header, []byte, error) {
	if len(b) < HeaderSize || string(b[:4]) != Magic || b[4] != Version || b[5] != CodecOpus {
		return Header{}, nil, errors.New("invalid SteamVoice v2 packet")
	}
	h := Header{Codec: b[5], SampleRate: binary.BigEndian.Uint32(b[6:]), Channels: b[10], Bitrate: binary.BigEndian.Uint32(b[12:]), Session: binary.BigEndian.Uint32(b[16:]), Sequence: binary.BigEndian.Uint32(b[20:]), PayloadLength: binary.BigEndian.Uint16(b[24:]), FrameMilliseconds: binary.BigEndian.Uint16(b[26:])}
	if h.SampleRate != SampleRate || h.Channels != Channels || h.FrameMilliseconds == 0 {
		return Header{}, nil, errors.New("unsupported audio format")
	}
	n := int(h.PayloadLength)
	if len(b) != HeaderSize+n {
		return Header{}, nil, errors.New("invalid payload length")
	}
	return h, b[HeaderSize:], nil
}
