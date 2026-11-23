package neuromate_test

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	neuromate "github.com/soh1eil/neuromate-sdk/go"
)

var (
	devURL  string
	devKeys []string
	devSkip string
)

// TestMain builds and starts the real Go MCP dev server once for all integration tests.
func TestMain(m *testing.M) {
	dir, _ := os.MkdirTemp("", "nm-sdk-go-")
	bin := os.Getenv("NM_DEVSERVER_BIN")
	if bin == "" {
		bin = filepath.Join(dir, "mcp-devserver")
		build := exec.Command("go", "build", "-o", bin, "./cmd/mcp-devserver")
		build.Dir = filepath.Join("..", "..", "server")
		if out, err := build.CombinedOutput(); err != nil {
			devSkip = fmt.Sprintf("cannot build mcp-devserver (set NM_DEVSERVER_BIN): %v\n%s", err, out)
		}
	}
	var proc *exec.Cmd
	if devSkip == "" {
		proc = exec.Command(bin)
		proc.Env = append(os.Environ(), "NM_DEV=1")
		stdout, _ := proc.StdoutPipe()
		if err := proc.Start(); err != nil {
			devSkip = err.Error()
		} else {
			line, _ := bufio.NewReader(stdout).ReadString('\n')
			if mm := regexp.MustCompile(`url=(\S+) keys=(\S+)`).FindStringSubmatch(line); mm != nil {
				devURL, devKeys = mm[1], strings.Split(mm[2], ",")
			} else {
				devSkip = "unexpected devserver output: " + line
			}
		}
	}
	code := m.Run()
	if proc != nil && proc.Process != nil {
		_ = proc.Process.Kill()
		_, _ = proc.Process.Wait()
	}
	_ = os.RemoveAll(dir)
	os.Exit(code)
}

func integ(t *testing.T) {
	t.Helper()
	if devSkip != "" {
		t.Skip(devSkip)
	}
}

func newClient(t *testing.T, key string) *neuromate.Client {
	c, err := neuromate.NewClient(key, neuromate.WithBaseURL(devURL))
	if err != nil {
		t.Fatal(err)
	}
	return c
}

// Scholar's mate, keyed by ply: white plays SAN, black plays UCI.
var script = map[int]string{0: "e4", 1: "e7e5", 2: "Bc4", 3: "b8c6", 4: "Qh5", 5: "g8f6", 6: "Qxf7#"}

func scripted(ctx context.Context, p *neuromate.Position) (neuromate.Move, error) {
	return neuromate.UCI(script[p.Ply]), nil
}

func playPair(t *testing.T, a, b *neuromate.Client, games int, opts ...neuromate.PlayOption) [][]*neuromate.Position {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	out := make([][]*neuromate.Position, 2)
	var wg sync.WaitGroup
	for i, c := range []*neuromate.Client{a, b} {
		i, c := i, c
		wg.Add(1)
		go func() {
			defer wg.Done()
			for g := 0; g < games; g++ {
				p, err := c.Play(ctx, scripted, opts...)
				if err != nil {
					t.Errorf("player %d game %d: %v", i, g, err)
					return
				}
				out[i] = append(out[i], p)
			}
		}()
	}
	wg.Wait()
	return out
}

func TestTwoGoClientsPlayScholarsMate(t *testing.T) {
	integ(t)
	res := playPair(t, newClient(t, devKeys[0]), newClient(t, devKeys[1]), 1, neuromate.WithTimeControl("3+2"))
	for _, ps := range res {
		if len(ps) != 1 {
			t.Fatal("missing game")
		}
		p := ps[0]
		if !p.IsOver() || p.Result == nil || p.Result.Termination != "checkmate" || p.Result.Winner != "white" ||
			strings.Join(p.MovesSAN, " ") != "e4 e5 Bc4 Nc6 Qh5 Nf6 Qxf7#" {
			t.Fatalf("%+v %v", p.Result, p.MovesSAN)
		}
	}
}

