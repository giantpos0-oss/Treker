# BlindExplorer

A GPS app for blind people on Android: live exploration that tells you where you are as you move, What's Around (point your phone to hear what's in that direction), virtual map browsing by swiping, and turn-by-turn guidance for walking, driving and public transit.

Download the newest build: https://github.com/giantpos0-oss/Treker/releases/latest/download/Treker.apk

See docs/user-guide.md for how to use it and docs/release-notes.md for what changed.

Everything is OpenStreetMap and works offline: maps are downloaded per region from this repo's "maps" release (built by the "Build offline map" workflow), and routes are made on the phone with Valhalla. Public transit trips come from Transitous (transitous.org).

Offline maps are available for all 50 states, Washington D.C., Puerto Rico and the U.S. Virgin Islands, rebuilt monthly by the "Build offline maps" workflow (regions are listed in tools/us-regions.json).

BlindExplorer is free software, licensed under the GNU General Public License version 3 (see LICENSE). Map data © OpenStreetMap contributors, available under the Open Database License.
