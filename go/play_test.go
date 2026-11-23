package neuromate

import (
	"context"
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func gameID(t *testing.T) string {
	return result(t, "get_game_start")["structuredContent"].(map[string]any)["game"].(map[string]any)["game_id"].(string)
}

type env struct {
	your, over map[string]any
	gid        string
}

func newEnv(t *testing.T) env {
	gid := gameID(t)
	return env{your: waitResult(t, "your_turn", "get_game_start", nil), over: waitResult(t, "game_over", "resign", map[string]any{"game_id": gid}), gid: gid}
}

func (e env) base(t *testing.T, more map[string][]any) map[string][]any {
	s := map[string][]any{"whoami": {result(t, "whoami_idle")}, "join_queue": {result(t, "join_queue_waiting")}}
	for k, v := range more {
		s[k] = v
	}
	return s
}

func fixed(m string) Strategy {
	return func(ctx context.Context, p *Position) (Move, error) { return UCI(m), nil }
}

func TestQueueThenPlayUntilGameOver(t *testing.T) {
	e := newEnv(t)
	f := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {e.your, e.over}, "make_move": {result(t, "move_ok")}}))
	var seen int
	final, err := f.client().Play(bg, func(ctx context.Context, p *Position) (Move, error) {
		seen++
		return Move{"e4", "centre"}, nil
	}, WithTimeControl("3+0"))
	if err != nil || !final.IsOver() || seen != 1 {
		t.Fatalf("%v %v %d", final, err, seen)
	}
	if got := strings.Join(f.names(), ","); got != "whoami,join_queue,wait_for_turn,make_move,wait_for_turn" {
		t.Fatal(got)
	}
	mv := f.callsOf("make_move")[0].Args
	if mv["move"] != "e4" || mv["note"] != "centre" || mv["expected_ply"] != float64(0) || !strings.HasPrefix(mv["client_move_id"].(string), e.gid) {
		t.Fatalf("%v", mv)
	}
	if jq := f.callsOf("join_queue")[0].Args; jq["time_control"] != "3+0" || len(jq) != 1 {
		t.Fatalf("%v", jq)
	}
}

func TestResumeActiveGame(t *testing.T) {
	e := newEnv(t)
	busy := clone(result(t, "whoami_idle"))
	busy["structuredContent"].(map[string]any)["active_games"] = []any{map[string]any{"game_id": e.gid, "opponent": "Bob", "color": "white", "your_turn": true, "ply": 0, "variant": "standard"}}
	f := newFake(t, map[string][]any{"whoami": {busy}, "wait_for_turn": {e.your, e.over}, "make_move": {result(t, "move_ok")}})
	if _, err := f.client().Play(bg, fixed("e4")); err != nil {
		t.Fatal(err)
	}
	if contains(f.names(), "join_queue") || f.callsOf("wait_for_turn")[0].Args["game_id"] != e.gid {
		t.Fatal(f.names())
	}
}

func TestIllegalMoveFedBackAndNewID(t *testing.T) {
	e := newEnv(t)
	f := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {e.your, e.over}, "make_move": {result(t, "err_illegal_move"), result(t, "move_ok")}}))
	var errs [][]IllegalAttempt
	_, err := f.client().Play(bg, func(ctx context.Context, p *Position) (Move, error) {
		errs = append(errs, p.IllegalAttempts)
		if len(p.IllegalAttempts) == 0 {
			return UCI("e2e5"), nil
		}
		return UCI("e2e4"), nil
	})
	if err != nil || len(errs) != 2 || len(errs[0]) != 0 || !strings.Contains(errs[1][0].Error, "pawn on e2 cannot move to e5") {
		t.Fatalf("%v %v", err, errs)
	}
	ms := f.callsOf("make_move")
	if ms[0].Args["client_move_id"] == ms[1].Args["client_move_id"] {
		t.Fatal("new move needs a new id")
	}
}

func TestIllegalBudgetFallsBack(t *testing.T) {
	e := newEnv(t)
	ill := result(t, "err_illegal_move")
	f := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {e.your, e.over}, "make_move": {ill, ill, ill, result(t, "move_ok")}}))
	if _, err := f.client().Play(bg, fixed("zzzz"), WithMaxIllegal(3)); err != nil {
		t.Fatal(err)
	}
	ms := f.callsOf("make_move")
	if len(ms) != 4 || ms[2].Args["move"] != "zzzz" || len(ms[3].Args["move"].(string)) != 4 || ms[3].Args["move"] == "zzzz" {
		t.Fatalf("%v", ms)
	}
}

func TestTransientErrorsRetrySameID(t *testing.T) {
	e := newEnv(t)
	f := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {e.your, e.over}, "make_move": {dropReply{}, httpReply{503, "unavailable"}, result(t, "move_ok")}}))
	t0 := time.Now()
	if _, err := f.client().Play(bg, fixed("e4")); err != nil {
		t.Fatal(err)
	}
	ms := f.callsOf("make_move")
	ids := map[any]bool{}
	for _, m := range ms {
		ids[m.Args["client_move_id"]] = true
	}
	if len(ms) != 3 || len(ids) != 1 || time.Since(t0) > 5*time.Second {
		t.Fatalf("%d %v %v", len(ms), ids, time.Since(t0))
	}
}

