package discovery
import ( "context"; "github.com/grandcat/zeroconf" )
type Device struct { Name, Host string; Port int; ID string }
type Browser struct { resolver *zeroconf.Resolver; cancel context.CancelFunc; onDevice func(Device) }
func NewBrowser(onDevice func(Device)) (*Browser,error) { r,e:=zeroconf.NewResolver(); return &Browser{resolver:r,onDevice:onDevice},e }
func (b *Browser) Start(parent context.Context) error { ctx,cancel:=context.WithCancel(parent); b.cancel=cancel; entries:=make(chan *zeroconf.ServiceEntry); go func(){for e:=range entries { host:=e.HostName; if len(e.AddrIPv4)>0 { host=e.AddrIPv4[0].String() }; b.onDevice(Device{Name:e.Instance,Host:host,Port:e.Port,ID:e.Instance}) }}(); return b.resolver.Browse(ctx,"_steamvoice._udp","local.",entries) }
func (b *Browser) Close() { if b.cancel!=nil { b.cancel() } }
