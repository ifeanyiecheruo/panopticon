// Command dbstore scaffolds and generates code for a component's private
// SQL store: one <name>.dbstore.json project file per store, describing its
// schema/migration source, query source, and generated-code destination.
//
// It never opens a database connection — the runtime (each store's own
// migrate.go, see controller/internal/dbstore) always migrates a store
// forward to its latest schema on open. dbstore only scaffolds new
// migration files and drives sqlc codegen.
//
// Ported from ../../../morsel/cmd/dbstore, which this is a close copy of —
// see that project if a change here seems like it should apply there too.
package main

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
)

func main() {
	root := &cobra.Command{
		Use:           "dbstore",
		Short:         "Scaffold and generate code for private SQL stores",
		SilenceErrors: true,
		SilenceUsage:  true,
	}
	root.AddCommand(newInitCmd(), newMigrateCmd(), newGenerateCmd())

	if err := root.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
