# Echo Show 5 Dashboard v1

Target: checkers / Android 11 / physical 960×480 / density 195 dpi (1.21875 px/dp), font scale 1.0. `DashboardActivity` is the launcher and HOME surface. Existing `MainActivity`, `AssistantService`, Talk button, and voice resources remain in the package; the dashboard does not automatically start voice.

## Data contract

The dashboard reads a schema_version 1 JSON snapshot from a configured HTTPS Worker URL using a revocable **read-only** Bearer token in Settings → Integrations. The token is stored by Butler's Android Keystore facility. No URL/token is bundled. The response is capped at 128 KiB, validated before it atomically replaces the private last-known-good file, and a bad response keeps the previous snapshot. The Clock always uses the device clock. Last Sync taps fetch the already published JSON; the tap never triggers an Actions workflow or advances the commit baseline.

`COMMITS` displays `today.commits_since_snapshot`: the count from the previous *successful scheduled* snapshot's main SHA to the current main HEAD. Manual data regeneration reuses the previous scheduled SHA, and manual display refresh only fetches the Worker. Initial/missing/non-ancestor baseline produces `null`, displayed as `—`. The server alone advances `previous_snapshot_sha`, after a successful scheduled publication.

`PROJECTS` displays `today.projects`: the count of direct `- **ID**` listings in the Vault main `project-overview.md` section `## Active Work - finite Projects` up to the next `##` heading. Waiting listings count; SV-03's canonical frontmatter is untouched. Notes include tracked `.md` files; new notes count distinct added Markdown paths in today's JST main history, excluding renames. Open PRs include Draft. Data aggregation and Cloudflare Worker deployment are separate from this Android change.

Sample shape, with values deliberately unavailable until the private pipeline publishes them:

```json
{
  "schema_version": 1,
  "data_date": "2026-09-27",
  "generated_at": "2026-09-27T23:55:00+09:00",
  "source": {
    "repository": "yzrswork/obsidian-vault",
    "main_sha": "1111111111111111111111111111111111111111",
    "commit_window": {
      "from_sha": "0000000000000000000000000000000000000000",
      "from_generated_at": "2026-09-26T23:55:00+09:00",
      "to_sha": "1111111111111111111111111111111111111111"
    }
  },
  "today": {
    "notes_total": null,
    "new_notes": null,
    "commits_since_snapshot": null,
    "open_prs": {"count": null, "numbers": []},
    "projects": null
  },
  "activity": [],
  "next": {"featured": null, "active": [], "active_total": null}
}
```

`activity` is at most 20 `{id, occurred_at, kind, work_id?, title}` items, where kind is `pr_merged`, `work_note_updated`, or `curated`. `next.featured` is `{work_id?, title, stage?, action}`, and `next.active` is at most four `{work_id, title, stage}` items. The screen shows at most four activities and three active rows. A human must select the featured action; do not infer it from a commit message.

## Device and build verification

The preflight screenshot showed Android's HOME chooser, not Butler. It reported ordinary app bounds 960×421 with a 29 px status bar and 59 px navigation bar. `DashboardActivity` requests layout-behind-bars plus immersive hide; verify its own content bounds after launch. If the content view is smaller, it scales the entire 960×480 design uniformly and centers it. Current navigation hit regions are 148×82 px (121×67 dp); settings is 64×64 px (52.5×52.5 dp); Last Sync is 264×69 px (217×56.6 dp).

On the owner's Windows checkout, keep the existing debug keystore so `adb install -r` preserves app data. Do not install an APK signed in another environment over the existing package. The full build gate is `gradlew.bat assembleDebug testDebugUnitTest`. Verify on-device CLOCK, TODAY, NEXT, Japanese text, weather fallback, stale/offline last-known-good, settings access, `dumpsys window` immersive bounds and screenshot legibility. The bundled night photograph is static; Settings' selected background overrides it. The existing weather-linked animated background option applies to the preserved legacy screen, not the dashboard.
