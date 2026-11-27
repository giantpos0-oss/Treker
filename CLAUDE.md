# Working on BlindExplorer

## Keep the user guide and release notes current (every change)

The user guide and release notes are the single source for help, in the app and in the browser:

- `docs/user-guide.md` is shown on the Help tab (bundled into the app as an asset) and opened in
  the browser by "Open user guide in browser" at the top of the Help tab.
- `docs/release-notes.md` is opened by "Open release notes in browser".

With every change people will notice (a new feature, a changed button, setting, prompt or
behaviour, a removed feature):

1. Update `docs/user-guide.md` so it describes the app exactly as it now is. Use the exact
   on-screen wording for buttons, settings and spoken prompts. Remove anything no longer true.
2. Bump `versionName` and `versionCode` in `app/build.gradle.kts`, and add a
   `## Version x.y.z` section at the top of `docs/release-notes.md` in plain language.
   The build fails if the current version has no release notes.

Writing style for both files: plain English for blind users, short paragraphs, `#` headings and
`-` bullet points only. No tables, no images, no nested lists, no HTML. Bold, code marks and
links are stripped by the in-app reader, so don't rely on them for meaning.

## Map data

BlindExplorer is fully OpenStreetMap and offline. tools/build_region.py builds a region's street
database; .github/workflows/maps.yml builds every region in tools/us-regions.json (or one, via
the "only" input) plus Valhalla routing tiles, publishes them on the "maps" release, and rebuilds
regions.json, the catalog the app reads. Runs monthly. License: GPL-3.0. Public transit uses Transitous (online,
non-commercial, open-source apps only, send a User-Agent with contact info). No Google services.
