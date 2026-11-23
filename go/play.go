package neuromate

import (
	"context"
	"errors"
	"fmt"
	"math/rand"
	"sort"
	"time"
)

// Move is what a Strategy returns: a move in UCI or SAN and an optional note spectators can read.
type Move struct {
	Move string
	Note string
}

// UCI makes a Move from a move string (UCI or SAN).
func UCI(move string) Move { return Move{Move: move} }

// Strategy chooses a move. It is called whenever it is your turn. ctx expires when the clock
// (minus the safety margin) runs out: honour it, or the fallback move is sent instead.
// Return ErrResign to resign.
type Strategy func(ctx context.Context, pos *Position) (Move, error)

type playConfig struct {
	gameID, opponent, variant, timeControl, pool string
	rated, acceptChallenges, useClock            bool
	safety                                       time.Duration
	maxIllegal, maxRetries                       int
	wait                                         float64
	fallback                                     func(*Position) Move
	onPosition                                   func(*Position)
	onDraw                                       func(*Position) bool
	findTimeout                                  time.Duration
	onEvent                                      func(Event)
	noJoin                                       bool
}

// PlayOption configures Play.
type PlayOption func(*playConfig)

func WithGameID(id string) PlayOption             { return func(c *playConfig) { c.gameID = id } }
func WithOpponent(name string) PlayOption         { return func(c *playConfig) { c.opponent = name } }
func WithVariant(v string) PlayOption             { return func(c *playConfig) { c.variant = v } }
func WithTimeControl(tc string) PlayOption        { return func(c *playConfig) { c.timeControl = tc } }
func WithRated(r bool) PlayOption                 { return func(c *playConfig) { c.rated = r } }
func WithPool(p string) PlayOption                { return func(c *playConfig) { c.pool = p } }
func WithAcceptChallenges() PlayOption            { return func(c *playConfig) { c.acceptChallenges = true } }
func WithOnPosition(f func(*Position)) PlayOption { return func(c *playConfig) { c.onPosition = f } }

// WithOnDrawOffer decides whether to accept a draw offer (default: decline).
func WithOnDrawOffer(f func(*Position) bool) PlayOption { return func(c *playConfig) { c.onDraw = f } }

// WithFindTimeout gives up when no game starts within d.
func WithFindTimeout(d time.Duration) PlayOption { return func(c *playConfig) { c.findTimeout = d } }

// WithMaxRetries bounds retries of transient failures per call (default 8).
func WithMaxRetries(n int) PlayOption { return func(c *playConfig) { c.maxRetries = n } }

// WithMaxIllegal is the number of rejected moves before the fallback move is used (default 3).
func WithMaxIllegal(n int) PlayOption { return func(c *playConfig) { c.maxIllegal = n } }

// WithSafetyMargin is the time kept in reserve for the network (default 1 s).
func WithSafetyMargin(d time.Duration) PlayOption { return func(c *playConfig) { c.safety = d } }

// WithClockBudget switches the clock-derived deadline on or off (default on).
func WithClockBudget(on bool) PlayOption { return func(c *playConfig) { c.useClock = on } }

// WithFallback sets the move sent when the strategy is too slow or keeps failing (default: random legal move).
func WithFallback(f func(*Position) Move) PlayOption { return func(c *playConfig) { c.fallback = f } }

func randomLegal(p *Position) Move {
	if len(p.LegalMoves) == 0 {
		return Move{}
	}
	return UCI(p.LegalMoves[rand.Intn(len(p.LegalMoves))].UCI)
}

var errStrategyTimeout = errors.New("neuromate: strategy out of time")

// retry runs fn, repeating transient failures with backoff (the same arguments, so a move keeps its client_move_id).
func retry[T any](ctx context.Context, cfg *playConfig, deadline time.Time, fn func() (T, error)) (T, error) {
	delay := 250 * time.Millisecond
	for i := 0; ; i++ {
		v, err := fn()
		if err == nil {
			return v, nil
		}
		var e Error
		if ctx.Err() != nil {
			return v, ctx.Err()
		}
		if !errors.As(err, &e) || !e.Retryable() || i >= cfg.maxRetries {
			return v, err
		}
		wait := delay + time.Duration(rand.Int63n(int64(delay)))
		var rl *RateLimitedError
		if errors.As(err, &rl) {
			wait = rl.RetryAfter
		}
		if wait > 30*time.Second {
			wait = 30 * time.Second
		}
		if !deadline.IsZero() && time.Now().Add(wait).After(deadline.Add(5*time.Second)) {
			return v, err
		}
		select {
		case <-time.After(wait):
		case <-ctx.Done():
			return v, ctx.Err()
		}
		if delay < 4*time.Second {
			delay *= 2
		}
	}
}

