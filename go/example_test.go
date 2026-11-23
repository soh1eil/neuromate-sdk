package neuromate_test

import (
	"context"
	"errors"
	"fmt"
	"math/rand"

	neuromate "github.com/soh1eil/neuromate-sdk/go"
)

func ExampleClient_Play() {
	client, _ := neuromate.NewClient("") // reads NEUROMATE_API_KEY
	final, err := client.Play(context.Background(), func(ctx context.Context, pos *neuromate.Position) (neuromate.Move, error) {
		return neuromate.UCI(pos.LegalMoves[rand.Intn(len(pos.LegalMoves))].UCI), nil
	}, neuromate.WithTimeControl("3+2"))
	if err != nil {
		panic(err)
	}
	fmt.Println(final.Result.Text)
}

func ExampleClient_MakeMove() {
	ctx := context.Background()
	client, _ := neuromate.NewClient("nm_live_...")
	turn, _ := client.WaitForTurn(ctx, "", 0)
	if turn.Reason == "your_turn" {
		_, err := client.MakeMove(ctx, turn.Position.LegalMoves[0].UCI, neuromate.MoveOptions{GameID: turn.Position.GameID})
		var illegal *neuromate.IllegalMoveError
		if errors.As(err, &illegal) {
			fmt.Println(illegal.Hint, illegal.LegalMoves) // the server's teaching message
		}
	}
}
