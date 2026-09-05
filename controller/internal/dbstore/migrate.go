package dbstore

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"io/fs"

	"github.com/pressly/goose/v3"
)

//go:embed schemas/db/*.sql
var migrationsFS embed.FS

// migrate applies every pending migration under schemas/db. Safe to call on
// every startup — goose tracks what's already applied in its own table.
func migrate(ctx context.Context, db *sql.DB) error {
	provider, err := createMigrationsProvider(db)
	if err != nil {
		return err
	}
	if _, err := provider.Up(ctx); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	return nil
}

func createMigrationsProvider(db *sql.DB) (*goose.Provider, error) {
	fsys, err := fs.Sub(migrationsFS, "schemas/db")
	if err != nil {
		return nil, fmt.Errorf("migrations fs: %w", err)
	}
	return goose.NewProvider(goose.DialectSQLite3, db, fsys)
}