// Play plays one complete game and returns the final position (Result tells who won).
//
// Without WithGameID it resumes your active game, else challenges WithOpponent, else joins the
// matchmaking queue. Cancelling ctx while still queued leaves the queue.
func (c *Client) Play(ctx context.Context, strategy Strategy, opts ...PlayOption) (*Position, error) {
	cfg := &playConfig{useClock: true, safety: time.Second, maxIllegal: 3, maxRetries: 8, wait: 25}
	for _, o := range opts {
		o(cfg)
	}
	if cfg.fallback == nil {
		cfg.fallback = randomLegal
	}
	nonce := newID()[:6]
	gameID := cfg.gameID
	queued := false
	defer func() {
		if queued && gameID == "" {
			lctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_, _ = c.LeaveQueue(lctx)
		}
	}()
	noDeadline := time.Time{}

	if gameID == "" {
		lobby, err := retry(ctx, cfg, noDeadline, func() (*Lobby, error) { return c.Whoami(ctx) })
		if err != nil {
			return nil, err
		}
		if len(lobby.Events) > 0 { // leftovers from earlier games must not be mistaken for this one
			var max int64
			for _, e := range lobby.Events {
				if e.Seq > max {
					max = e.Seq
				}
			}
			if _, err := retry(ctx, cfg, noDeadline, func() (*Lobby, error) { return c.ListChallengesAck(ctx, max) }); err != nil {
				return nil, err
			}
		}
		mine := lobby.ActiveGames
		sort.SliceStable(mine, func(i, j int) bool { return mine[i].YourTurn && !mine[j].YourTurn })
		switch {
		case len(mine) > 0:
			gameID = mine[0].GameID
		case cfg.opponent != "":
			g := GameOptions{Variant: cfg.variant, TimeControl: cfg.timeControl, Rated: cfg.rated}
			if _, err := retry(ctx, cfg, noDeadline, func() (*Created, error) {
				return c.Challenge(ctx, cfg.opponent, ChallengeOptions{GameOptions: g})
			}); err != nil {
				return nil, err
			}
		default:
			g := GameOptions{Variant: cfg.variant, TimeControl: cfg.timeControl, Rated: cfg.rated}
			// A join that errors may still have queued us: leave on the way out.
			queued = true
			q, err := retry(ctx, cfg, noDeadline, func() (*Queued, error) {
				return c.JoinQueue(ctx, QueueOptions{GameOptions: g, Pool: cfg.pool})
			})
			if err != nil {
				return nil, err
			}
			if q.Position != nil {
				gameID, queued = q.Position.GameID, false
			}
		}
	}
	started := time.Now()
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if gameID == "" && cfg.findTimeout > 0 && time.Since(started) > cfg.findTimeout {
			return nil, fmt.Errorf("neuromate: no game started within %s", cfg.findTimeout)
		}
		turn, err := retry(ctx, cfg, noDeadline, func() (*Turn, error) { return c.WaitForTurn(ctx, gameID, cfg.wait) })
		if err != nil {
			return nil, err
		}
		if gameID == "" && cfg.acceptChallenges {
			for _, ev := range turn.Events {
				id, _ := ev.Data["challenge_id"].(string)
				if ev.Type == "challenge_received" && id != "" {
					p, err := retry(ctx, cfg, noDeadline, func() (*Position, error) { return c.AcceptChallenge(ctx, id) })
					if err != nil {
						return nil, err
					}
					turn.Position = p
				}
			}
		}
		pos := turn.Position
		if pos == nil || (gameID == "" && pos.IsOver()) {
			continue
		}
		if gameID == "" {
			gameID, queued = pos.GameID, false
		}
		if pos.GameID != gameID {
			continue
		}
		if cfg.onPosition != nil {
			cfg.onPosition(pos)
		}
		if pos.IsOver() {
			return pos, nil
		}
		if pos.DrawOffer == "from_opponent" {
			accept := cfg.onDraw != nil && cfg.onDraw(pos)
			if pos, err = retry(ctx, cfg, noDeadline, func() (*Position, error) { return c.RespondDraw(ctx, accept, gameID) }); err != nil {
				return nil, err
			}
			if pos.IsOver() {
				return pos, nil
			}
		}
		if pos.TakebackRequest == "from_opponent" {
			if pos, err = retry(ctx, cfg, noDeadline, func() (*Position, error) { return c.RespondTakeback(ctx, false, gameID) }); err != nil {
				return nil, err
			}
		}
		if !pos.YourTurn {
			continue
		}
		done, err := c.playMove(ctx, cfg, strategy, pos, nonce)
		if err != nil {
			return nil, err
		}
		if done != nil && done.IsOver() {
			return done, nil
		}
	}
}

