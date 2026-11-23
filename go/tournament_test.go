package neuromate

import (
	"context"
	"errors"
	"strings"
	"testing"
)

const tid = "t_k3m9x2qa"

var tref = map[string]any{"id": tid, "name": "Friday Blitz Swiss", "round": float64(1)}

func standingsReply(t *testing.T, status string, joined bool) map[string]any {
	r := clone(result(t, "tournament_standings"))
	tr := r["structuredContent"].(map[string]any)["tournament"].(map[string]any)
	tr["status"] = status
	if !joined {
		delete(tr, "me")
	}
	return r
}

func tgame(t *testing.T, reason, gid string) map[string]any {
	return waitResult(t, reason, "get_game_start", map[string]any{"game_id": gid, "tournament": tref})
}

func tover(t *testing.T, gid string) map[string]any {
	r := waitResult(t, "game_over", "resign", map[string]any{"game_id": gid, "tournament": tref})
	r["structuredContent"].(map[string]any)["events"] = []any{map[string]any{"seq": 5, "type": "tournament_game_over", "data": map[string]any{"tournament_id": tid}, "at": ""}}
	return r
}

func tbase(t *testing.T, more map[string][]any) map[string][]any {
	s := map[string][]any{"whoami": {result(t, "whoami_idle")}}
	for k, v := range more {
		s[k] = v
	}
	return s
}

func TestTournamentToolsParse(t *testing.T) {
	f := newFake(t, map[string][]any{
		"list_tournaments": {result(t, "tournaments_list")}, "join_tournament": {result(t, "tournament_join")},
		"withdraw_tournament": {result(t, "tournament_withdraw")}, "tournament_standings": {result(t, "tournament_standings")},
	})
	c := f.client()
	ts, err := c.ListTournaments(bg, TournamentFilter{Status: "registering,running", Kind: "swiss"})
	if err != nil || len(ts) != 2 || ts[0].ID != tid || ts[0].Me == nil || ts[0].Me.Rank != 3 || ts[1].Kind != "arena" || !ts[0].Joined() || ts[0].IsOver() {
		t.Fatalf("%+v %v", ts, err)
	}
	if ts[0].TimeControl["initial_ms"] != float64(180000) || ts[0].EndsAt != "" || ts[0].Winner != nil {
		t.Fatalf("%+v", ts[0])
	}
	if a := f.callsOf("list_tournaments")[0].Args; a["status"] != "registering,running" || a["kind"] != "swiss" || len(a) != 2 {
		t.Fatal(a)
	}
	j, err := c.JoinTournament(bg, tid)
	if err != nil || !j.Joined || j.Tournament.Status != "registering" || !strings.Contains(j.Next, "wait_for_turn") {
		t.Fatalf("%+v %v", j, err)
	}
	w, err := c.WithdrawTournament(bg, tid)
	if err != nil || !w.Withdrawn || w.Tournament.Joined() {
		t.Fatalf("%+v %v", w, err)
	}
	s, err := c.TournamentStandings(bg, tid, 2)
	if err != nil || s.Total != 4 || len(s.Standings) != 4 || s.Standings[0].Tiebreaks["buchholz"] != 3.5 {
		t.Fatalf("%+v %v", s, err)
	}
	if a := f.callsOf("tournament_standings")[0].Args; a["round"] != float64(2) || a["id"] != tid {
		t.Fatal(a)
	}
	if s.Pairings[0].White.Name != "Alice" || s.Pairings[0].IsBye() || !s.Pairings[1].IsBye() || s.Pairings[1].Black != nil || s.Pairings[1].Points != [2]float64{1, 0} {
		t.Fatalf("%+v", s.Pairings)
	}
	if s.Find("dev-agent-dave", "").Rank != 4 || s.Find("", "Bob") == nil || s.Find("x", "y") != nil {
		t.Fatal("find")
	}
}

func TestTournamentConflictAndGameRef(t *testing.T) {
	f := newFake(t, map[string][]any{"join_tournament": {result(t, "err_tournament_conflict")}, "wait_for_turn": {tgame(t, "your_turn", "g1")}})
	c := f.client()
	var ce *ConflictError
	if _, err := c.JoinTournament(bg, tid); !errors.As(err, &ce) {
		t.Fatal(err)
	}
	turn, err := c.WaitForTurn(bg, "", 1)
	if err != nil || turn.Position.Tournament == nil || *turn.Position.Tournament != (TournamentRef{tid, "Friday Blitz Swiss", 1}) {
		t.Fatalf("%+v %v", turn, err)
	}
}

func TestPlayTournamentTwoRoundsByeAndFinish(t *testing.T) {
	f := newFake(t, tbase(t, map[string][]any{
		"tournament_standings": {standingsReply(t, "running", false), standingsReply(t, "running", true)},
		"join_tournament":      {result(t, "tournament_join")},
		"wait_for_turn": {result(t, "wait_tournament_started"), tgame(t, "your_turn", "g1"), tover(t, "g1"), result(t, "wait_tournament_bye"),
			tgame(t, "your_turn", "g2"), tover(t, "g2"), result(t, "wait_tournament_finished")},
		"make_move": {result(t, "move_ok")},
	}))
	var seen, events []string
	row, err := f.client().PlayTournament(bg, tid, func(ctx context.Context, p *Position) (Move, error) {
		seen = append(seen, p.GameID)
		return UCI("e4"), nil
	}, WithOnEvent(func(e Event) { events = append(events, e.Type) }))
	if err != nil || row == nil || row.Rank != 3 || row.Score != 1.5 || row.Name != "Alice" {
		t.Fatalf("%+v %v", row, err)
	}
	if strings.Join(seen, ",") != "g1,g2" {
		t.Fatal(seen)
	}
	W, M, S := "wait_for_turn", "make_move", "tournament_standings"
	want := strings.Join([]string{S, "join_tournament", W, W, M, W, S, W, W, M, W, S, W, S, "whoami"}, ",")
	if got := strings.Join(f.names(), ","); got != want {
		t.Fatalf("%s\n%s", got, want)
	}
	if got := strings.Join(events, ","); got != "tournament_started,tournament_game_over,tournament_round,tournament_game_over,tournament_finished" {
		t.Fatal(got)
	}
}

