package neuromate

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// DefaultURL is the production MCP endpoint.
const DefaultURL = "https://api.neuromate.larsima.com/mcp"

const (
	protocolVersion = "2025-06-18"
	// Version is the SDK version.
	Version = "0.1.0"
)

// Client talks to the Neuromate MCP server. It is safe for concurrent use.
type Client struct {
	apiKey  string
	baseURL string
	http    *http.Client
	timeout time.Duration

	ids atomic.Int64
	mu  sync.Mutex // guards ready, session, serverInfo
	// session is the Mcp-Session-Id, if the server hands one out.
	session    string
	ready      bool
	serverInfo map[string]any
}

// Option configures a Client.
type Option func(*Client)

// WithBaseURL sets the MCP endpoint (default DefaultURL or $NEUROMATE_URL).
func WithBaseURL(u string) Option { return func(c *Client) { c.baseURL = u } }

// WithHTTPClient supplies your own http.Client.
func WithHTTPClient(h *http.Client) Option { return func(c *Client) { c.http = h } }

// WithTimeout sets the per-call timeout (default 30 s; wait_for_turn adds its wait time + 15 s).
func WithTimeout(d time.Duration) Option { return func(c *Client) { c.timeout = d } }

// NewClient creates a client. With an empty apiKey it reads $NEUROMATE_API_KEY
// (and $NEUROMATE_URL for the endpoint).
func NewClient(apiKey string, opts ...Option) (*Client, error) {
	c := &Client{apiKey: apiKey, http: &http.Client{}, timeout: 30 * time.Second}
	for _, o := range opts {
		o(c)
	}
	if c.apiKey == "" {
		c.apiKey = os.Getenv("NEUROMATE_API_KEY")
	}
	if c.apiKey == "" {
		return nil, errors.New("neuromate: no API key: pass one to NewClient or set NEUROMATE_API_KEY (keys look like nm_live_...)")
	}
	if c.baseURL == "" {
		c.baseURL = os.Getenv("NEUROMATE_URL")
	}
	if c.baseURL == "" {
		c.baseURL = DefaultURL
	}
	return c, nil
}

// String never reveals the key.
func (c *Client) String() string {
	k := c.apiKey
	if len(k) > 8 {
		k = k[:8]
	}
	return fmt.Sprintf("neuromate.Client{%s key=%s...}", c.baseURL, k)
}

// ServerInfo is the serverInfo from the initialize handshake (after the first call).
func (c *Client) ServerInfo() map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.serverInfo
}

// ---- raw protocol ----

type rpcReq struct {
	JSONRPC string `json:"jsonrpc"`
	ID      *int64 `json:"id,omitempty"`
	Method  string `json:"method"`
	Params  any    `json:"params,omitempty"`
}

type rpcResp struct {
	ID     json.RawMessage `json:"id"`
	Result json.RawMessage `json:"result"`
	Error  *struct {
		Code    int             `json:"code"`
		Message string          `json:"message"`
		Data    json.RawMessage `json:"data"`
	} `json:"error"`
}

func (c *Client) post(ctx context.Context, method string, params any, notification bool, timeout time.Duration) (json.RawMessage, error) {
	req := rpcReq{JSONRPC: "2.0", Method: method, Params: params}
	var id int64
	if !notification {
		id = c.ids.Add(1)
		req.ID = &id
	}
	body, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	hr, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL, bytes.NewReader(body))
	if err != nil {
		return nil, &TransportError{Msg: "bad request: " + err.Error(), Err: err}
	}
	hr.Header.Set("Authorization", "Bearer "+c.apiKey)
	hr.Header.Set("Content-Type", "application/json")
	hr.Header.Set("Accept", "application/json, text/event-stream")
	hr.Header.Set("MCP-Protocol-Version", protocolVersion)
	hr.Header.Set("User-Agent", "neuromate-go/"+Version)
	c.mu.Lock()
	if c.session != "" {
		hr.Header.Set("Mcp-Session-Id", c.session)
	}
	c.mu.Unlock()
	resp, err := c.http.Do(hr)
	if err != nil {
		return nil, &TransportError{Msg: fmt.Sprintf("could not reach %s: %v", c.baseURL, err), Err: err}
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if err != nil {
		return nil, &TransportError{Msg: "reading reply: " + err.Error(), Err: err}
	}
	if err := checkHTTP(resp.StatusCode, resp.Header, string(raw)); err != nil {
		return nil, err
	}
	if notification || resp.StatusCode == 202 || resp.StatusCode == 204 {
		return nil, nil
	}
	if sid := resp.Header.Get("Mcp-Session-Id"); sid != "" {
		c.mu.Lock()
		c.session = sid
		c.mu.Unlock()
	}
	return parseRPC(resp.Header.Get("Content-Type"), string(raw), id)
}

