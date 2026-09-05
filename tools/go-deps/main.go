// Command go-deps manages code-generation metadata via .gen.json sidecar
// files, so a Makefile can treat "run the generator" as an ordinary file
// target with real prerequisites instead of a manually-listed input list
// that inevitably drifts. Ported from ../../../morsel/cmd/go-deps (see that
// project's Makefile for the fuller original, which also has directory-mode
// dependency collection and a //go:generate linter; this trimmed copy only
// carries the two subcommands panopticon's own Makefile actually drives).
//
// Subcommands:
//
//	gen <file.gen.json>   – run the declared generator and touch <file>.gen.json.stamp
//	get <file.gen.json>   – print repo-relative input paths for one generator
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
)

// genDef is the shape of a "*.gen.json" sidecar file: cmd is the generator
// command to run (space-split, no shell interpretation — quote-free
// arguments only), inputs are the paths/globs/directories that should
// invalidate the stamp when they change, and outputs are the paths/globs
// the generator writes to (informational here; go-deps itself doesn't need
// them, but a sidecar with an empty outputs list is almost certainly a
// mistake, so mustParseGen still requires the field to be present).
type genDef struct {
	Cmd     string   `json:"cmd"`
	Inputs  []string `json:"inputs"`
	Outputs []string `json:"outputs"`
}

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "usage: go-deps <gen|get> <file.gen.json>")
		os.Exit(1)
	}
	switch os.Args[1] {
	case "gen":
		runGen(os.Args[2])
	case "get":
		runGet(os.Args[2])
	default:
		fmt.Fprintf(os.Stderr, "go-deps: unknown subcommand %q\n", os.Args[1])
		os.Exit(1)
	}
}

// runGen runs the generator declared in jsonFile and touches its stamp.
func runGen(jsonFile string) {
	g := mustParseGen(jsonFile)

	jsonDir := filepath.Dir(jsonFile)
	if jsonDir == "" {
		jsonDir = "."
	}

	parts := strings.Fields(g.Cmd)
	if len(parts) == 0 {
		die("gen: empty cmd in %s", jsonFile)
	}

	cmd := exec.Command(parts[0], parts[1:]...)
	cmd.Dir = jsonDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		die("gen: %v", err)
	}

	// Touch the stamp file.
	stamp := jsonFile + ".stamp"
	f, err := os.Create(stamp)
	if err != nil {
		die("gen: create stamp %s: %v", stamp, err)
	}
	if err := f.Close(); err != nil {
		die("gen: close stamp %s: %v", stamp, err)
	}
}

// runGet prints space-separated repo-relative input paths for jsonFile's
// generator, expanding globs and directories into their matching files. The
// json file itself is always included — editing it invalidates the stamp.
func runGet(jsonFile string) {
	g := mustParseGen(jsonFile)
	jsonDir := filepath.Dir(jsonFile)
	if jsonDir == "" {
		jsonDir = "."
	}
	seen := map[string]bool{filepath.ToSlash(jsonFile): true}
	for _, pattern := range g.Inputs {
		expandPath(jsonDir, pattern, seen)
	}
	printSorted(seen)
}

// expandPath expands a single glob pattern (or plain path/directory)
// relative to baseDir and records all matching files in seen using
// forward-slash paths.
func expandPath(baseDir, pattern string, seen map[string]bool) {
	full := filepath.Join(baseDir, pattern)
	matches, err := filepath.Glob(full)
	if err != nil || len(matches) == 0 {
		// No glob matches — treat as a literal path/directory.
		info, statErr := os.Stat(full)
		if statErr != nil {
			return
		}
		if info.IsDir() {
			walkDir(full, seen)
		} else {
			seen[filepath.ToSlash(full)] = true
		}
		return
	}
	for _, m := range matches {
		info, err := os.Stat(m)
		if err != nil {
			continue
		}
		if info.IsDir() {
			walkDir(m, seen)
		} else {
			seen[filepath.ToSlash(m)] = true
		}
	}
}

func walkDir(dir string, seen map[string]bool) {
	_ = filepath.WalkDir(dir, func(p string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			seen[filepath.ToSlash(p)] = true
		}
		return nil
	})
}

func mustParseGen(path string) *genDef {
	data, err := os.ReadFile(path)
	if err != nil {
		die("%v", err)
	}
	var g genDef
	if err := json.Unmarshal(data, &g); err != nil {
		die("parse %s: %v", path, err)
	}
	return &g
}

func printSorted(m map[string]bool) {
	paths := make([]string, 0, len(m))
	for p := range m {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	fmt.Print(strings.Join(paths, " "))
}

func die(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "go-deps: "+format+"\n", args...)
	os.Exit(1)
}
