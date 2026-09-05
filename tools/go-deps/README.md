# go-deps

Manages code-generation metadata via `*.gen.json` sidecar files, so a
Makefile can treat "run the generator" as an ordinary file target with real
prerequisites instead of a manually-maintained input list that inevitably
drifts out of date.

Ported from [`../../../morsel/cmd/go-deps`](../../../morsel/cmd/go-deps) —
that project's version is the fuller original (it also has directory-mode
dependency collection for computing a whole binary's build inputs, and a
`//go:generate` linter); this copy only carries the two subcommands
panopticon's root `Makefile` actually drives. If a fix belongs here, it
probably belongs there too.

## The sidecar format

A `*.gen.json` file next to (or above) the code it generates:

```json
{
  "cmd": "go run ../../../tools/dbstore generate --project db.dbstore.json",
  "inputs": ["db.dbstore.json", "schemas", "queries/*.sql"],
  "outputs": ["queries"]
}
```

- `cmd` — the generator command, space-split (no shell interpretation, no
  quoted arguments with spaces). Run with its working directory set to the
  sidecar's own directory.
- `inputs` — paths, directories, or globs (relative to the sidecar's own
  directory) that should invalidate the stamp when they change. Directories
  are expanded recursively.
- `outputs` — informational; not read by `go-deps` itself, but a sidecar
  that generates something ought to say what.

## Subcommands

```
go-deps get <file.gen.json>
    Prints the sidecar's own path plus every expanded input path,
    space-separated - a Makefile target's prerequisite list.

go-deps gen <file.gen.json>
    Runs the declared cmd (cwd = the sidecar's directory), then touches
    <file.gen.json>.stamp.
```

## How the root Makefile uses it

```make
_GEN_JSON_FILES := $(shell find controller -name '*.gen.json' 2>/dev/null)
_GEN_STAMP_FILES := $(addsuffix .stamp,$(_GEN_JSON_FILES))

generate: $(_GEN_STAMP_FILES)

define gen-stamp-rule
$(1): $(shell go run ./tools/go-deps get $(1:.stamp=))
	go run ./tools/go-deps gen $(1:.stamp=)
endef
$(foreach s,$(_GEN_STAMP_FILES),$(eval $(call gen-stamp-rule,$(s))))
```

Each `.gen.json` gets its own real Make file-target (`<file>.gen.json.stamp`)
whose prerequisites are computed by `go-deps get`. Make's own mtime
comparison then does the actual "is this stale?" check — `go-deps` doesn't
need to reimplement that. `.gen.json.stamp` files are gitignored
(`**/*.gen.json.stamp`); the generated code they gate is committed normally.
