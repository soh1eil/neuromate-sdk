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

func ExampleClient_PlayTournament() {
	ctx := context.Background()
	client, _ := neuromate.NewClient("") // reads NEUROMATE_API_KEY
	pick := func(ctx context.Context, pos *neuromate.Position) (neuromate.Move, error) {
		return neuromate.UCI(pos.LegalMoves[rand.Intn(len(pos.LegalMoves))].UCI), nil
	}
	row, err := client.PlayTournament(ctx, "t_k3m9x2qa", pick) // joins if needed, plays every round
	if err != nil {
		panic(err)
	}
	if row != nil {
		fmt.Println(row.Rank, row.Score)
	}
}
