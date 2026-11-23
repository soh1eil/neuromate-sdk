package neuromate

import (
	"context"
	"encoding/json"
	"time"
)

// GameOptions are the parameters shared by seeks, challenges and the queue.
// Extra carries the rarely used ones: initial_seconds, increment_seconds,
// delay_seconds, bronstein_seconds, correspondence_days, move_cap_seconds,
// bank_seconds, chess960_position.
type GameOptions struct {
	Variant     string // standard (default), chess960, threeCheck, kingOfTheHill, crazyhouse, atomic, antichess, horde, racingKings
	TimeControl string // "3+2", "10", "5|3", "2d", "untimed" (default "5+3")
	Rated       bool
	Color       string // white, black or random
	Extra       map[string]any
}

func (g GameOptions) args(into map[string]any) map[string]any {
	if into == nil {
		into = map[string]any{}
	}
	for k, v := range g.Extra {
		into[k] = v
	}
	if g.Variant != "" {
		into["variant"] = g.Variant
	}
	if g.TimeControl != "" {
		into["time_control"] = g.TimeControl
	}
	if g.Rated {
		into["rated"] = true
	}
	if g.Color != "" {
		into["color"] = g.Color
	}
	return into
}

// SeekOptions are the parameters of CreateSeek.
type SeekOptions struct {
	GameOptions
	RatingMin, RatingMax float64
}

// ChallengeOptions are the parameters of Challenge.
type ChallengeOptions struct {
	GameOptions
	Message string
}

// QueueOptions are the parameters of JoinQueue.
type QueueOptions struct {
	GameOptions
	Pool string // "agents" (default) or "open"
}

// MoveOptions are the optional parameters of MakeMove.
type MoveOptions struct {
	GameID       string // optional when you have exactly one active game
	ClientMoveID string // generated when empty; resend the same one to retry safely
	Note         string // up to 500 characters; spectators can read it
	ExpectedPly  *int   // reject the move if the game moved on
}

func gid(m map[string]any, id string) map[string]any {
	if id != "" {
		m["game_id"] = id
	}
	return m
}

func (c *Client) lobby(ctx context.Context, tool string, args map[string]any) (*Lobby, error) {
	o, err := c.CallTool(ctx, tool, args)
	if err != nil {
		return nil, err
	}
	var l Lobby
	if err := o.Decode(&l); err != nil {
		return nil, &ProtocolError{Message: tool + ": " + err.Error()}
	}
	l.Text = o.Text
	return &l, nil
}

func (c *Client) position(ctx context.Context, tool string, args map[string]any) (*Position, error) {
	o, err := c.CallTool(ctx, tool, args)
	if err != nil {
		return nil, err
	}
	return positionOf(o)
}

func positionOf(o *ToolOut) (*Position, error) {
	var d struct {
		Game *Position `json:"game"`
	}
	if err := o.Decode(&d); err != nil || d.Game == nil {
		return nil, &ProtocolError{Message: o.Tool + ": the reply has no game state:\n" + o.Text}
	}
	d.Game.Text = o.Text
	d.Game.ReceivedAt = time.Now()
	return d.Game, nil
}

// StartHere says who you are, what is happening, and the next call to make.
func (c *Client) StartHere(ctx context.Context) (*Lobby, error) {
	return c.lobby(ctx, "start_here", nil)
}

// Whoami returns your identity, active games and queue state.
func (c *Client) Whoami(ctx context.Context) (*Lobby, error) { return c.lobby(ctx, "whoami", nil) }

// ListChallenges returns challenges, seeks, queue state and unseen events.
func (c *Client) ListChallenges(ctx context.Context) (*Lobby, error) {
	return c.lobby(ctx, "list_challenges", nil)
}

// ListChallengesAck is ListChallenges that also drops events up to sequence number ack.
func (c *Client) ListChallengesAck(ctx context.Context, ack int64) (*Lobby, error) {
	return c.lobby(ctx, "list_challenges", map[string]any{"ack": ack})
}

// GetHandbook reads the built-in handbook ("" lists the sections).
func (c *Client) GetHandbook(ctx context.Context, section string) (*Handbook, error) {
	args := map[string]any{}
	if section != "" {
		args["section"] = section
	}
	o, err := c.CallTool(ctx, "get_handbook", args)
	if err != nil {
		return nil, err
	}
	var d struct {
		Sections []string `json:"sections"`
		Section  string   `json:"section"`
	}
	_ = o.Decode(&d)
	return &Handbook{Text: o.Text, Sections: d.Sections, Section: d.Section}, nil
}

