// Package pairing implements the Add-phone flow: parsing the two-field
// paste form (or a pasted full invite URL), calling POST /api/pair, and
// persisting the resulting phone + bearer token.
package pairing

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log"

	"panopticon-controller/internal/calibration"
	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/identity"
	"panopticon-controller/internal/phoneapi"
)

// Result is what the Add-phone success card needs: the newly paired phone's
// identity as reported by the phone itself (name comes from pairing;
// manufacturer/model from a follow-up GET /api/device call).
type Result struct {
	PhoneID      string
	Name         string
	Manufacturer string
	Model        string
}

// Outcome classifies a failure the UI must render distinctly, per
// HANDOFF-controller-ux.md's Add-phone flow ("distinguishing an unreachable
// address ... from a bad invite").
type Outcome int

const (
	OutcomeOK Outcome = iota
	OutcomeUnreachable
	OutcomeInvalidInvite
	OutcomeOtherError
)

// Error wraps a pairing failure with its Outcome classification and a
// ready-to-display message.
type Error struct {
	Outcome Outcome
	Message string
	cause   error
}

func (e *Error) Error() string { return e.Message }
func (e *Error) Unwrap() error { return e.cause }

// AddPhone drives the whole pairing exchange: normalizes the address, POSTs
// /api/pair with this controller's persistent identity, and on success
// stores the phone + token in SQLite. address/inviteCode are assumed
// already resolved (see ParseInviteOrFields for the two-field-vs-URL
// disambiguation the Add-phone screen needs before calling this).
func AddPhone(ctx context.Context, store *dbstore.Store, address, inviteCode string) (Result, error) {
	baseURL := phoneapi.NormalizeAddress(address)
	if baseURL == "" {
		return Result{}, &Error{Outcome: OutcomeOtherError, Message: "Enter the phone's IP address or hostname."}
	}
	if inviteCode == "" {
		return Result{}, &Error{Outcome: OutcomeOtherError, Message: "Enter the invite code shown on the phone."}
	}

	id, err := identity.LoadOrCreate(store)
	if err != nil {
		return Result{}, fmt.Errorf("load controller identity: %w", err)
	}

	client := phoneapi.New(baseURL, "")
	pairResp, err := client.Pair(ctx, inviteCode, phoneapi.PairRequest{
		PublicKey: identity.PublicKeyBase64(id),
		Name:      id.Name,
		Kind:      "desktop",
	})
	if err != nil {
		switch {
		case errors.Is(err, phoneapi.ErrUnreachable):
			return Result{}, &Error{Outcome: OutcomeUnreachable, Message: fmt.Sprintf("Could not reach %s on the network.", address), cause: err}
		case errors.Is(err, phoneapi.ErrInvalidInvite):
			return Result{}, &Error{Outcome: OutcomeInvalidInvite, Message: "Invalid or expired invite code.", cause: err}
		default:
			return Result{}, &Error{Outcome: OutcomeOtherError, Message: "Pairing failed: " + err.Error(), cause: err}
		}
	}

	// Best-effort device-identity lookup right after pairing, so Fleet/Phone
	// detail can show manufacturer/model immediately. Not fatal if it fails
	// (phone answered /api/pair but then dropped off wifi, say) — the phone
	// is still successfully paired either way.
	authed := phoneapi.New(baseURL, pairResp.Token)
	manufacturer, model := "", ""
	if dev, err := authed.Device(ctx); err == nil {
		manufacturer, model = dev.Manufacturer, dev.Model
	}

	name := pairResp.Phone.Name
	if name == "" {
		name = "Paired phone"
	}
	phoneID := pairResp.Phone.PhoneID
	if phoneID == "" {
		phoneID = generatePhoneID()
	}

	rec := dbstore.Phone{
		ID:           phoneID,
		Name:         name,
		BaseURL:      baseURL,
		Token:        pairResp.Token,
		Manufacturer: manufacturer,
		Model:        model,
		LastSeenMs:   dbstore.NowMs(),
		SyncCursorMs: 0,
		CreatedAtMs:  dbstore.NowMs(),
	}
	if err := store.InsertPhone(rec); err != nil {
		return Result{}, fmt.Errorf("save paired phone: %w", err)
	}

	// Opportunistic calibration ingest, per HANDOFF-controller-ux.md's data
	// model: if this phone already has a persisted sweep result, pull it now
	// so its model is calibrated for the whole fleet without anyone running a
	// sweep. Best-effort — a phone that never calibrated, or dropped off wifi
	// right after pairing, just leaves this for the next Phone-detail open.
	if ingested, err := calibration.IngestOpportunistic(ctx, store, rec); err != nil {
		log.Printf("pairing: opportunistic calibration ingest for %s: %v", phoneID, err)
	} else if ingested {
		log.Printf("pairing: ingested calibration result from %s for model %q", phoneID, calibration.ModelKey(manufacturer, model))
	}

	return Result{PhoneID: phoneID, Name: name, Manufacturer: manufacturer, Model: model}, nil
}

func generatePhoneID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "ph_" + hex.EncodeToString(b)
}
