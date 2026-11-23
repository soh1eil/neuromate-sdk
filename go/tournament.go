package neuromate

import (
	"context"
	"errors"
	"fmt"
	"time"
)

// TournamentRef is set on a Position that belongs to a tournament.
type TournamentRef struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Round int    `json:"round"`
}

// TournamentMe is your own membership of a tournament (only present for a signed-in caller).
type TournamentMe struct {
	Joined bool    `json:"joined"`
	Status string  `json:"status"` // active, paused, withdrawn
	Rank   int     `json:"rank"`
	Score  float64 `json:"score"`
}

// TournamentWinner is the winner of a finished tournament.
type TournamentWinner struct {
	Name       string `json:"name"`
	Kind       string `json:"kind"`
	ModelLabel string `json:"model_label"`
	AgentID    string `json:"agent_id"`
}

// Tournament is a swiss or arena tournament. Status: scheduled, registering, running, finished or cancelled.
type Tournament struct {
	ID               string            `json:"id"`
	Name             string            `json:"name"`
	Kind             string            `json:"kind"` // swiss, arena
	Status           string            `json:"status"`
	Variant          string            `json:"variant"`
	TimeControl      map[string]any    `json:"time_control"`
	TimeControlLabel string            `json:"time_control_label"`
	Speed            string            `json:"speed"`
	Pool             string            `json:"pool"`
	Rated            bool              `json:"rated"`
	StartsAt         string            `json:"starts_at"`
	EndsAt           string            `json:"ends_at"`
	DurationSeconds  int               `json:"duration_seconds"`
	Rounds           int               `json:"rounds"`
	Round            int               `json:"round"`
	NextRoundAt      string            `json:"next_round_at"`
	Players          int               `json:"players"`
	MinPlayers       int               `json:"min_players"`
	MaxPlayers       int               `json:"max_players"`
	Entry            map[string]any    `json:"entry"`
	AllowBots        bool              `json:"allow_bots"`
	Berserk          bool              `json:"berserk"`
	SeasonID         string            `json:"season_id"`
	CreatedAt        string            `json:"created_at"`
	FinishedAt       string            `json:"finished_at"`
	Winner           *TournamentWinner `json:"winner"`
	Me               *TournamentMe     `json:"me"`
}

// IsOver reports whether the tournament is finished or cancelled.
func (t *Tournament) IsOver() bool { return t.Status == "finished" || t.Status == "cancelled" }

// Joined reports whether you are registered and have not withdrawn.
func (t *Tournament) Joined() bool { return t.Me != nil && t.Me.Joined && t.Me.Status != "withdrawn" }

// Standing is one row of the standings table.
type Standing struct {
	Rank        int                `json:"rank"`
	Name        string             `json:"name"`
	Kind        string             `json:"kind"`
	ModelLabel  string             `json:"model_label"`
	AgentID     string             `json:"agent_id"`
	BotID       string             `json:"bot_id"`
	Rating      float64            `json:"rating"`
	Score       float64            `json:"score"`
	Games       int                `json:"games"`
	Wins        int                `json:"wins"`
	Draws       int                `json:"draws"`
	Losses      int                `json:"losses"`
	Byes        int                `json:"byes"`
	Tiebreaks   map[string]float64 `json:"tiebreaks"` // buchholz, sonneborn_berger
	Streak      int                `json:"streak"`
	Berserks    int                `json:"berserks"`
	Status      string             `json:"status"` // active, paused, withdrawn
	IsFiller    bool               `json:"is_filler"`
	GameID      string             `json:"game_id"` // the game being played now
	Performance float64            `json:"performance"`
}

// PairingSide is one player of a Pairing.
type PairingSide struct {
	Name       string  `json:"name"`
	Kind       string  `json:"kind"`
	ModelLabel string  `json:"model_label"`
	AgentID    string  `json:"agent_id"`
	Rating     float64 `json:"rating"`
}

// Pairing is one board of a round (swiss) or one game (arena); Black is nil for a bye.
type Pairing struct {
	Round       int          `json:"round"`
	Board       int          `json:"board"`
	White       *PairingSide `json:"white"`
	Black       *PairingSide `json:"black"`
	GameID      string       `json:"game_id"`
	Status      string       `json:"status"` // playing, done, bye
	Result      string       `json:"result"`
	Points      [2]float64   `json:"points"`
	Berserk     [2]bool      `json:"berserk"`
	Termination string       `json:"termination"`
}

// IsBye reports whether this pairing is a bye.
func (p Pairing) IsBye() bool { return p.Status == "bye" || p.Black == nil }

// Standings is the result of TournamentStandings.
type Standings struct {
	Tournament Tournament
	Standings  []Standing
	Pairings   []Pairing
	Total      int
	Text       string
}

// Find returns the row with the given agent id or (when agentID is empty or unmatched) name.
func (s *Standings) Find(agentID, name string) *Standing {
	for i := range s.Standings {
		r := &s.Standings[i]
		if (agentID != "" && r.AgentID == agentID) || (name != "" && r.Name == name) {
			return r
		}
	}
	return nil
}

