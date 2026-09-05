package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"

	"github.com/spf13/cobra"
	"gopkg.in/yaml.v3"
)

// sqlcConfig mirrors the shape of a hand-written sqlc.yaml (version 2,
// single sql entry, go codegen with idiomatic-Go emit_* flags), rendered
// programmatically instead of maintained by hand.
type sqlcConfig struct {
	Version string    `yaml:"version"`
	SQL     []sqlcSQL `yaml:"sql"`
}

type sqlcSQL struct {
	Engine  string      `yaml:"engine"`
	Queries string      `yaml:"queries"`
	Schema  []string    `yaml:"schema"`
	Gen     sqlcGenSpec `yaml:"gen"`
}

type sqlcGenSpec struct {
	Go sqlcGoSpec `yaml:"go"`
}

type sqlcGoSpec struct {
	Package             string `yaml:"package"`
	Out                 string `yaml:"out"`
	EmitJSONTags        bool   `yaml:"emit_json_tags"`
	EmitPreparedQueries bool   `yaml:"emit_prepared_queries"`
	EmitInterface       bool   `yaml:"emit_interface"`
	EmitExactTableNames bool   `yaml:"emit_exact_table_names"`
	EmitEmptySlices     bool   `yaml:"emit_empty_slices"`
}

// group is a set of stores that share one (queries, dst, package) triple —
// and therefore compile into one sqlc invocation with a combined schema list.
type group struct {
	queries string
	dst     string
	pkg     string
	engine  string
	schemas []string
}

// buildGroups loads every project file and groups stores that resolve to the
// same (queries, dst, package) triple into a single sqlc invocation, in the
// order their group first appeared. Members of a group must agree on engine.
func buildGroups(projects []string) ([]*group, error) {
	groups := map[[3]string]*group{}
	var order [][3]string
	for _, p := range projects {
		store, dir, err := LoadStore(p)
		if err != nil {
			return nil, err
		}
		key := [3]string{store.QueriesDir(dir), store.DstDir(dir), store.Gen.Package}
		g, ok := groups[key]
		if !ok {
			g = &group{queries: key[0], dst: key[1], pkg: key[2], engine: store.Engine}
			groups[key] = g
			order = append(order, key)
		} else if g.engine != store.Engine {
			return nil, fmt.Errorf("%s: engine %q conflicts with %q already grouped under %s", p, store.Engine, g.engine, key[0])
		}
		g.schemas = append(g.schemas, store.SchemasDir(dir))
	}
	out := make([]*group, len(order))
	for i, key := range order {
		out[i] = groups[key]
	}
	return out, nil
}

func newGenerateCmd() *cobra.Command {
	var projects []string

	cmd := &cobra.Command{
		Use:   "generate",
		Short: "Generate sqlc code for one or more stores, grouping stores that share a queries/output package",
		RunE: func(cmd *cobra.Command, _ []string) error {
			if len(projects) == 0 {
				return fmt.Errorf("at least one --project is required")
			}

			groups, err := buildGroups(projects)
			if err != nil {
				return err
			}
			for _, g := range groups {
				if err := runSqlcGenerate(g); err != nil {
					return err
				}
			}
			return nil
		},
	}
	cmd.Flags().StringArrayVar(&projects, "project", nil, "path to a <name>.dbstore.json project file (repeatable)")
	return cmd
}

func runSqlcGenerate(g *group) error {
	tmpDir, err := os.MkdirTemp("", "dbstore-sqlc-*")
	if err != nil {
		return fmt.Errorf("create temp dir: %w", err)
	}
	defer func() { _ = os.RemoveAll(tmpDir) }()

	// sqlc resolves schema/queries/out by joining them onto the config
	// file's own directory rather than recognizing them as already-absolute
	// (confirmed upstream in morsel: an absolute path here gets
	// concatenated, not substituted, breaking on Windows). Express every
	// path relative to tmpDir instead.
	relQueries, err := filepath.Rel(tmpDir, g.queries)
	if err != nil {
		return fmt.Errorf("relativize queries dir: %w", err)
	}
	relDst, err := filepath.Rel(tmpDir, g.dst)
	if err != nil {
		return fmt.Errorf("relativize dst dir: %w", err)
	}
	relSchemas := make([]string, len(g.schemas))
	for i, s := range g.schemas {
		rel, err := filepath.Rel(tmpDir, s)
		if err != nil {
			return fmt.Errorf("relativize schema dir %s: %w", s, err)
		}
		relSchemas[i] = rel
	}

	cfg := sqlcConfig{
		Version: "2",
		SQL: []sqlcSQL{
			{
				Engine:  g.engine,
				Queries: relQueries,
				Schema:  relSchemas,
				Gen: sqlcGenSpec{
					Go: sqlcGoSpec{
						Package:             g.pkg,
						Out:                 relDst,
						EmitJSONTags:        false,
						EmitPreparedQueries: false,
						EmitInterface:       false,
						EmitExactTableNames: false,
						EmitEmptySlices:     true,
					},
				},
			},
		},
	}

	data, err := yaml.Marshal(&cfg)
	if err != nil {
		return fmt.Errorf("marshal sqlc.yaml: %w", err)
	}
	sqlcPath := filepath.Join(tmpDir, "sqlc.yaml")
	if err := os.WriteFile(sqlcPath, data, 0o644); err != nil {
		return fmt.Errorf("write sqlc.yaml: %w", err)
	}

	cmd := exec.Command("go", "run", "github.com/sqlc-dev/sqlc/cmd/sqlc", "generate", "--file", sqlcPath)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("sqlc generate (package %s): %w", g.pkg, err)
	}
	return nil
}