func checkHTTP(status int, h http.Header, body string) error {
	if status == 200 || status == 202 || status == 204 {
		return nil
	}
	snippet := strings.TrimSpace(body)
	if len(snippet) > 500 {
		snippet = snippet[:500]
	}
	switch {
	case status == 401:
		ae := &AuthError{Status: status, Body: snippet}
		var j struct {
			Code    string `json:"code"`
			Message string `json:"message"`
			Hint    string `json:"hint"`
			Error   *struct {
				Data struct {
					Code    string `json:"code"`
					Message string `json:"message"`
					Hint    string `json:"hint"`
				} `json:"data"`
			} `json:"error"`
		}
		if json.Unmarshal([]byte(body), &j) == nil {
			ae.Code, ae.Message, ae.Hint = j.Code, j.Message, j.Hint
			if j.Error != nil && j.Error.Data.Message != "" {
				ae.Code, ae.Message, ae.Hint = j.Error.Data.Code, j.Error.Data.Message, j.Error.Data.Hint
			}
		}
		return ae
	case status == 429:
		ra := time.Second
		if f, err := strconv.ParseFloat(h.Get("Retry-After"), 64); err == nil {
			ra = time.Duration(f * float64(time.Second))
		}
		if snippet == "" {
			snippet = "rate limited"
		}
		return &RateLimitedError{Message: snippet, RetryAfter: ra}
	case status >= 500:
		return &ServerError{Status: status, Body: snippet}
	}
	var r rpcResp
	if json.Unmarshal([]byte(body), &r) == nil && r.Error != nil {
		return &ProtocolError{Code: r.Error.Code, Message: r.Error.Message, Data: string(r.Error.Data)}
	}
	return &HTTPError{Status: status, Body: snippet}
}

var sseSplit = regexp.MustCompile(`\r?\n\r?\n`)

func ssePayload(body string, id int64) string {
	last := ""
	for _, block := range sseSplit.Split(body, -1) {
		var data []string
		for _, line := range strings.Split(block, "\n") {
			line = strings.TrimRight(line, "\r")
			if strings.HasPrefix(line, "data:") {
				data = append(data, strings.TrimLeft(line[5:], " "))
			}
		}
		if len(data) == 0 {
			continue
		}
		last = strings.Join(data, "\n")
		var r rpcResp
		if json.Unmarshal([]byte(last), &r) == nil && string(r.ID) == strconv.FormatInt(id, 10) {
			return last
		}
	}
	return last
}

func parseRPC(contentType, body string, id int64) (json.RawMessage, error) {
	if strings.Contains(contentType, "text/event-stream") {
		body = ssePayload(body, id)
	}
	var r rpcResp
	if err := json.Unmarshal([]byte(body), &r); err != nil {
		s := body
		if len(s) > 200 {
			s = s[:200]
		}
		return nil, &ProtocolError{Message: fmt.Sprintf("the server sent something that is not JSON-RPC: %q", s)}
	}
	if r.Error != nil {
		return nil, &ProtocolError{Code: r.Error.Code, Message: r.Error.Message, Data: string(r.Error.Data)}
	}
	return r.Result, nil
}

func (c *Client) connect(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.ready {
		return nil
	}
	// c.post takes c.mu briefly, so release while calling.
	c.mu.Unlock()
	res, err := c.post(ctx, "initialize", map[string]any{
		"protocolVersion": protocolVersion,
		"capabilities":    map[string]any{},
		"clientInfo":      map[string]any{"name": "neuromate-go", "version": Version},
	}, false, c.timeout)
	if err == nil {
		_, err = c.post(ctx, "notifications/initialized", nil, true, c.timeout)
	}
	c.mu.Lock()
	if err != nil {
		return err
	}
	var ir struct {
		ServerInfo map[string]any `json:"serverInfo"`
	}
	_ = json.Unmarshal(res, &ir)
	c.serverInfo = ir.ServerInfo
	c.ready = true
	return nil
}

// ToolOut is a decoded tools/call result.
type ToolOut struct {
	Tool string
	Text string
	// Data is the raw structuredContent.
	Data    json.RawMessage
	IsError bool
}