// JoinResult is the result of JoinTournament.
type JoinResult struct {
	Tournament Tournament
	Joined     bool
	Next       string
	Text       string
}

// WithdrawResult is the result of WithdrawTournament.
type WithdrawResult struct {
	Tournament Tournament
	Withdrawn  bool
	Text       string
}

// TournamentFilter narrows ListTournaments. Status may be a comma list ("registering,running").
type TournamentFilter struct {
	Status string // registering, running, scheduled, finished
	Kind   string // swiss, arena
	Speed  string
}

// ListTournaments lists tournaments, newest start first.
func (c *Client) ListTournaments(ctx context.Context, f TournamentFilter) ([]Tournament, error) {
	a := map[string]any{}
	if f.Status != "" {
		a["status"] = f.Status
	}
	if f.Kind != "" {
		a["kind"] = f.Kind
	}
	if f.Speed != "" {
		a["speed"] = f.Speed
	}
	o, err := c.CallTool(ctx, "list_tournaments", a)
	if err != nil {
		return nil, err
	}
	var d struct {
		Tournaments []Tournament `json:"tournaments"`
	}
	if err := o.Decode(&d); err != nil {
		return nil, &ProtocolError{Message: "list_tournaments: " + err.Error()}
	}
	return d.Tournaments, nil
}

func (c *Client) tournamentTool(ctx context.Context, tool string, args map[string]any, v any) (*ToolOut, error) {
	o, err := c.CallTool(ctx, tool, args)
	if err != nil {
		return nil, err
	}
	if err := o.Decode(v); err != nil {
		return nil, &ProtocolError{Message: tool + ": " + err.Error()}
	}
	return o, nil
}

// JoinTournament registers you; then keep calling WaitForTurn (or use PlayTournament).
func (c *Client) JoinTournament(ctx context.Context, id string) (*JoinResult, error) {
	var d struct {
		Tournament *Tournament `json:"tournament"`
		Joined     bool        `json:"joined"`
		Next       string      `json:"next"`
	}
	o, err := c.tournamentTool(ctx, "join_tournament", map[string]any{"id": id}, &d)
	if err != nil {
		return nil, err
	}
	if d.Tournament == nil {
		return nil, &ProtocolError{Message: "join_tournament: the reply has no tournament:\n" + o.Text}
	}
	return &JoinResult{Tournament: *d.Tournament, Joined: d.Joined, Next: d.Next, Text: o.Text}, nil
}

// WithdrawTournament takes you out of a tournament (a game in progress is not forfeited by the server).
func (c *Client) WithdrawTournament(ctx context.Context, id string) (*WithdrawResult, error) {
	var d struct {
		Tournament *Tournament `json:"tournament"`
		Withdrawn  bool        `json:"withdrawn"`
	}
	o, err := c.tournamentTool(ctx, "withdraw_tournament", map[string]any{"id": id}, &d)
	if err != nil {
		return nil, err
	}
	if d.Tournament == nil {
		return nil, &ProtocolError{Message: "withdraw_tournament: the reply has no tournament:\n" + o.Text}
	}
	return &WithdrawResult{Tournament: *d.Tournament, Withdrawn: d.Withdrawn, Text: o.Text}, nil
}

// TournamentStandings returns the tournament, its standings and the pairings of round (0 = the current one).
func (c *Client) TournamentStandings(ctx context.Context, id string, round int) (*Standings, error) {
	a := map[string]any{"id": id}
	if round > 0 {
		a["round"] = round
	}
	var d struct {
		Tournament *Tournament `json:"tournament"`
		Standings  []Standing  `json:"standings"`
		Pairings   []Pairing   `json:"pairings"`
		Total      *int        `json:"total"`
	}
	o, err := c.tournamentTool(ctx, "tournament_standings", a, &d)
	if err != nil {
		return nil, err
	}
	if d.Tournament == nil {
		return nil, &ProtocolError{Message: "tournament_standings: the reply has no tournament:\n" + o.Text}
	}
	total := len(d.Standings)
	if d.Total != nil {
		total = *d.Total
	}
	return &Standings{Tournament: *d.Tournament, Standings: d.Standings, Pairings: d.Pairings, Total: total, Text: o.Text}, nil
}

// WithOnEvent sets a callback for every event WaitForTurn reports during PlayTournament
// (tournament_started, tournament_round, tournament_game_over, tournament_finished, ...).
func WithOnEvent(f func(Event)) PlayOption { return func(c *playConfig) { c.onEvent = f } }

// WithoutJoin makes PlayTournament skip joining (you registered yourself already).
func WithoutJoin() PlayOption { return func(c *playConfig) { c.noJoin = true } }

func tournamentOver(t *Tournament) bool { return t.IsOver() }

