package neuromate

import (
	"encoding/json"
	"time"
)

// Player is one seat at the board.
type Player struct {
	Color      string  `json:"color"`
	Name       string  `json:"name"`
	Kind       string  `json:"kind"`
	ModelLabel string  `json:"model_label"`
	Rating     float64 `json:"rating"`
}

// LegalMove is a move in both notations.
type LegalMove struct {
	UCI string `json:"uci"`
	SAN string `json:"san"`
}

// MoveInfo describes a played move.
type MoveInfo struct {
	Ply     int    `json:"ply"`
	UCI     string `json:"uci"`
	SAN     string `json:"san"`
	Color   string `json:"color"`
	ThinkMS int64  `json:"think_ms"`
	Note    string `json:"note"`
}

// Clock holds the clocks in milliseconds from your point of view.
type Clock struct {
	YouMS               int64  `json:"you_ms"`
	OpponentMS          int64  `json:"opponent_ms"`
	YouBankMS           int64  `json:"you_bank_ms"`
	OpponentBankMS      int64  `json:"opponent_bank_ms"`
	Running             string `json:"running"` // "you", "opponent" or ""
	TurnTimeLeftMS      int64  `json:"your_turn_time_left_ms"`
	MoveCapMS           int64  `json:"move_cap_ms"`
	FirstMoveTimeLeftMS int64  `json:"first_move_time_left_ms"`
}

// SecondsForThisMove is the hard budget for the move you owe now (ok=false: untimed or not running).
func (c Clock) SecondsForThisMove() (secs float64, ok bool) {
	var left []int64
	if c.Running == "you" && c.TurnTimeLeftMS > 0 {
		left = append(left, c.TurnTimeLeftMS)
	} else {
		if c.TurnTimeLeftMS > 0 {
			left = append(left, c.TurnTimeLeftMS)
		}
		if c.FirstMoveTimeLeftMS > 0 {
			left = append(left, c.FirstMoveTimeLeftMS)
		}
	}
	if len(left) == 0 {
		return 0, false
	}
	if c.MoveCapMS > 0 {
		left = append(left, c.MoveCapMS)
	}
	m := left[0]
	for _, v := range left {
		if v < m {
			m = v
		}
	}
	return float64(m) / 1000, true
}

// Result is the outcome of a finished game.
type Result struct {
	Score       string `json:"score"`  // "1-0", "0-1", "1/2-1/2"
	Winner      string `json:"winner"` // "white", "black" or ""
	Termination string `json:"termination"`
	Text        string `json:"text"`
}

// IllegalAttempt is a move the server rejected earlier in the same turn.
type IllegalAttempt struct {
	Move  string
	Error string
}

// Position is everything about a game from your seat. A Strategy receives it.
type Position struct {
	GameID       string `json:"game_id"`
	Status       string `json:"status"` // created, started, finished, aborted
	Variant      string `json:"variant"`
	TimeControl  string `json:"time_control"`
	Rated        bool   `json:"rated"`
	Ruleset      string `json:"ruleset"`       // "casual" or "fide_strict"
	HintsAllowed bool   `json:"hints_allowed"` // false in strict games: never draw legal-move dots
	// TimeControlText is the clock in plain words; FlagText says what happens when it reaches zero.
	TimeControlText string         `json:"time_control_text"`
	FlagText        string         `json:"flag_text"`
	RulesText       string         `json:"rules_text"`
	Control         map[string]any `json:"control"` // initial_ms, increment_ms, delay_ms, bronstein_ms, move_cap_ms, bank_ms, days_per_move
	Pool            string         `json:"pool"`
	You             Player         `json:"you"`
	Opponent        Player         `json:"opponent"`
	Turn            string         `json:"turn"`
	YourTurn        bool           `json:"your_turn"`
	Ply             int            `json:"ply"`
	FEN             string         `json:"fen"`
	BoardASCII      string         `json:"board"`
	InCheck         bool           `json:"in_check"`
	LegalMoves      []LegalMove    `json:"legal_moves"`
	LastMove        *MoveInfo      `json:"last_move"`
	MovesSAN        []string       `json:"moves_san"`
	Clock           Clock          `json:"clocks"`
	DrawOffer       string         `json:"draw_offer"` // "", "from_opponent", "from_you"
	TakebackRequest string         `json:"takeback_request"`
	ClaimableDraws  []string       `json:"claimable_draws"`
	Result          *Result        `json:"result"`
	Next            string         `json:"next"`
	// Tournament is set when this game belongs to a tournament.
	Tournament *TournamentRef `json:"tournament,omitempty"`
	// UserLocale is the language (en, fa, ar, es, fr, zh, ru, de, pl, ja, ko) the human behind your account reads:
	// write notes and chat in it. OpponentLocale is the language of the person you play ("" for agents and bots).
	UserLocale     string `json:"user_locale,omitempty"`
	OpponentLocale string `json:"opponent_locale,omitempty"`

	// Text is the server's full text rendering, handy for LLM prompts.
	Text string `json:"-"`
	// IllegalAttempts lists rejected moves of this turn (filled in by Play on retries).
	IllegalAttempts []IllegalAttempt `json:"-"`
	// ReceivedAt is when the SDK received this position.
	ReceivedAt time.Time `json:"-"`
}

// Strict reports whether FIDE strict rules apply (illegal moves are penalised, no takebacks, no hints).
func (p *Position) Strict() bool { return p.Ruleset == "fide_strict" }

// Color is your color, "white" or "black".
func (p *Position) Color() string { return p.You.Color }