func TestTwoGamesInARowIgnoreLeftoverEvents(t *testing.T) {
	integ(t)
	res := playPair(t, newClient(t, devKeys[0]), newClient(t, devKeys[1]), 2, neuromate.WithTimeControl("untimed"))
	ids := map[string]bool{}
	for _, ps := range res {
		for _, p := range ps {
			ids[p.GameID] = true
			if p.Result == nil || p.Result.Termination != "checkmate" {
				t.Fatalf("%+v", p.Result)
			}
		}
	}
	if len(ids) != 2 {
		t.Fatalf("expected 2 distinct games, got %v", ids)
	}
}

func TestErrorsAndManualCallsAgainstRealServer(t *testing.T) {
	integ(t)
	ctx := context.Background()
	a, b := newClient(t, devKeys[0]), newClient(t, devKeys[2]) // alice, carol
	lobby, err := a.StartHere(ctx)
	if err != nil || lobby.You.Name != "Alice" {
		t.Fatalf("%v %v", lobby, err)
	}
	if tools, err := a.ListTools(ctx); err != nil || tools[0]["name"] != "start_here" {
		t.Fatalf("%v %v", tools, err)
	}
	sk, err := a.CreateSeek(ctx, neuromate.SeekOptions{GameOptions: neuromate.GameOptions{TimeControl: "5+0", Color: "white"}})
	if err != nil {
		t.Fatal(err)
	}
	pos, err := b.AcceptChallenge(ctx, sk.ID)
	if err != nil || pos.Opponent.Name != "Alice" {
		t.Fatalf("%v %v", pos, err)
	}
	gid := pos.GameID
	turn, err := a.WaitForTurn(ctx, gid, 2)
	if err != nil || turn.Reason != "your_turn" || len(turn.Position.LegalMoves) != 20 {
		t.Fatalf("%v %v", turn, err)
	}
	_, err = a.MakeMove(ctx, "e2e5", neuromate.MoveOptions{GameID: gid})
	var ill *neuromate.IllegalMoveError
	if !errors.As(err, &ill) || !strings.Contains(err.Error(), "legal pawn moves from e2") {
		t.Fatalf("%v", err)
	}
	first, err := a.MakeMove(ctx, "e4", neuromate.MoveOptions{GameID: gid, ClientMoveID: "same"})
	if err != nil {
		t.Fatal(err)
	}
	again, err := a.MakeMove(ctx, "e4", neuromate.MoveOptions{GameID: gid, ClientMoveID: "same"})
	if err != nil || first.Replayed || !again.Replayed {
		t.Fatalf("%v %v", again, err)
	}
	if w, _ := b.WaitForTurn(ctx, gid, 1); w.Reason != "your_turn" {
		t.Fatalf("%v", w)
	}
	if w, _ := a.WaitForTurn(ctx, gid, 1); !w.TimedOut() {
		t.Fatalf("%v", w)
	}
	if _, err := b.MakeMove(ctx, "e5", neuromate.MoveOptions{GameID: gid}); err != nil {
		t.Fatal(err)
	}
	if _, err := a.OfferDraw(ctx, gid); err != nil {
		t.Fatal(err)
	}
	if w, _ := b.WaitForTurn(ctx, gid, 2); w.Reason != "draw_offer" {
		t.Fatalf("%v", w)
	}
	if _, err := b.RespondDraw(ctx, false, gid); err != nil {
		t.Fatal(err)
	}
	res, err := b.Resign(ctx, gid)
	if err != nil || !res.IsOver() || res.Result.Termination != "resignation" || res.Result.Winner != "white" {
		t.Fatalf("%v %v", res, err)
	}
	_, err = a.MakeMove(ctx, "Nf3", neuromate.MoveOptions{GameID: gid})
	var over *neuromate.GameOverError
	if !errors.As(err, &over) {
		t.Fatalf("%v", err)
	}
	if an, err := a.GetAnalysis(ctx, gid); err != nil || an.Text == "" {
		t.Fatalf("%v %v", an, err)
	}
	if hb, err := a.GetHandbook(ctx, ""); err != nil || len(hb.Sections) == 0 {
		t.Fatalf("%v %v", hb, err)
	}
	_, err = newClient(t, "nm_live_wrong").Whoami(ctx)
	var auth *neuromate.AuthError
	if !errors.As(err, &auth) {
		t.Fatalf("%v", err)
	}
}