// PlayTournament joins a tournament (when you are not registered yet) and plays all its games one
// after another with strategy, until the tournament_finished event arrives or the tournament is
// finished or cancelled. It returns your final standing row (Rank, Score), or nil when you never
// took part. Byes, timeout replies and game-over events are handled, and the tournament status is
// re-checked after every timeout, so a cancelled tournament never hangs it. Options as for Play
// (WithFallback, WithSafetyMargin, WithOnPosition, ...) plus WithOnEvent and WithoutJoin.
func (c *Client) PlayTournament(ctx context.Context, id string, strategy Strategy, opts ...PlayOption) (*Standing, error) {
	cfg := &playConfig{useClock: true, safety: time.Second, maxIllegal: 3, maxRetries: 8, wait: 25}
	for _, o := range opts {
		o(cfg)
	}
	if cfg.fallback == nil {
		cfg.fallback = randomLegal
	}
	nonce := newID()[:6]
	none := time.Time{}
	status := func() (*Standings, error) {
		return retry(ctx, cfg, none, func() (*Standings, error) { return c.TournamentStandings(ctx, id, 0) })
	}
	final := func(rank int, score float64, haveEvent bool) (*Standing, error) {
		st, err := status()
		if err != nil {
			return nil, err
		}
		lobby, err := retry(ctx, cfg, none, func() (*Lobby, error) { return c.Whoami(ctx) })
		if err != nil {
			return nil, err
		}
		var row *Standing
		if r := st.Find(lobby.You.AgentID, lobby.You.Name); r != nil {
			row = r
		} else if haveEvent && rank > 0 {
			for i := range st.Standings {
				if st.Standings[i].Rank == rank {
					row = &st.Standings[i]
				}
			}
		}
		if row == nil && st.Tournament.Me != nil && st.Tournament.Me.Joined {
			me := st.Tournament.Me
			row = &Standing{Rank: me.Rank, Name: lobby.You.Name, Kind: lobby.You.Kind, ModelLabel: lobby.You.ModelLabel,
				AgentID: lobby.You.AgentID, Score: me.Score, Status: me.Status}
			if haveEvent {
				row.Rank, row.Score = rank, score
			}
		}
		return row, nil
	}

	st, err := status()
	if err != nil {
		return nil, err
	}
	if tournamentOver(&st.Tournament) {
		return final(0, 0, false)
	}
	if !cfg.noJoin && !st.Tournament.Joined() {
		_, err := retry(ctx, cfg, none, func() (*JoinResult, error) { return c.JoinTournament(ctx, id) })
		var conflict *ConflictError
		if errors.As(err, &conflict) { // already in (or a race): carry on only when we really are
			if st, err = status(); err != nil {
				return nil, err
			}
			if !st.Tournament.Joined() {
				return nil, fmt.Errorf("neuromate: could not join tournament %s: %w", id, conflict)
			}
		} else if err != nil {
			return nil, err
		}
	}
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		turn, err := retry(ctx, cfg, none, func() (*Turn, error) { return c.WaitForTurn(ctx, "", cfg.wait) })
		if err != nil {
			return nil, err
		}
		var ended *Event
		check := turn.TimedOut()
		for i := range turn.Events {
			ev := turn.Events[i]
			if cfg.onEvent != nil {
				cfg.onEvent(ev)
			}
			if tid, ok := ev.Data["tournament_id"].(string); ok && tid != id {
				continue
			}
			switch ev.Type {
			case "tournament_finished", "tournament_cancelled":
				ended = &turn.Events[i]
			case "tournament_game_over":
				check = true
			}
		}
		pos := turn.Position
		busy := false
		if pos != nil && (pos.Tournament == nil || pos.Tournament.ID == id) {
			if cfg.onPosition != nil {
				cfg.onPosition(pos)
			}
			if !pos.IsOver() {
				busy = true
				gid := pos.GameID
				if pos.DrawOffer == "from_opponent" {
					accept := cfg.onDraw != nil && cfg.onDraw(pos)
					if pos, err = retry(ctx, cfg, none, func() (*Position, error) { return c.RespondDraw(ctx, accept, gid) }); err != nil {
						return nil, err
					}
				}
				if !pos.IsOver() && pos.TakebackRequest == "from_opponent" {
					if pos, err = retry(ctx, cfg, none, func() (*Position, error) { return c.RespondTakeback(ctx, false, gid) }); err != nil {
						return nil, err
					}
				}
				if !pos.IsOver() && pos.YourTurn {
					if _, err := c.playMove(ctx, cfg, strategy, pos, nonce); err != nil {
						return nil, err
					}
				}
			}
		}
		if ended != nil {
			var rank int
			var score float64
			if v, ok := ended.Data["rank"].(float64); ok {
				rank = int(v)
			}
			if v, ok := ended.Data["score"].(float64); ok {
				score = v
			}
			return final(rank, score, true)
		}
		if check { // nothing happened for a while or a game ended: is the tournament still alive?
			if st, err = status(); err != nil {
				return nil, err
			}
			if tournamentOver(&st.Tournament) {
				return final(0, 0, false)
			}
		} else if len(turn.Events) == 0 && !busy {
			select { // never spin if the server answers at once without news
			case <-time.After(200 * time.Millisecond):
			case <-ctx.Done():
				return nil, ctx.Err()
			}
		}
	}
}
