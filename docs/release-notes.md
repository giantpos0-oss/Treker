# Treker release notes

## Version 0.1.2

- New: one Pause button pauses live exploration and route guidance together, and Resume brings both back. It's near the top of the Explore tab, on the Routes tab while guidance is running, and in the Treker notification as Pause or resume. While paused, guidance keeps following you silently; when you resume, Treker says the current instruction.
- New: a separate set of announcement switches for while you're being guided, under Announce while guiding on the Routes tab: country, state, county, city, neighborhood, zip code, street name, nearest address, approaching intersections and places you pass. They're used instead of the Explore tab's switches whenever guidance is running, in every travel mode.
- Removed: Mute guidance, replaced by Pause, and the Live exploration switch on the Explore tab, also replaced by Pause.

## Version 0.1.1

- Fixed: Treker kept saying GPS signal weak and GPS signal good again over and over, and could talk so much that the screen reader couldn't be heard. Now the GPS signal is only announced when a change lasts, weak for 20 seconds or good again for 15, and at most once every 3 minutes. You can switch these announcements off in Help and settings, under Announcements and verbosity.
- Fixed: announcements could pile up. Treker no longer repeats the same words within 10 seconds, and never lets more than two announcements wait; newer ones replace older ones.
- New: Mute guidance and Unmute guidance, on the Routes tab while guidance is running, for walking, driving and public transit. Guidance keeps going and the screen keeps updating; Repeat instruction still speaks when you press it.
- New: the Treker notification has Pause or resume announcements and Mute or unmute guidance, as well as Stop Treker.

## Version 0.1.0

The first build of Treker. Everything runs on OpenStreetMap and works offline, except public transit trips.

- Five tabs: Explore, Map browsing, Routes, Favorites, and Help and settings.
- Live exploration runs all the time, with a switch for each thing it announces: country, state, county, city, neighborhood, zip code, street name, nearest address, approaching intersections, direction of travel and places you pass.
- The switches under Announce while moving show the latest information for each one that's on, so you can read it with your screen reader any time.
- What's Around: point your phone to hear places and intersections in that direction.
- Map browsing: swipe up, down, left and right to walk the streets virtually, one intersection at a time.
- Routes: search for an address or place in one field, then start pedestrian, motorized or public transit guidance, explore it virtually, or add it to favorites.
- Favorites, including saving where you are.
- Offline maps: download your area's map in Help and settings. Walking and driving routes are made on your phone by Valhalla and recalculated when you go off route.
- Offline maps for every U.S. state, Washington D.C., Puerto Rico and the U.S. Virgin Islands, refreshed monthly.
- Accuracy: announcements pause when GPS is weak, the street you're walking along wins at corners, divided roads count as one intersection, zip codes come from Census Bureau zip code areas, and automatic tests check the math on every build.
- Report a map problem from the Explore tab, any place, map browsing or guidance. Reports go to OpenStreetMap so volunteers can fix the map, and some fixes take effect on your phone straight away.
- GPS accuracy down to about 30 feet: Treker uses the phone's most precise location mode and the GPS chip directly, smooths out wild fixes, and only names things or says you've arrived when GPS is that precise. The last 150 feet of a walk are guided straight to the destination, with distance and direction.
- Shake the phone for Where am I, even with the screen off.
- Speech settings: speech engine, voice, rate, pitch and volume.
- Verbosity settings: brief, normal or detailed announcements for streets, intersections, places and areas, and how early intersections are announced.
- Route settings: fastest or shortest; avoid stairs, prefer sidewalks, avoid alleys and prefer lit streets when walking; avoid highways, toll roads and ferries when driving.
- Directions as ahead, behind, left and right (the default) or clock face. No compass directions, except a route's first instruction.
- Distances in feet and miles, yards and miles, or meters and kilometers.
- Public transit trips from Transitous, with each stop named as you pass it.
