package neuromate

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func one(t *testing.T, tool string, rep any) *Client {
	return newFake(t, map[string][]any{tool: {rep}}).client()
}

func TestPositionFromRealReply(t *testing.T) {
	pos, err := one(t, "get_game", result(t, "get_game_start")).GetGame(bg, "")
	if err != nil {
		t.Fatal(err)
	}
	if pos.Status != "created" || pos.Variant != "standard" || pos.Ply != 0 || !pos.YourTurn || pos.Color() != "white" {
		t.Fatalf("%+v", pos)
	}
	if !strings.HasPrefix(pos.FEN, "rnbqkbnr/pppppppp") || len(pos.LegalMoves) != 20 || pos.Clock.YouMS != 180000 {
		t.Fatalf("fen/legal/clock: %+v", pos)
	}
	found := false
	for _, u := range pos.LegalUCI() {
		found = found || u == "e2e4"
	}
	if !found || !strings.Contains(pos.BoardASCII, "a b c d e f g h") || !strings.Contains(pos.Text, "YOUR TURN") {
		t.Fatal("legal/board/text")
	}
	if pos.Opponent.Name == pos.You.Name || !strings.HasPrefix(pos.Opponent.ModelLabel, "Dev Model") || pos.IsOver() {
		t.Fatalf("players %+v %+v", pos.You, pos.Opponent)
	}
	if _, ok := pos.Clock.SecondsForThisMove(); !ok {
		t.Fatal("first move budget missing")
	}
	if _, ok := pos.Deadline(); !ok {
		t.Fatal("deadline")
	}
}

func TestMoveResultAndReplay(t *testing.T) {
	ok, err := one(t, "make_move", result(t, "move_ok")).MakeMove(bg, "e4", MoveOptions{ClientMoveID: "w1"})
	if err != nil || ok.Move == nil || ok.Move.UCI != "e2e4" || ok.Move.Note != "centre" || ok.Replayed || ok.Position.YourTurn {
		t.Fatalf("%+v %v", ok, err)
	}
	again, _ := one(t, "make_move", result(t, "move_replayed")).MakeMove(bg, "e4", MoveOptions{ClientMoveID: "w1"})
	if !again.Replayed || again.Move.SAN != "e4" {
		t.Fatalf("%+v", again)
	}
}

func TestWaitVariants(t *testing.T) {
	w, _ := one(t, "wait_for_turn", result(t, "wait_timeout")).WaitForTurn(bg, "g", 1)
	if !w.TimedOut() || w.Position != nil {
		t.Fatalf("%+v", w)
	}
	w, _ = one(t, "wait_for_turn", result(t, "wait_your_turn")).WaitForTurn(bg, "", 0)
	if w.Reason != "your_turn" || w.Position == nil || len(w.Position.LegalMoves) == 0 {
		t.Fatalf("%+v", w)
	}
	w, _ = one(t, "wait_for_turn", result(t, "wait_draw_offer")).WaitForTurn(bg, "", 0)
	if w.Reason != "draw_offer" || w.Position.DrawOffer != "from_opponent" {
		t.Fatalf("%+v", w)
	}
	w, _ = one(t, "wait_for_turn", result(t, "wait_game_started")).WaitForTurn(bg, "", 0)
	if len(w.Events) == 0 || w.Events[len(w.Events)-1].Type != "game_started" || w.Events[len(w.Events)-1].Data["game_id"] == "" {
		t.Fatalf("%+v", w.Events)
	}
}

