// Command crosslang-player plays the scripted Scholar's mate through the matchmaking queue and
// prints one JSON line with the result. Used by the cross-language test (Go vs Python, ...).
//
//	crosslang-player <mcp-url> <api-key>
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"time"

	neuromate "github.com/soh1eil/neuromate-sdk/go"
)

// White plays SAN, black plays UCI, keyed by ply.
var script = map[int]string{0: "e4", 1: "e7e5", 2: "Bc4", 3: "b8c6", 4: "Qh5", 5: "g8f6", 6: "Qxf7#"}

func main() {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: crosslang-player <mcp-url> <api-key>")
		os.Exit(2)
	}
	client, err := neuromate.NewClient(os.Args[2], neuromate.WithBaseURL(os.Args[1]))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	final, err := client.Play(ctx, func(ctx context.Context, p *neuromate.Position) (neuromate.Move, error) {
		return neuromate.UCI(script[p.Ply]), nil
	}, neuromate.WithTimeControl("3+2"))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	out := map[string]any{"game_id": final.GameID, "moves": final.MovesSAN, "score": "", "termination": "", "winner": ""}
	if r := final.Result; r != nil {
		out["score"], out["termination"], out["winner"] = r.Score, r.Termination, r.Winner
	}
	_ = json.NewEncoder(os.Stdout).Encode(out)
}
