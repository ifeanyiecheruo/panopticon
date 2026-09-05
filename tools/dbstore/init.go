package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/cobra"
)

const projectSuffix = ".dbstore.json"

func newInitCmd() *cobra.Command {
	var project string
	var engine string
	var schema string
	var queries string
	var dst string
	var pkg string

	cmd := &cobra.Command{
		Use:   "init",
		Short: "Create a new <name>.dbstore.json project file and its initial schema migration",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return runInit(project, engine, schema, queries, dst, pkg)
		},
	}

	cmd.Flags().StringVar(&project, "project", "", "path to the <name>.dbstore.json project file to create (required)")
	cmd.Flags().StringVar(&engine, "engine", "sqlite", "database engine: sqlite, mysql, or postgresql")
	cmd.Flags().StringVar(&schema, "schema", "", "schema/migration source directory (default schemas/<name>)")
	cmd.Flags().StringVar(&queries, "queries", "", "query .sql source directory (default queries)")
	cmd.Flags().StringVar(&dst, "dst", "", "generated code output directory (default: the resolved --package name)")
	cmd.Flags().StringVar(&pkg, "package", "", "generated Go package name (default queries)")

	return cmd
}

// runInit creates the project file at project (must not already exist) and
// its initial migration, filling in any unset schema/queries/dst/pkg from
// the project's filename stem.
func runInit(project, engine, schema, queries, dst, pkg string) error {
	if project == "" {
		return fmt.Errorf("--project is required")
	}
	name := strings.TrimSuffix(filepath.Base(project), projectSuffix)
	if name == filepath.Base(project) {
		return fmt.Errorf("--project must end in %s", projectSuffix)
	}

	if _, err := os.Stat(project); err == nil {
		return fmt.Errorf("project file already exists: %s", project)
	}

	if schema == "" {
		schema = filepath.Join("schemas", name)
	}
	if queries == "" {
		queries = "queries"
	}
	if pkg == "" {
		pkg = "queries"
	}
	if dst == "" {
		dst = pkg
	}

	var s Store
	s.Engine = engine
	s.Src.Schemas = schema
	s.Src.Queries = queries
	s.Gen.Dst = dst
	s.Gen.Package = pkg

	if !validEngines[s.Engine] {
		return fmt.Errorf("unsupported engine %q (want sqlite, mysql, or postgresql)", s.Engine)
	}

	dir := filepath.Dir(project)
	if dir == "" {
		dir = "."
	}

	data, err := json.MarshalIndent(&s, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal project file: %w", err)
	}
	data = append(data, '\n')
	if err := os.WriteFile(project, data, 0o644); err != nil {
		return fmt.Errorf("write project file: %w", err)
	}

	schemasDir := resolve(dir, schema)
	if err := os.MkdirAll(schemasDir, 0o755); err != nil {
		return fmt.Errorf("create schema dir: %w", err)
	}
	initial := filepath.Join(schemasDir, "001_initial.sql")
	if err := os.WriteFile(initial, []byte(migrationStub), 0o644); err != nil {
		return fmt.Errorf("write initial migration: %w", err)
	}

	fmt.Printf("created %s\n", project)
	fmt.Printf("created %s\n", initial)
	fmt.Printf("next: edit %s, then run `dbstore generate --project %s`\n", initial, project)
	return nil
}

const migrationStub = `-- +goose Up

-- +goose Down
`
