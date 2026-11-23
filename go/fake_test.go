package neuromate

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func fixture(t testing.TB, name string) map[string]any {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "testdata", name+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var d map[string]any
	if err := json.Unmarshal(b, &d); err != nil {
		t.Fatal(err)
	}
	return d
}

func result(t testing.TB, name string) map[string]any {
	return fixture(t, name)["response"].(map[string]any)["result"].(map[string]any)
}

func clone(v map[string]any) map[string]any {
	b, _ := json.Marshal(v)
	var out map[string]any
	_ = json.Unmarshal(b, &out)
	return out
}

// waitResult builds a wait_for_turn result around the game of a recorded fixture.
func waitResult(t testing.TB, reason, gameFrom string, patch map[string]any) map[string]any {
	g := clone(result(t, gameFrom)["structuredContent"].(map[string]any)["game"].(map[string]any))
	for k, v := range patch {
		g[k] = v
	}
	return map[string]any{
		"content":           []any{map[string]any{"type": "text", "text": "WAIT"}},
		"structuredContent": map[string]any{"reason": reason, "events": []any{}, "game": g},
	}
}

// reply kinds
type httpReply struct {
	status int
	body   string
}
type dropReply struct{}
type rpcErrReply struct{ err map[string]any }

type fakeCall struct {
	Name string
	Args map[string]any
}

type fakeServer struct {
	t      testing.TB
	srv    *httptest.Server
	mu     sync.Mutex
	script map[string][]any
	calls  []fakeCall
}

func newFake(t testing.TB, script map[string][]any) *fakeServer {
	f := &fakeServer{t: t, script: map[string][]any{}}
	for k, v := range script {
		f.script[k] = append([]any(nil), v...)
	}
	f.srv = httptest.NewServer(http.HandlerFunc(f.handle))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeServer) client(opts ...Option) *Client {
	c, err := NewClient("nm_live_test", append([]Option{WithBaseURL(f.srv.URL)}, opts...)...)
	if err != nil {
		f.t.Fatal(err)
	}
	return c
}

func (f *fakeServer) names() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []string
	for _, c := range f.calls {
		out = append(out, c.Name)
	}
	return out
}

func (f *fakeServer) callsOf(name string) []fakeCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []fakeCall
	for _, c := range f.calls {
		if c.Name == name {
			out = append(out, c)
		}
	}
	return out
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func (f *fakeServer) handle(w http.ResponseWriter, r *http.Request) {
	var msg struct {
		ID     json.RawMessage `json:"id"`
		Method string          `json:"method"`
		Params struct {
			Name      string         `json:"name"`
			Arguments map[string]any `json:"arguments"`
		} `json:"params"`
	}
	_ = json.NewDecoder(r.Body).Decode(&msg)
	rpc := func(result any) map[string]any {
		return map[string]any{"jsonrpc": "2.0", "id": msg.ID, "result": result}
	}
	switch {
	case msg.Method == "initialize":
		writeJSON(w, rpc(fixture(f.t, "initialize")["response"].(map[string]any)["result"]))
		return
	case len(msg.ID) == 0:
		w.WriteHeader(202)
		return
	case msg.Method == "tools/list":
		writeJSON(w, rpc(fixture(f.t, "tools_list")["response"].(map[string]any)["result"]))
		return
	}
	f.mu.Lock()
	f.calls = append(f.calls, fakeCall{msg.Params.Name, msg.Params.Arguments})
	q := f.script[msg.Params.Name]
	if len(q) == 0 {
		f.mu.Unlock()
		f.t.Errorf("unexpected tool call %s %v", msg.Params.Name, msg.Params.Arguments)
		w.WriteHeader(500)
		return
	}
	rep := q[0]
	if len(q) > 1 {
		f.script[msg.Params.Name] = q[1:]
	}
	f.mu.Unlock()
	switch v := rep.(type) {
	case dropReply:
		panic(http.ErrAbortHandler)
	case httpReply:
		w.WriteHeader(v.status)
		_, _ = w.Write([]byte(v.body))
	case rpcErrReply:
		writeJSON(w, map[string]any{"jsonrpc": "2.0", "id": msg.ID, "error": v.err})
	default:
		writeJSON(w, rpc(rep))
	}
}

var bg = context.Background()
