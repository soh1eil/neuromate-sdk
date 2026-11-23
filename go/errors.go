package neuromate

import (
	"encoding/json"
	"errors"
	"fmt"
	"time"
)

// Error is implemented by every error this package returns.
// Retryable reports whether repeating the same call may succeed.
type Error interface {
	error
	Retryable() bool
}

// TransportError means the request never got an answer (connection refused, timeout, DNS).
type TransportError struct {
	Msg string
	Err error
}

func (e *TransportError) Error() string   { return e.Msg }
func (e *TransportError) Unwrap() error   { return e.Err }
func (e *TransportError) Retryable() bool { return true }

// HTTPError is an unexpected HTTP status.
type HTTPError struct {
	Status int
	Body   string
}

func (e *HTTPError) Error() string   { return fmt.Sprintf("HTTP %d: %s", e.Status, e.Body) }
func (e *HTTPError) Retryable() bool { return false }

// AuthError is HTTP 401: the API key is missing, wrong or revoked. Servers
// that send the unified JSON body fill Code ("unauthorized"), Message and Hint.
type AuthError struct {
	Status  int
	Body    string
	Code    string
	Message string
	Hint    string
}

func (e *AuthError) Error() string {
	if e.Message != "" {
		if e.Hint != "" {
			return e.Message + ". " + e.Hint
		}
		return e.Message
	}
	if e.Body == "" {
		return "invalid or missing API key: send it as Authorization: Bearer nm_live_..."
	}
	return e.Body
}
func (e *AuthError) Retryable() bool { return false }

// ServerError is HTTP 5xx or a platform INTERNAL ERROR. Safe to retry.
type ServerError struct {
	Status int
	Body   string
}

func (e *ServerError) Error() string   { return fmt.Sprintf("server error %d: %s", e.Status, e.Body) }
func (e *ServerError) Retryable() bool { return true }

// RateLimitedError means too many requests.
type RateLimitedError struct {
	Message    string
	RetryAfter time.Duration
}

func (e *RateLimitedError) Error() string   { return e.Message }
func (e *RateLimitedError) Retryable() bool { return true }

// ProtocolError is a JSON-RPC level error (unknown tool, invalid params, unreadable reply).
type ProtocolError struct {
	Code    int
	Message string
	Data    any
}

func (e *ProtocolError) Error() string   { return e.Message }
func (e *ProtocolError) Retryable() bool { return false }

func (e *ProtocolError) data() (d struct {
	Code string `json:"code"`
	Hint string `json:"hint"`
}) {
	var raw string
	switch v := e.Data.(type) {
	case string:
		raw = v
	case []byte:
		raw = string(v)
	}
	_ = json.Unmarshal([]byte(raw), &d)
	return d
}

// ErrorCode is the stable snake_case code ("unknown_tool", "invalid_params"...)
// when the server sent one, else "".
func (e *ProtocolError) ErrorCode() string { return e.data().Code }

// Hint is the server's "what to do" line, when it sent one.
func (e *ProtocolError) Hint() string { return e.data().Hint }

// ToolError means a tool ran and said no. Error() is the server's complete teaching text.
type ToolError struct {
	Tool string
	// Code is the stable machine code: "illegal_move", "not_your_turn",
	// "invalid_params", "no_game", ... (docs/MCP.md has the full list).
	Code       string
	Message    string // what was wrong
	Hint       string // the server's "WHAT TO DO" line
	LegalMoves []string
	Position   *Position // the game, when the server attached it
	Text       string
	// Context holds every other field of the error (illegal_attempts_remaining,
	// game_id, retry_after_seconds, ...); numbers are float64.
	Context map[string]any
}

func (e *ToolError) Error() string {
	if e.Text != "" {
		return e.Text
	}
	return e.Message
}
func (e *ToolError) Retryable() bool { return false }

// Typed tool errors. Each embeds *ToolError, so errors.As works with both the
// specific type and *ToolError.
type (
	IllegalMoveError struct{ *ToolError }
	NotYourTurnError struct{ *ToolError }
	// StalePlyError: expected_ply no longer matches; re-read the game.
	StalePlyError  struct{ *ToolError }
	GameOverError  struct{ *ToolError }
	ForbiddenError struct{ *ToolError }
	NotFoundError  struct{ *ToolError }
	// InvalidParamsError: a parameter was missing, unknown, of the wrong type or out of range.
	InvalidParamsError struct{ *ToolError }
	// BadRequestError is the legacy code of servers before unified error codes.
	BadRequestError struct{ *ToolError }
	// NoGameError: you have no game at all (game_id is required).
	NoGameError     struct{ *ToolError }
	NotAllowedError struct{ *ToolError }
	ConflictError   struct{ *ToolError }
)

func (e *IllegalMoveError) Unwrap() error   { return e.ToolError }
func (e *NotYourTurnError) Unwrap() error   { return e.ToolError }
func (e *StalePlyError) Unwrap() error      { return e.ToolError }
func (e *GameOverError) Unwrap() error      { return e.ToolError }
func (e *ForbiddenError) Unwrap() error     { return e.ToolError }
func (e *NotFoundError) Unwrap() error      { return e.ToolError }
func (e *InvalidParamsError) Unwrap() error { return e.ToolError }
func (e *BadRequestError) Unwrap() error    { return e.ToolError }
func (e *NoGameError) Unwrap() error        { return e.ToolError }
func (e *NotAllowedError) Unwrap() error    { return e.ToolError }
func (e *ConflictError) Unwrap() error      { return e.ToolError }

func newToolError(te *ToolError) error {
	switch te.Code {
	case "illegal_move":
		return &IllegalMoveError{te}
	case "not_your_turn":
		return &NotYourTurnError{te}
	case "stale_ply":
		return &StalePlyError{te}
	case "game_over":
		return &GameOverError{te}
	case "forbidden":
		return &ForbiddenError{te}
	case "not_found":
		return &NotFoundError{te}
	case "invalid_params":
		return &InvalidParamsError{te}
	case "bad_request":
		return &BadRequestError{te}
	case "no_game":
		return &NoGameError{te}
	case "not_allowed":
		return &NotAllowedError{te}
	case "conflict":
		return &ConflictError{te}
	}
	return te
}

// ErrResign: return it from a Strategy to resign the game.
var ErrResign = errors.New("neuromate: resign")

// IsRetryable reports whether err (anywhere in its chain) is retryable.
func IsRetryable(err error) bool {
	var e Error
	return errors.As(err, &e) && e.Retryable()
}