func TestPlayTournamentAlreadyJoinedAndTimeouts(t *testing.T) {
	f := newFake(t, tbase(t, map[string][]any{
		"tournament_standings": {standingsReply(t, "running", true)},
		"wait_for_turn":        {result(t, "wait_timeout"), result(t, "wait_timeout"), result(t, "wait_tournament_finished")},
	}))
	row, err := f.client().PlayTournament(bg, tid, fixed("e4"))
	if err != nil || row == nil || row.Rank != 3 || contains(f.names(), "join_tournament") || len(f.callsOf("wait_for_turn")) != 3 {
		t.Fatalf("%+v %v %v", row, err, f.names())
	}
}

func TestPlayTournamentJoinConflictToleratedRefusalRaised(t *testing.T) {
	f := newFake(t, tbase(t, map[string][]any{
		"tournament_standings": {standingsReply(t, "running", false), standingsReply(t, "running", true)},
		"join_tournament":      {result(t, "err_tournament_conflict")}, "wait_for_turn": {result(t, "wait_tournament_finished")},
	}))
	if row, err := f.client().PlayTournament(bg, tid, fixed("e4")); err != nil || row == nil {
		t.Fatal(row, err)
	}
	refused := map[string]any{"content": []any{map[string]any{"type": "text", "text": "ERROR (not_allowed): wrong pool"}}, "isError": true,
		"structuredContent": map[string]any{"error": map[string]any{"code": "not_allowed", "message": "wrong pool", "hint": "pick another"}}}
	g := newFake(t, tbase(t, map[string][]any{"tournament_standings": {standingsReply(t, "registering", false)}, "join_tournament": {refused}}))
	var na *NotAllowedError
	if _, err := g.client().PlayTournament(bg, tid, fixed("e4")); !errors.As(err, &na) {
		t.Fatal(err)
	}
}

func TestPlayTournamentCancelledWhileWaiting(t *testing.T) {
	f := newFake(t, tbase(t, map[string][]any{
		"tournament_standings": {standingsReply(t, "registering", true), standingsReply(t, "cancelled", true)},
		"wait_for_turn":        {result(t, "wait_timeout")},
	}))
	row, err := f.client().PlayTournament(bg, tid, fixed("e4"))
	if err != nil || row == nil || row.Rank != 3 || len(f.callsOf("wait_for_turn")) != 1 {
		t.Fatalf("%+v %v", row, err)
	}
}

func TestPlayTournamentCancelledMidGame(t *testing.T) {
	aborted := tover(t, "g1")
	aborted["structuredContent"].(map[string]any)["game"].(map[string]any)["status"] = "aborted"
	f := newFake(t, tbase(t, map[string][]any{
		"tournament_standings": {standingsReply(t, "running", true), standingsReply(t, "cancelled", true)},
		"wait_for_turn":        {tgame(t, "your_turn", "g1"), aborted}, "make_move": {result(t, "move_ok")},
	}))
	row, err := f.client().PlayTournament(bg, tid, fixed("e4"))
	if err != nil || row == nil || len(f.callsOf("wait_for_turn")) != 2 {
		t.Fatalf("%+v %v", row, err)
	}
}

func TestPlayTournamentAlreadyFinishedAndForeignEvents(t *testing.T) {
	f := newFake(t, tbase(t, map[string][]any{"tournament_standings": {standingsReply(t, "finished", true)}}))
	if row, err := f.client().PlayTournament(bg, tid, fixed("e4")); err != nil || row == nil || row.Rank != 3 || contains(f.names(), "wait_for_turn") {
		t.Fatal(row, err, f.names())
	}
	other := clone(result(t, "wait_tournament_finished"))
	other["structuredContent"].(map[string]any)["events"].([]any)[0].(map[string]any)["data"].(map[string]any)["tournament_id"] = "t_other"
	g := newFake(t, tbase(t, map[string][]any{"tournament_standings": {standingsReply(t, "running", true)}, "wait_for_turn": {other, result(t, "wait_tournament_finished")}}))
	if _, err := g.client().PlayTournament(bg, tid, fixed("e4")); err != nil || len(g.callsOf("wait_for_turn")) != 2 {
		t.Fatal(err, g.names())
	}
}

func TestPlayTournamentContextCancelStops(t *testing.T) {
	f := newFake(t, tbase(t, map[string][]any{"tournament_standings": {standingsReply(t, "running", true)}, "wait_for_turn": {result(t, "wait_timeout")}}))
	ctx, cancel := context.WithCancel(bg)
	cancel()
	if _, err := f.client().PlayTournament(ctx, tid, fixed("e4")); err == nil {
		t.Fatal("expected an error")
	}
}

func TestEventFlatFieldsAreAccepted(t *testing.T) {
	var e Event
	if err := e.UnmarshalJSON([]byte(`{"seq":3,"type":"tournament_round","tournament_id":"t1","round":2,"bye":true}`)); err != nil {
		t.Fatal(err)
	}
	if e.Seq != 3 || e.Data["tournament_id"] != "t1" || e.Data["bye"] != true {
		t.Fatalf("%+v", e)
	}
}
