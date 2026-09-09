# Architecture decision records

Lightweight ADRs (Nygard style: **Status · Context · Decision · Consequences**), grouped
thematically rather than one-per-micro-decision. Each file collects the settled calls for one
area, with the individual decisions as sub-sections.

These were recorded **retrospectively**, consolidated from the design-session notes that
preceded implementation (dated 2026-09-04 … 2026-09-07) and from the two application READMEs.
Where a decision has since been implemented, superseded, or partly deferred, the record says so.

| # | Area |
|---|---|
| [0001](0001-repository-and-build.md) | Repository, modules, and build infrastructure |
| [0002](0002-http-api-surface-and-auth.md) | Phone HTTP API — surface and auth model |
| [0003](0003-pairing-and-unpairing.md) | Pairing and unpairing |
| [0004](0004-recording-pipeline.md) | Recording pipeline architecture |
| [0005](0005-motion-detection.md) | Motion detection scope |
| [0006](0006-segments-clips-and-tombstones.md) | Segments, clips, and the tombstone model |
| [0007](0007-live-preview-plain-hls.md) | Live preview — plain HLS |
| [0008](0008-camera-control-and-multi-camera.md) | Camera selection and manual controls |
| [0009](0009-calibration-model.md) | Calibration model |
| [0010](0010-mode-state-machine.md) | Phone mode state machine |
| [0011](0011-controller-runtime-and-state.md) | Controller runtime and local state |
| [0012](0012-ux-shape.md) | UX shape — phone and controller |

## Adding a decision

Append a sub-section to the closest existing file, or add `00NN-<area>.md` for a genuinely new
area and link it above. Keep the four headings. When something here is overturned, mark the
sub-section **Superseded by …** rather than deleting it.
