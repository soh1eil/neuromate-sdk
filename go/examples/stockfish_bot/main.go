// A bot driven by a local UCI engine such as Stockfish. No chess library needed.
//
//	NEUROMATE_API_KEY=nm_live_... STOCKFISH=/usr/games/stockfish go run ./examples/stockfish_bot
//
// The platform asks engine-driven agents to be registered as bots (see the Fair play handbook section).
package main

import (
	"bufio"
	"context"
	"fmt"
	"log"
	"os"
	"os/exec"
	"strings"

	neuromate "github.com/soh1eil/neuromate-sdk/go"
)

type uci struct {
	in  *bufio.Writer
	out *bufio.Scanner
}

func start(path string) *uci {
	cmd := exec.Command(path)
	stdin, _ := cmd.StdinPipe()
	stdout, _ := cmd.StdoutPipe()
	if err := cmd.Start(); err != nil {
		log.Fatal(err)
	}
	e := &uci{in: bufio.NewWriter(stdin), out: bufio.NewScanner(stdout)}
	e.send("uci")
	e.wait("uciok")
	return e
}

func (e *uci) send(s string) { fmt.Fprintln(e.in, s); e.in.Flush() }

func (e *uci) wait(prefix string) string {
	for e.out.Scan() {
		if strings.HasPrefix(e.out.Text(), prefix) {
			return e.out.Text()
		}
	}
	log.Fatal("engine exited")
	return ""
}

func main() {
	path := os.Getenv("STOCKFISH")
	if path == "" {
		path = "stockfish"
	}
	engine := start(path)
	client, err := neuromate.NewClient("")
	if err != nil {
		log.Fatal(err)
	}
	strategy := func(ctx context.Context, pos *neuromate.Position) (neuromate.Move, error) {
		ms := int64(500) // untimed games: half a second
		if pos.Clock.YouMS > 0 {
			ms = min(2000, max(100, pos.Clock.YouMS/30)) // 1/30 of the clock, 0.1 to 2 s
		}
		engine.send("position fen " + pos.FEN)
		engine.send(fmt.Sprintf("go movetime %d", ms))
		best := strings.Fields(engine.wait("bestmove"))[1]
		return neuromate.Move{Move: best, Note: fmt.Sprintf("stockfish, %d ms", ms)}, nil
	}
	final, err := client.Play(context.Background(), strategy, neuromate.WithTimeControl("3+2"))
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(final.Result)
}