func TestLobbyQueueSeek(t *testing.T) {
	l, _ := one(t, "start_here", result(t, "start_here_idle")).StartHere(bg)
	if len(l.ActiveGames) != 0 || !strings.Contains(l.Next, "join_queue") || l.You.Name != "Alice" || len(l.Tools) < 10 {
		t.Fatalf("%+v", l)
	}
	q, _ := one(t, "join_queue", result(t, "join_queue_waiting")).JoinQueue(bg, QueueOptions{})
	if !q.Queued || q.Position != nil {
		t.Fatalf("%+v", q)
	}
	q, _ = one(t, "join_queue", result(t, "join_queue_matched")).JoinQueue(bg, QueueOptions{})
	if q.Queued || q.Position == nil {
		t.Fatalf("%+v", q)
	}
	busy, _ := one(t, "list_challenges", result(t, "list_challenges_busy")).ListChallenges(bg)
	if len(busy.IncomingChallenges) == 0 || busy.IncomingChallenges[0].From.Name != "Bob" || len(busy.MySeeks) == 0 {
		t.Fatalf("%+v", busy)
	}
	s, _ := one(t, "create_seek", result(t, "create_seek")).CreateSeek(bg, SeekOptions{})
	if !strings.HasPrefix(s.ID, "sk_") || s.Kind != "seek" {
		t.Fatalf("%+v", s)
	}
	ch, _ := one(t, "challenge", result(t, "challenge")).Challenge(bg, "carol", ChallengeOptions{})
	if !strings.HasPrefix(ch.ID, "ch_") {
		t.Fatalf("%+v", ch)
	}
}

func TestHandbookAnalysisFinished(t *testing.T) {
	hb, _ := one(t, "get_handbook", result(t, "handbook_index")).GetHandbook(bg, "")
	if !contains(hb.Sections, "quickstart") {
		t.Fatalf("%v", hb.Sections)
	}
	a, _ := one(t, "get_analysis", result(t, "analysis_unavailable")).GetAnalysis(bg, "g")
	if a.Text == "" || a.Available() || a.Status() == "done" {
		t.Fatalf("%+v", a)
	}
	pos, _ := one(t, "resign", result(t, "resign")).Resign(bg, "")
	if !pos.IsOver() || pos.Result == nil || pos.Result.Termination != "resignation" || pos.Result.Winner == "" {
		t.Fatalf("%+v", pos.Result)
	}
}

func contains(l []string, s string) bool {
	for _, x := range l {
		if x == s {
			return true
		}
	}
	return false
}

func TestTypedToolErrors(t *testing.T) {
	_, err := one(t, "make_move", result(t, "err_illegal_move")).MakeMove(bg, "e2e5", MoveOptions{})
	var ill *IllegalMoveError
	var te *ToolError
	if !errors.As(err, &ill) || !errors.As(err, &te) {
		t.Fatalf("%T %v", err, err)
	}
	if !strings.Contains(err.Error(), "pawn on e2 cannot move to e5") || ill.Code != "illegal_move" || ill.Hint == "" ||
		ill.Position == nil || !ill.Position.YourTurn || !contains(ill.LegalMoves, "e2e4") && !contains(ill.Position.LegalUCI(), "e2e4") {
		t.Fatalf("%+v", ill.ToolError)
	}
	if IsRetryable(err) {
		t.Fatal("illegal move must not be retryable")
	}
	// the remaining illegal-move budget is in the error context
	if ill.Context["illegal_attempts_remaining"] != float64(24) || ill.Context["illegal_attempts_budget"] != float64(25) || !strings.Contains(ill.Hint, "24 remaining") {
		t.Fatalf("budget context: %v / %s", ill.Context, ill.Hint)
	}
	_, err = one(t, "make_move", result(t, "err_not_your_turn")).MakeMove(bg, "e5", MoveOptions{})
	var nyt *NotYourTurnError
	if !errors.As(err, &nyt) || !strings.Contains(nyt.Hint, "wait_for_turn") {
		t.Fatalf("%v", err)
	}
	_, err = one(t, "make_move", result(t, "err_stale_ply")).MakeMove(bg, "e4", MoveOptions{})
	var st *StalePlyError
	if !errors.As(err, &st) {
		t.Fatalf("%v", err)
	}
	_, err = one(t, "make_move", result(t, "err_game_over")).MakeMove(bg, "Nf3", MoveOptions{})
	var go_ *GameOverError
	if !errors.As(err, &go_) || go_.Position == nil || !go_.Position.IsOver() {
		t.Fatalf("%v", err)
	}
	_, err = one(t, "make_move", result(t, "err_unknown_param")).MakeMove(bg, "e4", MoveOptions{})
	var ip *InvalidParamsError
	if !errors.As(err, &te) || !errors.As(err, &ip) || te.Code != "invalid_params" || !strings.Contains(te.Hint, "allowed parameters") {
		t.Fatalf("%v", err)
	}
	var generic *IllegalMoveError
	if errors.As(err, &generic) {
		t.Fatal("parameter error must not be an IllegalMoveError")
	}
	_, err = one(t, "challenge", result(t, "err_unknown_player")).Challenge(bg, "nobody", ChallengeOptions{})
	if !errors.As(err, &te) || te.Code != "unknown_player" || te.Hint == "" {
		t.Fatalf("%v", err)
	}
	_, err = one(t, "get_game", result(t, "err_no_game")).GetGame(bg, "")
	var ng *NoGameError
	if !errors.As(err, &ng) || !strings.Contains(ng.Hint, "join_queue") {
		t.Fatalf("%v", err)
	}
	// no game_id after the game ended: the server resolves to the most recent game
	pos, err := one(t, "get_game", result(t, "get_game_default_finished")).GetGame(bg, "")
	if err != nil || !pos.IsOver() || !strings.Contains(pos.Text, "most recent game") {
		t.Fatalf("%v %+v", err, pos)
	}
	_, err = one(t, "make_move", result(t, "err_game_over_no_id")).MakeMove(bg, "Nf3", MoveOptions{})
	if !errors.As(err, &go_) || go_.Context["game_id"] == nil || go_.Hint == "" {
		t.Fatalf("%v", err)
	}
}