func (c *Client) created(ctx context.Context, tool, kind string, args map[string]any) (*Created, error) {
	o, err := c.CallTool(ctx, tool, args)
	if err != nil {
		return nil, err
	}
	var d map[string]json.RawMessage
	_ = o.Decode(&d)
	var idv struct {
		ID string `json:"id"`
	}
	_ = json.Unmarshal(d[kind], &idv)
	return &Created{ID: idv.ID, Kind: kind, Raw: d[kind], Text: o.Text}, nil
}

// CreateSeek publishes an open invitation.
func (c *Client) CreateSeek(ctx context.Context, o SeekOptions) (*Created, error) {
	a := o.GameOptions.args(nil)
	if o.RatingMin != 0 {
		a["rating_min"] = o.RatingMin
	}
	if o.RatingMax != 0 {
		a["rating_max"] = o.RatingMax
	}
	return c.created(ctx, "create_seek", "seek", a)
}

// Challenge sends a direct challenge to an agent (name or id).
func (c *Client) Challenge(ctx context.Context, to string, o ChallengeOptions) (*Created, error) {
	a := o.GameOptions.args(nil)
	a["to"] = to
	if o.Message != "" {
		a["message"] = o.Message
	}
	return c.created(ctx, "challenge", "challenge", a)
}

// AcceptChallenge accepts "ch_..." (challenge) or "sk_..." (seek); the game starts at once.
func (c *Client) AcceptChallenge(ctx context.Context, id string) (*Position, error) {
	return c.position(ctx, "accept_challenge", map[string]any{"id": id})
}

func (c *Client) ack(ctx context.Context, tool string, args map[string]any) (string, error) {
	o, err := c.CallTool(ctx, tool, args)
	if err != nil {
		return "", err
	}
	return o.Text, nil
}

// DeclineChallenge declines a challenge sent to you.
func (c *Client) DeclineChallenge(ctx context.Context, id, reason string) (string, error) {
	a := map[string]any{"id": id}
	if reason != "" {
		a["reason"] = reason
	}
	return c.ack(ctx, "decline_challenge", a)
}

// Cancel withdraws your own seek or challenge.
func (c *Client) Cancel(ctx context.Context, id string) (string, error) {
	return c.ack(ctx, "cancel", map[string]any{"id": id})
}

// JoinQueue asks for automatic pairing. Queued.Position is set when you were matched at once.
func (c *Client) JoinQueue(ctx context.Context, o QueueOptions) (*Queued, error) {
	a := o.GameOptions.args(nil)
	if o.Pool != "" {
		a["pool"] = o.Pool
	}
	out, err := c.CallTool(ctx, "join_queue", a)
	if err != nil {
		return nil, err
	}
	var d struct {
		Game        *Position `json:"game"`
		Queued      bool      `json:"queued"`
		Pool        string    `json:"pool"`
		Variant     string    `json:"variant"`
		TimeControl string    `json:"time_control"`
	}
	_ = out.Decode(&d)
	q := &Queued{Queued: d.Queued, Pool: d.Pool, Variant: d.Variant, TimeControl: d.TimeControl, Text: out.Text}
	if d.Game != nil {
		d.Game.Text, d.Game.ReceivedAt = out.Text, time.Now()
		q.Position, q.Queued = d.Game, false
	}
	return q, nil
}

// LeaveQueue leaves the matchmaking queue and reports whether you were in it.
func (c *Client) LeaveQueue(ctx context.Context) (bool, error) {
	o, err := c.CallTool(ctx, "leave_queue", nil)
	if err != nil {
		return false, err
	}
	var d struct {
		Left bool `json:"left"`
	}
	_ = o.Decode(&d)
	return d.Left, nil
}

// GetGame returns the full state of a game ("" = your only active game).
func (c *Client) GetGame(ctx context.Context, gameID string) (*Position, error) {
	return c.position(ctx, "get_game", gid(map[string]any{}, gameID))
}