func TestGiveUpAfterMaxRetries(t *testing.T) {
	e := newEnv(t)
	f := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {e.your}, "make_move": {dropReply{}}}))
	_, err := f.client().Play(bg, fixed("e4"), WithMaxRetries(1))
	var tr *TransportError
	if !errors.As(err, &tr) {
		t.Fatalf("%v", err)
	}
}

func TestStalePlyRereads(t *testing.T) {
	e := newEnv(t)
	f := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {e.your, e.over}, "make_move": {result(t, "err_stale_ply"), result(t, "move_ok")}, "get_game": {result(t, "get_game_start")}}))
	var n int
	_, err := f.client().Play(bg, func(ctx context.Context, p *Position) (Move, error) { n++; return UCI("e4"), nil })
	if err != nil || n != 2 || !contains(f.names(), "get_game") {
		t.Fatalf("%v %d", err, n)
	}
}

func timed(t *testing.T, ms int) map[string]any {
	return map[string]any{"clocks": map[string]any{"you_ms": ms + 1000, "opponent_ms": 9000, "running": "you", "your_turn_time_left_ms": ms}}
}

func TestSlowStrategyHitsClockFallback(t *testing.T) {
	e := newEnv(t)
	f := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {waitResult(t, "your_turn", "get_game_start", timed(t, 1500)), e.over}, "make_move": {result(t, "move_ok")}}))
	t0 := time.Now()
	_, err := f.client().Play(bg, func(ctx context.Context, p *Position) (Move, error) {
		select {
		case <-time.After(5 * time.Second):
		case <-ctx.Done():
		}
		return UCI("e4"), nil
	}, WithSafetyMargin(time.Second), WithFallback(func(*Position) Move { return UCI("d2d4") }))
	if err != nil || time.Since(t0) > 3*time.Second || f.callsOf("make_move")[0].Args["move"] != "d2d4" {
		t.Fatalf("%v %v", err, time.Since(t0))
	}
}

func TestNoTimeLeftSkipsStrategy(t *testing.T) {
	e := newEnv(t)
	f := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {waitResult(t, "your_turn", "get_game_start", timed(t, 400)), e.over}, "make_move": {result(t, "move_ok")}}))
	var called atomic.Bool
	_, err := f.client().Play(bg, func(ctx context.Context, p *Position) (Move, error) { called.Store(true); return UCI("e4"), nil },
		WithFallback(func(*Position) Move { return UCI("c2c4") }))
	if err != nil || called.Load() || f.callsOf("make_move")[0].Args["move"] != "c2c4" {
		t.Fatalf("%v", err)
	}
}

func TestResign(t *testing.T) {
	e := newEnv(t)
	f := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {e.your}, "resign": {result(t, "resign")}}))
	final, err := f.client().Play(bg, func(ctx context.Context, p *Position) (Move, error) { return Move{}, ErrResign })
	if err != nil || !final.IsOver() || !contains(f.names(), "resign") {
		t.Fatalf("%v", err)
	}
}

func TestDrawOffer(t *testing.T) {
	e := newEnv(t)
	offer := result(t, "wait_draw_offer")
	f := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {offer, e.over}, "respond_draw": {result(t, "respond_draw_decline")}}))
	if _, err := f.client().Play(bg, fixed("e4")); err != nil {
		t.Fatal(err)
	}
	if f.callsOf("respond_draw")[0].Args["accept"] != false {
		t.Fatal("default must decline")
	}
	f2 := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {offer, e.over}, "respond_draw": {result(t, "respond_draw_decline")}}))
	_, _ = f2.client().Play(bg, fixed("e4"), WithOnDrawOffer(func(*Position) bool { return true }))
	if f2.callsOf("respond_draw")[0].Args["accept"] != true {
		t.Fatal("accept")
	}
}

func TestEmptyMoveIsExplained(t *testing.T) {
	e := newEnv(t)
	f := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {e.your}}))
	_, err := f.client().Play(bg, func(ctx context.Context, p *Position) (Move, error) { return Move{}, nil })
	if err == nil || !strings.Contains(err.Error(), "empty move") {
		t.Fatalf("%v", err)
	}
}

func TestCancelWhileQueuedLeavesQueue(t *testing.T) {
	e := newEnv(t)
	f := newFake(t, e.base(t, map[string][]any{"wait_for_turn": {result(t, "wait_timeout")}, "leave_queue": {result(t, "leave_queue")}}))
	ctx, cancel := context.WithTimeout(bg, 300*time.Millisecond)
	defer cancel()
	_, err := f.client().Play(ctx, fixed("e4"))
	if err == nil || !contains(f.names(), "leave_queue") {
		t.Fatalf("%v %v", err, f.names())
	}
}