// Decode unmarshals the structured content into v.
func (o *ToolOut) Decode(v any) error {
	if len(o.Data) == 0 {
		return nil
	}
	return json.Unmarshal(o.Data, v)
}

var reWait = regexp.MustCompile(`Wait (\d+) seconds`)

// CallTool calls any MCP tool by name. Tool-level failures come back as typed errors.
func (c *Client) CallTool(ctx context.Context, name string, args map[string]any) (*ToolOut, error) {
	if err := c.connect(ctx); err != nil {
		return nil, err
	}
	if args == nil {
		args = map[string]any{}
	}
	timeout := c.timeout
	if name == "wait_for_turn" {
		w := 25.0
		if v, ok := args["timeout_seconds"].(float64); ok && v > 0 {
			w = v
		}
		timeout = time.Duration((w + 15) * float64(time.Second))
	}
	res, err := c.post(ctx, "tools/call", map[string]any{"name": name, "arguments": args}, false, timeout)
	if err != nil {
		return nil, err
	}
	if len(res) == 0 {
		return nil, &ProtocolError{Message: "tools/call " + name + " returned no result"}
	}
	var r struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
		Structured json.RawMessage `json:"structuredContent"`
		IsError    bool            `json:"isError"`
	}
	if err := json.Unmarshal(res, &r); err != nil {
		return nil, &ProtocolError{Message: "unreadable tool result: " + err.Error()}
	}
	var sb strings.Builder
	for _, p := range r.Content {
		if p.Type == "text" {
			sb.WriteString(p.Text)
		}
	}
	out := &ToolOut{Tool: name, Text: sb.String(), Data: r.Structured, IsError: r.IsError}
	if string(out.Data) == "null" {
		out.Data = nil
	}
	if out.IsError {
		return nil, out.toError()
	}
	return out, nil
}

func (o *ToolOut) toError() error {
	t := o.Text
	var d struct {
		Error map[string]any `json:"error"`
		Game  *Position      `json:"game"`
	}
	_ = o.Decode(&d)
	str := func(k string) string { s, _ := d.Error[k].(string); return s }
	code := str("code")
	// Servers with unified error codes: decide by code. The text prefixes
	// below are only for older servers.
	if code == "rate_limited" || strings.HasPrefix(t, "RATE LIMITED") {
		ra := time.Second
		if f, ok := d.Error["retry_after_seconds"].(float64); ok {
			ra = time.Duration(f * float64(time.Second))
		} else if m := reWait.FindStringSubmatch(t); m != nil {
			n, _ := strconv.Atoi(m[1])
			ra = time.Duration(n) * time.Second
		}
		return &RateLimitedError{Message: strings.TrimSpace(t), RetryAfter: ra}
	}
	msg := str("message")
	if msg == "" {
		msg = strings.TrimSpace(t)
	}
	if code == "internal" || strings.HasPrefix(t, "INTERNAL ERROR") || msg == "internal error" {
		return &ServerError{Status: 500, Body: strings.TrimSpace(t)}
	}
	hint := str("hint")
	if hint == "" {
		hint = str("what_to_do")
	}
	var legal []string
	if l, ok := d.Error["legal_moves"].([]any); ok {
		for _, m := range l {
			if s, ok := m.(string); ok {
				legal = append(legal, s)
			}
		}
	}
	ctx := map[string]any{}
	for k, v := range d.Error {
		switch k {
		case "code", "message", "hint", "what_to_do", "legal_moves":
		default:
			ctx[k] = v
		}
	}
	if d.Game != nil {
		d.Game.Text = t
		d.Game.ReceivedAt = time.Now()
	}
	return newToolError(&ToolError{Tool: o.Tool, Code: code, Message: msg, Hint: hint, LegalMoves: legal, Position: d.Game, Text: t, Context: ctx})
}

// ListTools returns the raw tools/list entries.
func (c *Client) ListTools(ctx context.Context) ([]map[string]any, error) {
	if err := c.connect(ctx); err != nil {
		return nil, err
	}
	res, err := c.post(ctx, "tools/list", map[string]any{}, false, c.timeout)
	if err != nil {
		return nil, err
	}
	var r struct {
		Tools []map[string]any `json:"tools"`
	}
	if err := json.Unmarshal(res, &r); err != nil {
		return nil, &ProtocolError{Message: err.Error()}
	}
	return r.Tools, nil
}

func newID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
