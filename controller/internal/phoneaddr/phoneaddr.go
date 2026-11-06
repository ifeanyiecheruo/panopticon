// Package phoneaddr keeps a paired phone reachable when its address changes:
// checking that an address still reaches the phone, and moving a phone that
// stopped answering onto another of its known addresses.
//
// A phone can answer at several addresses (wifi and a VPN, say), and DHCP can
// move it. Its identity is its bearer token, never an address (see
// docs/design/decisions/0003-pairing-and-unpairing.md): an address reaches the
// phone exactly when the phone there accepts this controller's token.
package phoneaddr

import (
	"context"
	"errors"
	"log"
	"sync"
	"time"

	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/phoneapi"
)

// probeTimeout bounds one address check. Shorter than phoneapi's metadata
// timeout: a LAN phone that's there answers in milliseconds, and failover
// probes run inside a sync tick.
const probeTimeout = 3 * time.Second

// Reach is what checking one address found.
type Reach string

const (
	ReachOK          Reach = "ok"
	ReachUnreachable Reach = "unreachable"
	// ReachWrongPhone means something answered but refused this phone's
	// token: another phone, or one that has since revoked this controller.
	ReachWrongPhone Reach = "wrong_phone"
	// ReachError is any other answer (an HTTP error, a non-phone server).
	ReachError Reach = "error"
)

// Check asks the phone at baseURL for its device identity with token.
func Check(ctx context.Context, baseURL, token string) Reach {
	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()
	_, err := phoneapi.New(baseURL, token).Device(ctx)
	switch {
	case err == nil:
		return ReachOK
	case errors.Is(err, phoneapi.ErrUnreachable):
		return ReachUnreachable
	case errors.Is(err, phoneapi.ErrUnauthorized):
		return ReachWrongPhone
	default:
		return ReachError
	}
}

// CheckAll checks every address concurrently, results in addresses' order.
func CheckAll(ctx context.Context, addresses []string, token string) []Reach {
	out := make([]Reach, len(addresses))
	var wg sync.WaitGroup
	for i, a := range addresses {
		wg.Add(1)
		go func() {
			defer wg.Done()
			out[i] = Check(ctx, a, token)
		}()
	}
	wg.Wait()
	return out
}

// Failover is for a phone that didn't answer at its BaseURL: it checks the
// phone's other known addresses and, if one reaches it, makes that the
// address in use. It returns the phone as stored afterwards and whether the
// address changed. With several reachable, the first in the user's order wins.
func Failover(ctx context.Context, store *dbstore.Store, phone dbstore.Phone) (dbstore.Phone, bool) {
	addresses, err := store.PhoneAddresses(phone.ID)
	if err != nil {
		log.Printf("phoneaddr[%s]: list addresses: %v", phone.ID, err)
		return phone, false
	}
	others := make([]string, 0, len(addresses))
	for _, a := range addresses {
		if a != phone.BaseURL {
			others = append(others, a)
		}
	}
	if len(others) == 0 {
		return phone, false
	}
	for i, r := range CheckAll(ctx, others, phone.Token) {
		if r != ReachOK {
			continue
		}
		if err := store.SetPhoneActiveAddress(phone.ID, others[i]); err != nil {
			log.Printf("phoneaddr[%s]: switch to %s: %v", phone.ID, others[i], err)
			return phone, false
		}
		log.Printf("phoneaddr[%s]: %s not answering, switched to %s", phone.ID, phone.BaseURL, others[i])
		phone.BaseURL = others[i]
		return phone, true
	}
	return phone, false
}
