package main

import (
	"errors"

	"panopticon-controller/internal/phoneapi"
)

// RetuneArea asks the phone to fine-tune exposure and focus for its explicit
// area of interest again now. The phone tunes by itself - when an area is
// drawn, when its camera starts, and when the light changes - so this is only
// for not waiting (docs/design/decisions/0017-area-of-interest-fine-tuning.md).
func (a *App) RetuneArea(phoneID string) CameraActionResult {
	phone, err := a.store.GetPhone(phoneID)
	if err != nil {
		return CameraActionResult{Outcome: "other", Message: err.Error()}
	}
	err = phoneapi.New(phone.BaseURL, phone.Token).RetuneArea(a.ctxOrBackground())
	switch {
	case err == nil:
		return CameraActionResult{OK: true, Outcome: "ok"}
	case errors.Is(err, phoneapi.ErrUnreachable):
		return CameraActionResult{Outcome: "unreachable", Message: "Could not reach the phone."}
	default:
		var httpErr *phoneapi.HTTPError
		if errors.As(err, &httpErr) && httpErr.StatusCode == 404 {
			return CameraActionResult{Outcome: "other", Message: "The phone app needs updating to fine-tune by itself."}
		}
		if errors.As(err, &httpErr) {
			return CameraActionResult{Outcome: "other", Message: phoneErrorText(httpErr.Body, "The phone couldn't re-tune.")}
		}
		return CameraActionResult{Outcome: "other", Message: err.Error()}
	}
}
