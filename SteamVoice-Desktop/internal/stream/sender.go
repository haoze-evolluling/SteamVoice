package stream
import ( "crypto/rand"; "encoding/binary"; "net"; "sync"; "steamvoice-desktop/internal/protocol" )
type Sender struct { conn *net.UDPConn; session, seq uint32; mu sync.Mutex }
func NewSender(address string) (*Sender,error) { a,e:=net.ResolveUDPAddr("udp",address); if e!=nil{return nil,e}; c,e:=net.DialUDP("udp",nil,a); if e!=nil{return nil,e}; var raw [4]byte; _,_=rand.Read(raw[:]); return &Sender{conn:c,session:binary.BigEndian.Uint32(raw[:])},nil }
func (s *Sender) SendPCM(pcm []byte) error { s.mu.Lock(); defer s.mu.Unlock(); b,e:=protocol.Encode(protocol.Header{Session:s.session,Sequence:s.seq},pcm); if e==nil { _,e=s.conn.Write(b); s.seq++ }; return e }
func (s *Sender) Close() error { return s.conn.Close() }
