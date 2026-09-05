// Package unpair implements the controller side of unpairing, per
// HANDOFF-controller-ux.md's "Pairing & unpairing":
//
//   - Unpair (safe): before revoking, check GET /api/clips for clips the phone
//     still has that we never archived and warn if there are any; require the
//     phone to be reachable and DELETE /api/pair to succeed before removing
//     local state (so we never drop a pairing the phone still thinks is live).
//   - Force unpair: best-effort revoke, remove local state regardless.
//
// Either way, already-archived clips are kept — they stay in the aggregate
// Gallery as historical footage from a now-unpaired phone (store.DeletePhone
// deletes only the pairing row).
package unpair

import (
	"context"
	"errors"
	"fmt"

	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/phoneapi"
)

type Outcome int

const (
	OutcomeOK Outcome = iota
	// OutcomeNeedsConfirmation: the safe path found unsynced clips and
	// confirmed was false — nothing was changed; call again with confirmed=true.
	OutcomeNeedsConfirmation
	// OutcomeUnreachable: safe path only — the phone didn't answer, so its
	// token can't be revoked and local state is left intact.
	OutcomeUnreachable
	// OutcomeRevokeFailed: safe path only — the phone answered but DELETE
	// /api/pair failed; local state left intact.
	OutcomeRevokeFailed
	OutcomeError
)

type Result struct {
	Outcome       Outcome
	UnsyncedCount int
	Message       string
}

// Unpair is the safe path. When confirmed is false and the phone reports clips
// we haven't archived, it returns OutcomeNeedsConfirmation and changes
// nothing. It requires the phone to be reachable and the revoke to succeed
// (or to come back 401 — the phone has already forgotten us) before removing
// the local pairing.
func Unpair(ctx context.Context, store *dbstore.Store, phoneID string, confirmed bool) (Result, error) {
	phone, err := store.GetPhone(phoneID)
	if err != nil {
		return Result{Outcome: OutcomeError, Message: err.Error()}, err
	}
	client := phoneapi.New(phone.BaseURL, phone.Token)

	// Unsynced-clips check (skipped once the user has confirmed).
	if !confirmed {
		unsynced, err := countUnsynced(ctx, store, client, phoneID)
		if err != nil {
			if errors.Is(err, phoneapi.ErrUnreachable) {
				return Result{
					Outcome: OutcomeUnreachable,
					Message: fmt.Sprintf("Could not reach %s to unpair. The phone still holds a valid token, so it stays paired. Use Force unpair to remove it locally anyway.", phone.Name),
				}, nil
			}
			return Result{Outcome: OutcomeError, Message: err.Error()}, err
		}
		if unsynced > 0 {
			return Result{
				Outcome:       OutcomeNeedsConfirmation,
				UnsyncedCount: unsynced,
				Message:       fmt.Sprintf("%d clip%s haven't synced yet; unpairing risks losing them once the phone's ring buffer evicts them.", unsynced, plural(unsynced)),
			}, nil
		}
	}

	// Revoke, then remove locally.
	if err := client.Unpair(ctx); err != nil {
		switch {
		case errors.Is(err, phoneapi.ErrUnauthorized):
			// The phone has already invalidated this token — nothing to revoke,
			// safe to drop local state.
		case errors.Is(err, phoneapi.ErrUnreachable):
			return Result{
				Outcome: OutcomeUnreachable,
				Message: fmt.Sprintf("Could not reach %s to revoke the pairing. It stays paired locally; use Force unpair to remove it anyway.", phone.Name),
			}, nil
		default:
			return Result{
				Outcome: OutcomeRevokeFailed,
				Message: fmt.Sprintf("The phone rejected the unpair request (%v). It stays paired locally; use Force unpair to remove it anyway.", err),
			}, nil
		}
	}

	if err := store.DeletePhone(phoneID); err != nil {
		return Result{Outcome: OutcomeError, Message: err.Error()}, err
	}
	return Result{Outcome: OutcomeOK}, nil
}

// Force removes the local pairing regardless of whether the phone can be
// reached or the revoke succeeds. It still *attempts* the revoke (best effort)
// so a reachable phone does drop the token. It cannot do the unsynced-clips
// check — the phone may be unreachable, which is the whole premise.
func Force(ctx context.Context, store *dbstore.Store, phoneID string) (Result, error) {
	phone, err := store.GetPhone(phoneID)
	if err != nil {
		return Result{Outcome: OutcomeError, Message: err.Error()}, err
	}

	revokeErr := phoneapi.New(phone.BaseURL, phone.Token).Unpair(ctx)
	if err := store.DeletePhone(phoneID); err != nil {
		return Result{Outcome: OutcomeError, Message: err.Error()}, err
	}

	msg := fmt.Sprintf("%s removed.", phone.Name)
	if revokeErr != nil && !errors.Is(revokeErr, phoneapi.ErrUnauthorized) {
		msg += " The phone could not be reached to revoke its token — it may still hold a live token until cleared from the phone's own UI."
	}
	return Result{Outcome: OutcomeOK, Message: msg}, nil
}

// countUnsynced asks the phone what clips it has since our sync cursor and
// counts the ones we haven't archived locally.
func countUnsynced(ctx context.Context, store *dbstore.Store, client *phoneapi.Client, phoneID string) (int, error) {
	phone, err := store.GetPhone(phoneID)
	if err != nil {
		return 0, err
	}
	resp, err := client.Clips(ctx, phone.SyncCursorMs)
	if err != nil {
		return 0, err
	}
	n := 0
	for _, c := range resp.Clips {
		exists, err := store.ClipExists(phoneID, c.Filename)
		if err != nil {
			return 0, err
		}
		if !exists {
			n++
		}
	}
	return n, nil
}

func plural(n int) string {
	if n == 1 {
		return ""
	}
	return "s"
}
