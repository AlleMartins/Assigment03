package player

import (
	"math/rand"
	"time"
)

// Message types - redefined here to avoid circular imports
type MessageType int

const (
	MsgRegister MessageType = iota
	MsgGameStart
	MsgShowFingers
	MsgResult
	MsgGameComplete
	MsgShutdown
)

type Message struct {
	Type   MessageType
	Sender int
	Data   interface{}
}

// Player represents a participant in the Odds-and-Evens tournament
type Player struct {
	id      int
	channel chan Message
	out     chan Message
}

// New creates a new player instance
func New(id int) *Player {
	return &Player{
		id:      id,
		channel: make(chan Message, 10),
		out:     make(chan Message, 10),
	}
}

// Start begins the player's goroutine to handle messages
func (p *Player) Start() {
	go func() {
		defer close(p.out)
		for msg := range p.channel {
			p.handleMessage(msg)
		}
	}()
}

// GetMessageChannel returns the player's input message channel
func (p *Player) GetMessageChannel() chan Message {
	return p.channel
}

// GetResultChannel returns the player's output/result channel
func (p *Player) GetResultChannel() chan Message {
	return p.out
}

// GetID returns the player's unique identifier
func (p *Player) GetID() int {
	return p.id
}

// RespondToGame shows fingers (0 or 1) in response to a game request
func (p *Player) RespondToGame() int {
	// Randomly choose 0 or 1 finger
	fingers := rand.Intn(2)
	return fingers
}

// HandleMessage processes incoming messages
func (p *Player) handleMessage(msg Message) {
	switch msg.Type {
	case MsgRegister:
		// Register confirmation - player is ready
	case MsgGameStart:
		// Ready to play
	case MsgShowFingers:
		// Show fingers and send result
		fingers := p.RespondToGame()
		resultMsg := Message{
			Type:   MsgResult,
			Sender: p.id,
			Data:   fingers,
		}
		select {
		case p.out <- resultMsg:
		case <-time.After(100 * time.Millisecond):
			// Channel full, skip
		}
	case MsgGameComplete:
		// Round complete
	case MsgShutdown:
		return
	}
}

// Send sends a message to this player
func (p *Player) Send(msg Message) {
	select {
	case p.channel <- msg:
	case <-time.After(100 * time.Millisecond):
		// Channel full, skip
	}
}

// Shutdown gracefully shuts down the player
func (p *Player) Shutdown() {
	p.Send(Message{Type: MsgShutdown})
}
