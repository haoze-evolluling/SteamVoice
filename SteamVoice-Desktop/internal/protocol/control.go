package protocol

import (
	"encoding/binary"
	"errors"
)

const ControlSize = 30

type ReceiverFeedback struct {
	Session, HighestSeq, Received, Lost uint32
	Queue                               uint16
	Bitrate                             uint32
}

func EncodeFeedback(f ReceiverFeedback) []byte {
	b := make([]byte, ControlSize)
	copy(b, "SVCT")
	b[4] = Version
	b[5] = 1
	binary.BigEndian.PutUint32(b[8:], f.Session)
	binary.BigEndian.PutUint32(b[12:], f.HighestSeq)
	binary.BigEndian.PutUint32(b[16:], f.Received)
	binary.BigEndian.PutUint32(b[20:], f.Lost)
	binary.BigEndian.PutUint16(b[24:], f.Queue)
	binary.BigEndian.PutUint32(b[26:], f.Bitrate)
	return b
}
func DecodeFeedback(b []byte) (ReceiverFeedback, error) {
	if len(b) != ControlSize || string(b[:4]) != "SVCT" || b[4] != Version || b[5] != 1 {
		return ReceiverFeedback{}, errors.New("invalid feedback")
	}
	return ReceiverFeedback{binary.BigEndian.Uint32(b[8:]), binary.BigEndian.Uint32(b[12:]), binary.BigEndian.Uint32(b[16:]), binary.BigEndian.Uint32(b[20:]), binary.BigEndian.Uint16(b[24:]), binary.BigEndian.Uint32(b[26:])}, nil
}
