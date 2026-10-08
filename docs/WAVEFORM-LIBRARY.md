# Waveform library

The Android library follows the approved interactive prototype in the existing
Compose stack. Cards show name, independent heart/star marks, duration in
`single seconds × repeat · total seconds` form, optional note, rhythm chart,
and replay/detail/delete actions. The example is named `示例波形-波浪`.

## Personal metadata

- `is_liked` signals user feedback to the AI.
- `is_favorite` is the user's personal collection filter.
- `description` is an optional note; new notes and edits allow 120 characters.
- Marks switch quietly, with no success notification. Failed writes remain
  visible and do not pretend that the server accepted a change.
- Detail editing has inline save confirmation and an unsaved-change guard.
- The built-in definition cannot be deleted or replaced. Each authenticated
  user's marks and note override are independent.

## API

All routes use the existing authenticated library principal; clients do not
supply user IDs. Responses have `Cache-Control: no-store`.

- `GET /patterns?offset=0&limit=30&include_steps=true&filter=all&q=`
  returns a typed page. Filters are `all`, `liked`, `favorites`. Filtering and
  name/note search happen before pagination. The built-in appears once.
  `include_steps` defaults to false for older callers; Android opts in to
  avoid a detail request for every visible chart.
- `GET /patterns/{id}` reads exact IDs, including `builtin-wave`.
- `PATCH /patterns/{id}` accepts a nonempty partial metadata object. Unknown
  fields, nulls, string booleans and overlong notes are rejected.
- `DELETE /patterns/{id}` hides the pattern immediately and returns a
  six-second server undo window; the app offers undo for five seconds.
- `POST /patterns/{id}/restore` restores a private deletion within that
  window; conflicts and expired windows return 409.

MCP `get_pattern_preferences` returns typed structured JSON with both marks,
notes and total duration. `list_patterns` and `get_pattern` also expose marks.
No MCP preference-write tool is registered. This is a transport boundary, not
human-only credential enforcement: until the coordinated identity rollout,
REST still uses the same existing bearer/JWT authority as the phone library.

## Storage and upgrade

SQLAlchemy stores live waveforms and metadata in `library_patterns` and
one-time import markers in `library_imports`, in the application database.
Composite user/id keys preserve legacy identifiers without cross-user
collisions. Initialization creates only these two compatibility tables; it
does not migrate or replace live accounts, tokens, OAuth or safety settings.
Alembic revision `0002_waveform_library` describes the same tables for M1
installations. The earlier `patterns` staging table is left untouched.

Each legacy JSON file imports transactionally once. Original files are kept
as rollback input and are no longer written. A malformed file fails visibly
instead of yielding an empty library. Back up the database and legacy files
before upgrade. A rollback to the old JSON implementation cannot preserve
new notes, new waveforms or deletions unless those are exported first.

## Drawing and replay

Charts port the prototype's per-gear and per-suction-mode cadence templates,
raised-cosine joins and shape-preserving cubic Hermite drawing. Rests remain
zero, all repeats share a constant 10dp/second scale, and long charts scroll
horizontally. Sampling density increases for long patterns to prevent fast
cadence aliasing. A faint grid and separate solid channel colors mirror the
prototype. Detail inspection can switch to command-value illustration.

The graph is not calibrated hardware power. Original steps, timing and scale
remain unchanged. Explicit replay fetches and validates a fresh definition;
the existing dispatcher validates again and applies scale exactly once.
Stop invokes the existing real stop action. The progress cursor uses the
phone's actual running job timeline, not browser-style simulated playback;
it is not a physical sensor or a Bluetooth acknowledgement.

Metadata, listing, detail, deletion and undo never issue device commands.

## Validation

- Server unit/contract suite and all seven standalone verification scripts.
- Legacy import and restart, corrupted-input failure, per-user isolation,
  strict partial patches, built-in protection, filtering and undo expiry.
- Android HTTP contracts, geometry invariants and Compose interactions.
- Host-rendered light/dark/Gemini and narrow-screen snapshots. Host tests
  cannot establish physical device behavior or replace phone acceptance.
