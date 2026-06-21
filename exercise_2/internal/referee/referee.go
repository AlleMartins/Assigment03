package referee

import (
	"fmt"
	"sync"
	"time"

	"odds-evens/internal/player"
)

// Referee coordinates the Odds-and-Evens tournament
type Referee struct {
	players  []*player.Player
	mutex    sync.Mutex
	roundNum int
}

// New creates a new Referee instance
func New() *Referee {
	return &Referee{
		players: make([]*player.Player, 0),
	}
}

// RegisterPlayer adds a player to the tournament
func (r *Referee) RegisterPlayer(p *player.Player) {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	r.players = append(r.players, p)
}

// RunTournament conducts the complete Odds-and-Evens tournament
func (r *Referee) RunTournament() *player.Player {
	fmt.Printf("Starting Odds-and-Evens tournament with %d players\n", len(r.players))

	// Calculate number of rounds: log2(numPlayers)
	numRounds := calculateRounds(len(r.players))
	fmt.Printf("Number of rounds: %d\n", numRounds)

	currentPlayers := r.clonePlayers()
	roundNum := 0

	for len(currentPlayers) > 1 && roundNum < numRounds {
		fmt.Printf("\n=== Round %d ===\n", roundNum+1)
		currentPlayers = r.runRound(currentPlayers, roundNum)
		roundNum++

		if len(currentPlayers) == 1 {
			fmt.Printf("\n=== Champion: Player %d ===\n", currentPlayers[0].GetID())
			return currentPlayers[0]
		}
	}

	return currentPlayers[0]
}

// calculateRounds determines how many rounds are needed
func calculateRounds(numPlayers int) int {
	rounds := 0
	for n := numPlayers; n > 1; n /= 2 {
		rounds++
	}
	return rounds
}

// clonePlayers creates a copy of the current player slice
func (r *Referee) clonePlayers() []*player.Player {
	clone := make([]*player.Player, len(r.players))
	copy(clone, r.players)
	return clone
}

// runRound executes one round of concurrent games
func (r *Referee) runRound(players []*player.Player, roundNum int) []*player.Player {
	var winners []*player.Player
	var wg sync.WaitGroup

	// Pair players for concurrent games
	for i := 0; i < len(players); i += 2 {
		wg.Add(1)
		p1 := players[i]
		p2 := players[i+1]

		go func(player1, player2 *player.Player) {
			defer wg.Done()
			winner := r.playGame(player1, player2, roundNum)
			if winner != nil {
				r.mutex.Lock()
				defer r.mutex.Unlock()
				winners = append(winners, winner)
			}
		}(p1, p2)
	}

	wg.Wait()
	return winners
}

// playGame conducts a single match between two players
func (r *Referee) playGame(p1, p2 *player.Player, roundNum int) *player.Player {
	fmt.Printf("  Game: Player %d vs Player %d\n", p1.GetID(), p2.GetID())

	// Notify players game is starting
	p1.Send(player.Message{Type: player.MsgGameStart})
	p2.Send(player.Message{Type: player.MsgGameStart})

	// Request fingers from both players with timeout
	var fingers1, fingers2 int
	var mu sync.Mutex
	done := make(chan bool, 2)

	// Player 1 response handler
	go func() {
		for {
			select {
			case msg := <-p1.GetResultChannel():
				if msg.Type == player.MsgResult {
					mu.Lock()
					fingers1 = msg.Data.(int)
					mu.Unlock()
					done <- true
					return
				}
			case <-time.After(2 * time.Second):
				return
			}
		}
	}()

	// Player 2 response handler
	go func() {
		for {
			select {
			case msg := <-p2.GetResultChannel():
				if msg.Type == player.MsgResult {
					mu.Lock()
					fingers2 = msg.Data.(int)
					mu.Unlock()
					done <- true
					return
				}
			case <-time.After(2 * time.Second):
				return
			}
		}
	}()

	// Ask both players to show fingers
	p1.Send(player.Message{Type: player.MsgShowFingers})
	p2.Send(player.Message{Type: player.MsgShowFingers})

	// Wait for responses
	<-done
	<-done

	fmt.Printf("    Player %d shows %d finger(s)\n", p1.GetID(), fingers1)
	fmt.Printf("    Player %d shows %d finger(s)\n", p2.GetID(), fingers2)

	// Determine winner based on odd/even sum
	sum := fingers1 + fingers2
	isOdd := sum%2 == 1

	// Even-indexed players are "Even" team, odd-indexed are "Odd" team
	team1IsOdd := p1.GetID()%2 == 1

	var winner *player.Player
	if isOdd {
		// Odd team wins
		if team1IsOdd {
			winner = p1
		} else {
			winner = p2
		}
	} else {
		// Even team wins
		if !team1IsOdd {
			winner = p1
		} else {
			winner = p2
		}
	}

	fmt.Printf("    Sum: %d (%s) -> Winner: Player %d\n", sum,
		map[bool]string{true: "Odd", false: "Even"}[isOdd], winner.GetID())

	// Notify players of result
	p1.Send(player.Message{Type: player.MsgGameComplete, Data: map[string]interface{}{
		"winner": winner.GetID(),
		"sum":    sum,
	}})
	p2.Send(player.Message{Type: player.MsgGameComplete, Data: map[string]interface{}{
		"winner": winner.GetID(),
		"sum":    sum,
	}})

	return winner
}

// Shutdown gracefully shuts down all players
func (r *Referee) Shutdown() {
	for _, p := range r.players {
		p.Shutdown()
	}
}