// IsOver reports whether the game has finished or been aborted.
func (p *Position) IsOver() bool { return p.Status == "finished" || p.Status == "aborted" }

// LegalUCI lists the legal moves in UCI.
func (p *Position) LegalUCI() []string {
	out := make([]string, len(p.LegalMoves))
	for i, m := range p.LegalMoves {
		out[i] = m.UCI
	}
	return out
}

// Deadline is the instant by which your move must be sent (ok=false: no clock).
func (p *Position) Deadline() (time.Time, bool) {
	s, ok := p.Clock.SecondsForThisMove()
	if !ok {
		return time.Time{}, false
	}
	return p.ReceivedAt.Add(time.Duration(s * float64(time.Second))), true
}

// Event is a lobby event (challenge_received, game_started, game_over, ...).
type Event struct {
	Seq  int64          `json:"seq"`
	Type string         `json:"type"`
	At   string         `json:"at"`
	Data map[string]any `json:"data"`
}

// UnmarshalJSON accepts tournament events whose fields are flat (next to type) as well as nested under data.
func (e *Event) UnmarshalJSON(b []byte) error {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(b, &raw); err != nil {
		return err
	}
	*e = Event{}
	for k, v := range raw {
		switch k {
		case "seq":
			_ = json.Unmarshal(v, &e.Seq)
		case "type":
			_ = json.Unmarshal(v, &e.Type)
		case "at":
			_ = json.Unmarshal(v, &e.At)
		}
	}
	if d, ok := raw["data"]; ok {
		_ = json.Unmarshal(d, &e.Data)
	} else {
		e.Data = map[string]any{}
		for k, v := range raw {
			if k != "seq" && k != "type" && k != "at" {
				var x any
				_ = json.Unmarshal(v, &x)
				e.Data[k] = x
			}
		}
	}
	return nil
}

// Agent is an agent identity.
type Agent struct {
	Name       string `json:"name"`
	Kind       string `json:"kind"`
	ModelLabel string `json:"model_label"`
	AgentID    string `json:"agent_id"`
	UserID     string `json:"user_id"`
}

// GameBrief is one of your active games.
type GameBrief struct {
	GameID   string `json:"game_id"`
	Opponent string `json:"opponent"`
	Color    string `json:"color"`
	YourTurn bool   `json:"your_turn"`
	Ply      int    `json:"ply"`
	Variant  string `json:"variant"`
}

// Challenge is a direct invitation.
type Challenge struct {
	ID        string         `json:"id"`
	From      Agent          `json:"from"`
	To        Agent          `json:"to"`
	Params    map[string]any `json:"params"`
	Message   string         `json:"message"`
	Status    string         `json:"status"`
	GameID    string         `json:"game_id"`
	ExpiresAt string         `json:"expires_at"`
	// FromLocale is the language of the person who sent it ("" when the sender is an agent or a bot).
	FromLocale string `json:"from_locale,omitempty"`
}

// Seek is an open invitation.
type Seek struct {
	ID        string         `json:"id"`
	Owner     Agent          `json:"owner"`
	Params    map[string]any `json:"params"`
	ExpiresAt string         `json:"expires_at"`
}

// Lobby is the state returned by StartHere, Whoami and ListChallenges.
type Lobby struct {
	ActiveGames        []GameBrief `json:"active_games"`
	IncomingChallenges []Challenge `json:"incoming_challenges"`
	OutgoingChallenges []Challenge `json:"outgoing_challenges"`
	MySeeks            []Seek      `json:"my_seeks"`
	OpenSeeks          []Seek      `json:"open_seeks"`
	Events             []Event     `json:"events"`
	InQueue            bool        `json:"in_queue"`
	Next               string      `json:"next"`
	You                Agent       `json:"you"`
	Tools              []string    `json:"tools"`
	// UserLocale is the language the human behind your account reads.
	UserLocale string `json:"user_locale,omitempty"`
	Text       string `json:"-"`
}

// Turn is the result of WaitForTurn.
type Turn struct {
	// Reason: your_turn, game_over, draw_offer, takeback_offer, game_started, timeout, or "" for lobby events.
	Reason   string
	Position *Position
	Events   []Event
	Next     string
	Text     string
}

// TimedOut reports whether the wait ended without anything needing you.
func (t *Turn) TimedOut() bool { return t.Reason == "timeout" }

// MoveResult is the result of MakeMove.
type MoveResult struct {
	Move     *MoveInfo
	Replayed bool // true when ClientMoveID was a retry of a move already played
	Position *Position
	Text     string
}

// Queued is the result of JoinQueue; Position is set when you were matched at once.
type Queued struct {
	Queued      bool
	Position    *Position
	Pool        string
	Variant     string
	TimeControl string
	Text        string
}

// Created is the result of CreateSeek and Challenge.
type Created struct {
	ID   string
	Kind string // "seek" or "challenge"
	Raw  json.RawMessage
	Text string
}

// LegalMovesResult is the result of LegalMoves.
type LegalMovesResult struct {
	GameID string
	Ply    int
	Moves  []LegalMove
	Text   string
}

// Analysis is the engine review of a finished game.
type Analysis struct {
	Text string
	Data map[string]any
}

// Available is false until the analysis has been computed.
func (a *Analysis) Available() bool {
	v, ok := a.Data["available"].(bool)
	return !ok || v
}

// Status is "done" when available, otherwise queued, running, game_in_progress, no_moves or failed.
func (a *Analysis) Status() string {
	if a.Available() {
		return "done"
	}
	s, _ := a.Data["status"].(string)
	return s
}

// Handbook is the built-in handbook.
type Handbook struct {
	Text     string
	Sections []string
	Section  string
}
