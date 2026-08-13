package protocol

import (
	"encoding/binary"
	"errors"
)
const ( Magic = "SV01"; HeaderSize = 24; SampleRate = 48000; Channels = 2; BitsPerSample = 16 )
type Header struct { Session uint32; Sequence uint32; Timestamp uint64; PayloadLength uint16 }
func Encode(h Header, pcm []byte) ([]byte, error) { if len(pcm)>65535 { return nil, errors.New("pcm frame exceeds UDP packet limit") }; b:=make([]byte,HeaderSize+len(pcm)); copy(b,Magic); b[4]=1; b[5]=Channels; binary.BigEndian.PutUint32(b[6:],SampleRate); b[10]=BitsPerSample; binary.BigEndian.PutUint32(b[12:],h.Session); binary.BigEndian.PutUint32(b[16:],h.Sequence); binary.BigEndian.PutUint16(b[20:],uint16(len(pcm))); binary.BigEndian.PutUint16(b[22:],0); copy(b[HeaderSize:],pcm); return b,nil }
func Decode(b []byte) (Header, []byte, error) { if len(b)<HeaderSize || string(b[:4])!=Magic || b[4]!=1 { return Header{},nil,errors.New("invalid SteamVoice packet") }; n:=int(binary.BigEndian.Uint16(b[20:])); if len(b)!=HeaderSize+n { return Header{},nil,errors.New("invalid payload length") }; return Header{Session:binary.BigEndian.Uint32(b[12:]),Sequence:binary.BigEndian.Uint32(b[16:]),PayloadLength:uint16(n)},b[HeaderSize:],nil }
