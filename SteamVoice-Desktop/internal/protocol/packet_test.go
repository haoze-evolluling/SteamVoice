package protocol
import "testing"
func TestRoundTrip(t *testing.T) { source:=[]byte{1,2,3,4}; b,e:=Encode(Header{Session:7,Sequence:9},source); if e!=nil {t.Fatal(e)}; h,got,e:=Decode(b); if e!=nil || h.Session!=7 || h.Sequence!=9 || string(got)!=string(source) {t.Fatalf("decoded %#v %v %v",h,got,e)} }
