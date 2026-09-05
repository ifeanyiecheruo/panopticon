package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"

	"github.com/spf13/cobra"
)

func newMigrateCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "migrate",
		Short: "Manage forward schema migrations",
	}
	cmd.AddCommand(newMigrateNewCmd())
	return cmd
}

var migrationFileRe = regexp.MustCompile(`^(\d+)_.*\.sql$`)

func newMigrateNewCmd() *cobra.Command {
	var project string

	cmd := &cobra.Command{
		Use:   "new <name>",
		Short: "Scaffold a new forward migration file in the store's schema directory",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if project == "" {
				return fmt.Errorf("--project is required")
			}
			name := args[0]

			store, dir, err := LoadStore(project)
			if err != nil {
				return err
			}
			schemasDir := store.SchemasDir(dir)
			if err := os.MkdirAll(schemasDir, 0o755); err != nil {
				return fmt.Errorf("create schema dir: %w", err)
			}

			next, err := nextMigrationNumber(schemasDir)
			if err != nil {
				return err
			}

			filename := fmt.Sprintf("%03d_%s.sql", next, name)
			path := filepath.Join(schemasDir, filename)
			if err := os.WriteFile(path, []byte(migrationStub), 0o644); err != nil {
				return fmt.Errorf("write migration: %w", err)
			}
			fmt.Println(path)
			return nil
		},
	}
	cmd.Flags().StringVar(&project, "project", "", "path to the <name>.dbstore.json project file (required)")
	return cmd
}

// nextMigrationNumber scans dir for existing "NNN_*.sql" files and returns
// one more than the highest NNN found (1 if none exist).
func nextMigrationNumber(dir string) (int, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0, fmt.Errorf("read schema dir: %w", err)
	}
	var nums []int
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		m := migrationFileRe.FindStringSubmatch(e.Name())
		if m == nil {
			continue
		}
		n, err := strconv.Atoi(m[1])
		if err != nil {
			continue
		}
		nums = append(nums, n)
	}
	if len(nums) == 0 {
		return 1, nil
	}
	sort.Ints(nums)
	return nums[len(nums)-1] + 1, nil
}