func TestProtocolHTTPAndLimits(t *testing.T) {
	rpcErr := fixture(t, "rpc_unknown_tool")["response"].(map[string]any)["error"].(map[string]any)
	_, err := one(t, "nope", rpcErrReply{rpcErr}).CallTool(bg, "nope", nil)
	var pe *ProtocolError
	if !errors.As(err, &pe) || pe.Code != -32602 || !strings.Contains(pe.Message, "Did you mean make_move") {
		t.Fatalf("%v", err)
	}
	if pe.ErrorCode() != "unknown_tool" || pe.Hint() == "" {
		t.Fatalf("rpc error data: %+v", pe)
	}
	_, err = one(t, "whoami", httpReply{401, "missing or invalid agent API key"}).Whoami(bg)
	var ae *AuthError
	if !errors.As(err, &ae) || ae.Status != 401 || IsRetryable(err) || !strings.Contains(err.Error(), "missing or invalid") {
		t.Fatalf("%v", err)
	}
	body, _ := json.Marshal(fixture(t, "http_401")["response"])
	_, err = one(t, "whoami", httpReply{401, string(body)}).Whoami(bg)
	if !errors.As(err, &ae) || ae.Code != "unauthorized" || !strings.Contains(ae.Hint, "Authorization: Bearer") || !strings.Contains(err.Error(), "missing or invalid agent API key") {
		t.Fatalf("%v %+v", err, ae)
	}
	structured := map[string]any{"content": []any{map[string]any{"type": "text", "text": "ERROR (rate_limited): too many requests\n"}}, "isError": true,
		"structuredContent": map[string]any{"error": map[string]any{"code": "rate_limited", "message": "too many requests", "hint": "wait", "retry_after_seconds": 4}}}
	var re2 *RateLimitedError
	if _, err = one(t, "whoami", structured).Whoami(bg); !errors.As(err, &re2) || re2.RetryAfter != 4*time.Second {
		t.Fatalf("%v", err)
	}
	coded := map[string]any{"content": []any{map[string]any{"type": "text", "text": "ERROR (internal): something went wrong"}}, "isError": true,
		"structuredContent": map[string]any{"error": map[string]any{"code": "internal", "message": "something went wrong on our side", "hint": "retry"}}}
	var se2 *ServerError
	if _, err = one(t, "whoami", coded).Whoami(bg); !errors.As(err, &se2) {
		t.Fatalf("%v", err)
	}
	rl := map[string]any{"content": []any{map[string]any{"type": "text", "text": "RATE LIMITED: too many requests. Wait 7 seconds, then retry."}}, "isError": true}
	_, err = one(t, "whoami", rl).Whoami(bg)
	var re *RateLimitedError
	if !errors.As(err, &re) || re.RetryAfter != 7*time.Second || !IsRetryable(err) {
		t.Fatalf("%v", err)
	}
	_, err = one(t, "whoami", httpReply{429, "slow"}).Whoami(bg)
	if !errors.As(err, &re) || re.RetryAfter < time.Second {
		t.Fatalf("%v", err)
	}
	internal := map[string]any{"content": []any{map[string]any{"type": "text", "text": "INTERNAL ERROR: something went wrong on our side"}}, "isError": true,
		"structuredContent": map[string]any{"error": map[string]any{"message": "internal error"}}}
	var se *ServerError
	if _, err = one(t, "whoami", internal).Whoami(bg); !errors.As(err, &se) || !IsRetryable(err) {
		t.Fatalf("%v", err)
	}
	if _, err = one(t, "whoami", httpReply{503, "down"}).Whoami(bg); !errors.As(err, &se) {
		t.Fatalf("%v", err)
	}
	f := newFake(t, nil)
	f.srv.Close()
	_, err = f.client().Whoami(bg)
	var tr *TransportError
	if !errors.As(err, &tr) || !IsRetryable(err) {
		t.Fatalf("%v", err)
	}
}

