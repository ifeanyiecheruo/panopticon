package dbstore

import (
	"errors"
	"path/filepath"
	"slices"
	"testing"
)

func openTestStore(t *testing.T) *Store {
	t.Helper()
	store, err := Open(filepath.Join(t.TempDir(), "panopticon.db"))
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { store.Close() })
	return store
}

func TestMigration005_SeedsEachPhonesAddress(t *testing.T) {
	db, path := openAtVersion(t, 4)
	if _, err := db.Exec(`INSERT INTO phones (id, name, base_url, token, created_at_ms) VALUES ('ph_a', 'A', 'http://10.0.0.5', 'tok', 1)`); err != nil {
		t.Fatalf("seed: %v", err)
	}
	db.Close()

	store, err := Open(path)
	if err != nil {
		t.Fatalf("Open (migrates to latest): %v", err)
	}
	defer store.Close()
	got, err := store.PhoneAddresses("ph_a")
	if err != nil || !slices.Equal(got, []string{"http://10.0.0.5"}) {
		t.Fatalf("addresses = %v (err %v), want the phone's base_url", got, err)
	}
}

func TestPhoneAddresses(t *testing.T) {
	store := openTestStore(t)
	if err := store.InsertPhone(Phone{ID: "ph_a", Name: "A", BaseURL: "http://10.0.0.5", Token: "tok"}); err != nil {
		t.Fatalf("InsertPhone: %v", err)
	}
	if got, _ := store.PhoneAddresses("ph_a"); !slices.Equal(got, []string{"http://10.0.0.5"}) {
		t.Fatalf("after pairing, addresses = %v, want just the paired one", got)
	}

	want := []string{"http://10.0.0.9", "http://100.64.0.3"}
	if err := store.SetPhoneAddresses("ph_a", want, "http://100.64.0.3"); err != nil {
		t.Fatalf("SetPhoneAddresses: %v", err)
	}
	got, _ := store.PhoneAddresses("ph_a")
	phone, _ := store.GetPhone("ph_a")
	if !slices.Equal(got, want) || phone.BaseURL != "http://100.64.0.3" {
		t.Fatalf("addresses = %v, in use %q; want %v, in use the second", got, phone.BaseURL, want)
	}

	if err := store.SetPhoneAddresses("ph_a", want, "http://10.0.0.5"); err == nil {
		t.Errorf("an address in use that isn't listed should be refused")
	}
	if err := store.SetPhoneAddresses("ph_a", nil, ""); err == nil {
		t.Errorf("an empty list should be refused")
	}
	if err := store.SetPhoneAddresses("ph_missing", want, want[0]); !errors.Is(err, ErrNotFound) {
		t.Errorf("unknown phone: err = %v, want ErrNotFound", err)
	}

	if err := store.DeletePhone("ph_a"); err != nil {
		t.Fatalf("DeletePhone: %v", err)
	}
	if got, _ := store.PhoneAddresses("ph_a"); len(got) != 0 {
		t.Errorf("unpairing should drop the addresses, still have %v", got)
	}
}
