package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// validEngines is the intersection of engines goose and sqlc both support.
var validEngines = map[string]bool{
	"sqlite":     true,
	"mysql":      true,
	"postgresql": true,
}

// Store is the flat, single-store shape of a "<name>.dbstore.json" project
// file. All Src/Gen paths are relative to the directory containing the
// project file.
type Store struct {
	Engine string `json:"engine"`
	Src    struct {
		Schemas string `json:"schemas"`
		Queries string `json:"queries"`
	} `json:"src"`
	Gen struct {
		Dst     string `json:"dst"`
		Package string `json:"package"`
	} `json:"gen"`
}

// LoadStore reads and validates the project file at path, returning the
// parsed store and the absolute directory it lives in (the base every
// relative path in the store resolves against).
func LoadStore(path string) (*Store, string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, "", fmt.Errorf("read project file: %w", err)
	}
	var s Store
	if err := json.Unmarshal(data, &s); err != nil {
		return nil, "", fmt.Errorf("parse project file %s: %w", path, err)
	}
	if !validEngines[s.Engine] {
		return nil, "", fmt.Errorf("project file %s: unsupported engine %q (want sqlite, mysql, or postgresql)", path, s.Engine)
	}
	if s.Src.Schemas == "" {
		return nil, "", fmt.Errorf("project file %s: src.schemas is required", path)
	}
	if s.Src.Queries == "" {
		return nil, "", fmt.Errorf("project file %s: src.queries is required", path)
	}
	if s.Gen.Dst == "" {
		return nil, "", fmt.Errorf("project file %s: gen.dst is required", path)
	}
	if s.Gen.Package == "" {
		return nil, "", fmt.Errorf("project file %s: gen.package is required", path)
	}

	abs, err := filepath.Abs(path)
	if err != nil {
		return nil, "", fmt.Errorf("resolve project file path: %w", err)
	}
	return &s, filepath.Dir(abs), nil
}

// resolve joins base with rel and cleans the result — the shared rule every
// Src/Gen path in a project file follows.
func resolve(base, rel string) string {
	return filepath.Join(base, rel)
}

// SchemasDir, QueriesDir, and DstDir return the store's three paths resolved
// against dir (the value LoadStore returned alongside the store).
func (s *Store) SchemasDir(dir string) string { return resolve(dir, s.Src.Schemas) }
func (s *Store) QueriesDir(dir string) string { return resolve(dir, s.Src.Queries) }
func (s *Store) DstDir(dir string) string     { return resolve(dir, s.Gen.Dst) }