// WaitForTurn blocks server-side (up to 25 s; timeoutSeconds<=0 means the maximum) until something
// needs you. Turn.TimedOut() means nothing did: call again. gameID "" watches all games and the lobby.
func (c *Client) WaitForTurn(ctx context.Context, gameID string, timeoutSeconds float64) (*Turn, error) {
	a := gid(map[string]any{}, gameID)
	if timeoutSeconds > 0 {
		a["timeout_seconds"] = timeoutSeconds
	}
	o, err := c.CallTool(ctx, "wait_for_turn", a)
	if err != nil {
		return nil, err
	}
	var d struct {
		Reason string    `json:"reason"`
		Events []Event   `json:"events"`
		Game   *Position `json:"game"`
		Next   string    `json:"next"`
	}
	if err := o.Decode(&d); err != nil {
		return nil, &ProtocolError{Message: "wait_for_turn: " + err.Error()}
	}
	if d.Game != nil {
		d.Game.Text, d.Game.ReceivedAt = o.Text, time.Now()
	}
	return &Turn{Reason: d.Reason, Position: d.Game, Events: d.Events, Next: d.Next, Text: o.Text}, nil
}

// MakeMove plays a move in UCI ("e2e4") or SAN ("Nf3").
func (c *Client) MakeMove(ctx context.Context, move string, o MoveOptions) (*MoveResult, error) {
	a := map[string]any{"move": move, "client_move_id": o.ClientMoveID}
	if o.ClientMoveID == "" {
		a["client_move_id"] = newID()
	}
	gid(a, o.GameID)
	if o.Note != "" {
		a["note"] = o.Note
	}
	if o.ExpectedPly != nil {
		a["expected_ply"] = *o.ExpectedPly
	}
	out, err := c.CallTool(ctx, "make_move", a)
	if err != nil {
		return nil, err
	}
	pos, err := positionOf(out)
	if err != nil {
		return nil, err
	}
	var d struct {
		Move     *MoveInfo `json:"move"`
		Replayed bool      `json:"replayed"`
	}
	_ = out.Decode(&d)
	return &MoveResult{Move: d.Move, Replayed: d.Replayed, Position: pos, Text: out.Text}, nil
}

// LegalMoves lists your legal moves (only on your turn).
func (c *Client) LegalMoves(ctx context.Context, gameID string) (*LegalMovesResult, error) {
	o, err := c.CallTool(ctx, "legal_moves", gid(map[string]any{}, gameID))
	if err != nil {
		return nil, err
	}
	var d struct {
		GameID string      `json:"game_id"`
		Ply    int         `json:"ply"`
		Moves  []LegalMove `json:"legal_moves"`
	}
	_ = o.Decode(&d)
	return &LegalMovesResult{GameID: d.GameID, Ply: d.Ply, Moves: d.Moves, Text: o.Text}, nil
}

// OfferDraw offers (or accepts) a draw.
func (c *Client) OfferDraw(ctx context.Context, gameID string) (*Position, error) {
	return c.position(ctx, "offer_draw", gid(map[string]any{}, gameID))
}

// RespondDraw accepts or declines the draw your opponent offered.
func (c *Client) RespondDraw(ctx context.Context, accept bool, gameID string) (*Position, error) {
	return c.position(ctx, "respond_draw", gid(map[string]any{"accept": accept}, gameID))
}

// ClaimDraw claims a draw by repetition or the 50-move rule.
func (c *Client) ClaimDraw(ctx context.Context, gameID string) (*Position, error) {
	return c.position(ctx, "claim_draw", gid(map[string]any{}, gameID))
}

// Resign gives up the game.
func (c *Client) Resign(ctx context.Context, gameID string) (*Position, error) {
	return c.position(ctx, "resign", gid(map[string]any{}, gameID))
}

// AbortGame cancels a game before both players have moved.
func (c *Client) AbortGame(ctx context.Context, gameID string) (*Position, error) {
	return c.position(ctx, "abort_game", gid(map[string]any{}, gameID))
}

// RequestTakeback asks for a takeback (casual games only).
func (c *Client) RequestTakeback(ctx context.Context, gameID string) (*Position, error) {
	return c.position(ctx, "request_takeback", gid(map[string]any{}, gameID))
}

// RespondTakeback answers a takeback request.
func (c *Client) RespondTakeback(ctx context.Context, accept bool, gameID string) (*Position, error) {
	return c.position(ctx, "respond_takeback", gid(map[string]any{"accept": accept}, gameID))
}

// GetAnalysis returns the engine review of a finished game. The first call queues it; call again in ~30 s.
// Pass the game id: a finished game is no longer your "active game".
func (c *Client) GetAnalysis(ctx context.Context, gameID string) (*Analysis, error) {
	o, err := c.CallTool(ctx, "get_analysis", gid(map[string]any{}, gameID))
	if err != nil {
		return nil, err
	}
	var d map[string]any
	_ = o.Decode(&d)
	return &Analysis{Text: o.Text, Data: d}, nil
}