func TestRequestsAreWellFormed(t *testing.T) {
	f := newFake(t, map[string][]any{"make_move": {result(t, "move_ok")}, "join_queue": {result(t, "join_queue_waiting")}})
	c := f.client()
	ply := 0
	_, _ = c.MakeMove(bg, "e4", MoveOptions{GameID: "g1", Note: "n", ExpectedPly: &ply})
	_, _ = c.JoinQueue(bg, QueueOptions{GameOptions: GameOptions{Variant: "chess960", TimeControl: "3+2", Rated: true, Extra: map[string]any{"increment_seconds": 1}}, Pool: "agents"})
	mv := f.callsOf("make_move")[0].Args
	if mv["game_id"] != "g1" || mv["note"] != "n" || mv["expected_ply"] != float64(0) || len(mv["client_move_id"].(string)) < 8 {
		t.Fatalf("%v", mv)
	}
	jq := f.callsOf("join_queue")[0].Args
	if len(jq) != 5 || jq["variant"] != "chess960" || jq["pool"] != "agents" || jq["rated"] != true || jq["increment_seconds"] != float64(1) {
		t.Fatalf("%v", jq)
	}
}

func TestAPIKeyFromEnvAndSSE(t *testing.T) {
	t.Setenv("NEUROMATE_API_KEY", "")
	if _, err := NewClient(""); err == nil || !strings.Contains(err.Error(), "NEUROMATE_API_KEY") {
		t.Fatalf("%v", err)
	}
	t.Setenv("NEUROMATE_API_KEY", "nm_live_abc")
	t.Setenv("NEUROMATE_URL", "http://x/mcp")
	c, err := NewClient("")
	if err != nil || c.apiKey != "nm_live_abc" || c.baseURL != "http://x/mcp" || strings.Contains(c.String(), "nm_live_abc") {
		t.Fatalf("%v %v", c, err)
	}
	// An SSE-framed reply and a session id are understood.
	var sawSession string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawSession = r.Header.Get("Mcp-Session-Id")
		body := make([]byte, 4096)
		n, _ := r.Body.Read(body)
		if !strings.Contains(string(body[:n]), `"id"`) {
			w.WriteHeader(202)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Mcp-Session-Id", "sess-1")
		_, _ = w.Write([]byte("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"serverInfo\":{\"name\":\"x\"},\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"structuredContent\":{\"sections\":[\"a\"]}}}\n\n"))
	}))
	defer srv.Close()
	c2, _ := NewClient("k", WithBaseURL(srv.URL))
	hb, err := c2.GetHandbook(bg, "")
	if err != nil || hb.Text != "hi" || len(hb.Sections) != 1 {
		t.Fatalf("%+v %v", hb, err)
	}
	if _, err := c2.GetHandbook(bg, ""); err != nil || sawSession != "sess-1" {
		t.Fatalf("session %q %v", sawSession, err)
	}
}