func buildExample(t *testing.T, name string) string {
	t.Helper()
	bin := filepath.Join(t.TempDir(), name)
	cmd := exec.Command("go", "build", "-o", bin, "./examples/"+name)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("build %s: %v\n%s", name, err, out)
	}
	return bin
}

func runExample(bin, key string, extra ...string) *exec.Cmd {
	c := exec.Command(bin)
	c.Env = append(os.Environ(), "NEUROMATE_URL="+devURL, "NEUROMATE_API_KEY="+key)
	c.Env = append(c.Env, extra...)
	return c
}

func TestExamplesRandomMoverVsRandomMover(t *testing.T) {
	integ(t)
	bin := buildExample(t, "random_mover")
	cmds := []*exec.Cmd{runExample(bin, devKeys[0]), runExample(bin, devKeys[1])}
	outs := make([][]byte, 2)
	var wg sync.WaitGroup
	for i, c := range cmds {
		i, c := i, c
		wg.Add(1)
		go func() { defer wg.Done(); outs[i], _ = c.CombinedOutput() }()
	}
	wg.Wait()
	for i, c := range cmds {
		if c.ProcessState == nil || !c.ProcessState.Success() || len(strings.TrimSpace(string(outs[i]))) == 0 {
			t.Fatalf("example %d failed: %s", i, outs[i])
		}
	}
}

func TestExampleStockfishBeatsRandomMover(t *testing.T) {
	integ(t)
	sf := os.Getenv("STOCKFISH")
	if sf == "" {
		sf = "/data2/neuromate-build/engines/bin/stockfish"
	}
	if _, err := os.Stat(sf); err != nil {
		t.Skip("no stockfish (set STOCKFISH)")
	}
	a := runExample(buildExample(t, "stockfish_bot"), devKeys[0], "STOCKFISH="+sf)
	b := runExample(buildExample(t, "random_mover"), devKeys[1])
	var outA, outB []byte
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); outA, _ = a.CombinedOutput() }()
	go func() { defer wg.Done(); outB, _ = b.CombinedOutput() }()
	wg.Wait()
	if !a.ProcessState.Success() || !b.ProcessState.Success() || !strings.Contains(string(outA), "checkmate") {
		t.Fatalf("stockfish: %s\nrandom: %s", outA, outB)
	}
}

func TestCrosslangPlayerBinary(t *testing.T) {
	integ(t)
	bin := filepath.Join(t.TempDir(), "p")
	if out, err := exec.Command("go", "build", "-o", bin, "./cmd/crosslang-player").CombinedOutput(); err != nil {
		t.Fatalf("%v\n%s", err, out)
	}
	run := func(key string) (map[string]any, error) {
		out, err := exec.Command(bin, devURL, key).Output()
		var m map[string]any
		if err == nil {
			err = json.Unmarshal(out, &m)
		}
		return m, err
	}
	var wg sync.WaitGroup
	res := make([]map[string]any, 2)
	for i := range res {
		i := i
		wg.Add(1)
		go func() {
			defer wg.Done()
			m, err := run(devKeys[i])
			if err != nil {
				t.Errorf("player %d: %v", i, err)
			}
			res[i] = m
		}()
	}
	wg.Wait()
	for _, m := range res {
		if m["termination"] != "checkmate" || m["score"] != "1-0" || m["game_id"] == "" {
			t.Fatalf("%v", m)
		}
	}
}