// think runs the strategy under the clock budget.
func think(ctx context.Context, cfg *playConfig, s Strategy, view *Position) (Move, error) {
	tctx, cancel := ctx, context.CancelFunc(func() {})
	if cfg.useClock {
		if secs, ok := view.Clock.SecondsForThisMove(); ok {
			budget := time.Duration(secs*float64(time.Second)) - cfg.safety
			if budget <= 0 {
				return Move{}, errStrategyTimeout
			}
			tctx, cancel = context.WithTimeout(ctx, budget)
		}
	}
	defer cancel()
	type res struct {
		m   Move
		err error
	}
	ch := make(chan res, 1)
	go func() {
		defer func() {
			if r := recover(); r != nil {
				ch <- res{err: fmt.Errorf("neuromate: strategy panicked: %v", r)}
			}
		}()
		m, err := s(tctx, view)
		ch <- res{m, err}
	}()
	select {
	case r := <-ch:
		if r.err != nil && !errors.Is(r.err, ErrResign) && tctx.Err() != nil && ctx.Err() == nil {
			return Move{}, errStrategyTimeout
		}
		return r.m, r.err
	case <-tctx.Done():
		if ctx.Err() != nil {
			return Move{}, ctx.Err()
		}
		return Move{}, errStrategyTimeout
	}
}

func (c *Client) playMove(ctx context.Context, cfg *playConfig, s Strategy, pos *Position, nonce string) (*Position, error) {
	var attempts []IllegalAttempt
	n := 0
	for {
		view := *pos
		view.IllegalAttempts = attempts
		var mv Move
		panicked := false
		if len(attempts) >= cfg.maxIllegal {
			mv, panicked = cfg.fallback(&view), true
		} else {
			m, err := think(ctx, cfg, s, &view)
			switch {
			case errors.Is(err, ErrResign):
				return retry(ctx, cfg, time.Time{}, func() (*Position, error) { return c.Resign(ctx, pos.GameID) })
			case errors.Is(err, errStrategyTimeout):
				mv, panicked = cfg.fallback(&view), true
			case err != nil:
				return nil, err
			default:
				mv = m
			}
		}
		if mv.Move == "" {
			return nil, errors.New("neuromate: your strategy returned an empty move; return a move such as UCI(\"e2e4\") or Move{\"Nf3\", \"note\"}, or ErrResign")
		}
		_ = panicked
		n++
		ply := pos.Ply
		opts := MoveOptions{GameID: pos.GameID, ClientMoveID: fmt.Sprintf("%s-%d-%s-%d", pos.GameID, pos.Ply+1, nonce, n), Note: mv.Note, ExpectedPly: &ply}
		deadline, _ := pos.Deadline()
		res, err := retry(ctx, cfg, deadline, func() (*MoveResult, error) { return c.MakeMove(ctx, mv.Move, opts) })
		var (
			illegal *IllegalMoveError
			stale   *StalePlyError
			notTurn *NotYourTurnError
			over    *GameOverError
		)
		switch {
		case err == nil:
			return res.Position, nil
		case errors.As(err, &illegal):
			attempts = append(attempts, IllegalAttempt{Move: mv.Move, Error: illegal.Message})
			if len(attempts) > cfg.maxIllegal+2 {
				return nil, err
			}
		case errors.As(err, &stale):
			fresh, gerr := retry(ctx, cfg, time.Time{}, func() (*Position, error) { return c.GetGame(ctx, pos.GameID) })
			if gerr != nil {
				return nil, gerr
			}
			if fresh.IsOver() || !fresh.YourTurn {
				return fresh, nil
			}
			pos, attempts = fresh, nil
		case errors.As(err, &notTurn):
			return nil, nil
		case errors.As(err, &over):
			if over.Position != nil {
				return over.Position, nil
			}
			return retry(ctx, cfg, time.Time{}, func() (*Position, error) { return c.GetGame(ctx, pos.GameID) })
		default:
			return nil, err
		}
	}
}
