# BlindExplorer release notes

## Version 0.2.0

- Treker is now called BlindExplorer. The new name is on your home screen, in the notification, and in everything the app says. It installs over Treker, and your maps, favorites and settings stay as they were.

## Version 0.1.11

- Fixed: closing Treker from the recent apps list now stops it completely, the same as Stop Treker in the notification. It still keeps running when the screen is off or you switch to another app.
- Your exact house number indoors: Treker now uses a real address point out to about 160 feet away, preferring houses on the street you're on, so GPS drifting indoors no longer turns 1303 into 1301.
- Maps update themselves: when Treker starts, it checks for newer builds of your maps. On Wi-Fi it downloads them by itself and tells you; on mobile data it tells you one is ready. Offline maps now says whether each map is up to date.

## Version 0.1.10

- Fixed: exact house numbers from the new address points were sometimes in capitals, like EMILY MORGAN Circle, which a screen reader could spell out. They're now said normally: 1303 Emily Morgan Circle. The Texas map now has these address points; update it in Offline maps to get your exact house number.

## Version 0.1.9

- New Active route tab. While you're being guided, it replaces the Explore tab at the bottom left. It shows where you're going, the next instruction, the distance remaining and traveled, and updates by itself as you move. Its buttons are Repeat instruction, Where am I, Recalculate route, Step-by-step directions, Announcements while guiding, Report a route problem and Stop guidance. Announcements while guiding moved here from settings.
- Where am I now reads: Heading north. Near 1303 Emily Morgan Circle, Richmond. Next intersection in 250 feet. 3 ways, Emily Morgan Circle crossing Southern Place.
- While guided, Where am I says: On route. To. your destination. Traveled, Remaining, the next intersection, then the next instruction.
- Routes start with, for example: Motorized guidance. To. Corner Cafe. Route length, 4.2 miles. Then the first instruction.
- In a vehicle, instructions are said at 2 miles, at 1 mile, Get ready to at 0.2 miles, and at the turn. Continue for and the next instruction now come only after you've made the turn, not before.
- Rerouting no longer tells you to make U-turns. The new route starts in the direction you're already going.
- Intersections depend on how you're travelling. Walking: a beep, then for example 3-way intersection. Main Street crossing Oak Avenue. In a vehicle: just the cross street. Treker tells walking from driving by your speed over two minutes, like StellarTrek, or you can set Travel mode in Announcements and verbosity.

## Version 0.1.8

- The Where am I button is back on the Explore tab, below the GPS line. It, shaking the phone, and the summary when Treker starts all say the same thing, in HumanWare's style, for example: Heading north. 1303 Emily Morgan Circle. Next intersection in 250 feet, 3-way, Emily Morgan Circle crossing Southern Place. Richmond, Fort Bend County, Texas, zip code 7 7 4 0 6.
- Exact house numbers: maps now include real address points, where counties publish them, so Treker can say your exact house number instead of an estimate. Where there are none, it still works the number out from the street's ranges and says about. This needs the rebuilt maps; update your map once it shows a newer build date.

## Version 0.1.7

- New: when Treker starts, it says where you are once, as soon as it knows: street, address, city, zip code and so on, whatever your announcement switches are set to.
- Fixed: Pause no longer stays on after Treker is closed and opened again. Treker always starts unpaused.
- New: city names outside city limits. Many neighborhoods, like Pecan Grove, aren't inside any city's limits, but their mail goes to a city, like Richmond. Treker now uses that mailing city when you're outside city limits.
- New: house numbers almost everywhere. Where the map has no house mapped near you, Treker works out your house number from the Census Bureau's house number ranges for your street and which side of it you're on, and says it with about, for example about 1303 Emily Morgan Circle. This needs the updated maps, which are being rebuilt; download your map again once it shows a new build date.

## Version 0.1.6

- Fixed: street names stopped being announced indoors and in other places where GPS is a little rough. Now Treker names your street whenever it's clearly the nearest one, even with GPS up to about 100 feet off, and still says nothing when two streets are too close to call.
- Better battery life: when you've been still for 2 minutes, and aren't being guided or using What's Around, Treker checks your position every 15 seconds at low power instead of using precise GPS every second. As soon as the phone senses you moving, precise GPS comes straight back. The Explore tab says Saving battery while you're still while this is happening.
- Treker also looks up streets less often when you're not moving.
- Speech rate now goes up to 8 times normal speed, in steps of a half.
- Settings: every setting with several choices now has its own button, saying its current choice, for example Speech engine: Phone's default engine. Pressing it opens the choices on their own screen; Navigate up comes back. Switches and sliders stay on the page. Announce while guiding is also its own button now.

## Version 0.1.5

- Fixed: Treker could name the wrong street, such as a trail behind your house, right after starting or indoors. It was trusting rough first positions. Now nothing is named until GPS has given 5 precise readings in a row, every street name needs a precise reading, and a new street is only announced after 3 precise readings agree.
- Fixed: when you're standing still, a trail or footpath beside you no longer beats the street you're on. Trails and paths are named when you're on them.

## Version 0.1.4

- Removed: the Announce while guiding switches from the Routes tab during guidance. They're in Help and settings, under Announcements and verbosity, only.

## Version 0.1.3

- New: a bar across the top of every screen. It names the screen you're on and has Pause, or Resume when paused, at the top right. On a settings page, Navigate up is at the top left and takes you back to Help and settings.
- Changed: Pause has moved from the Explore and Routes tabs to that top bar, so it's on every screen.
- Removed: the Where am I button. Shake the phone instead, or move through the switches on the Explore tab, which show the latest city, state, street and so on.
- Removed: Announce while moving from the Announcements and verbosity settings, because it's on the Explore tab. Announce while guiding stays there, so you can set it before starting a route.
- Changed: verbosity options now read like Brief, for example: Main Street.
- Removed: the Back to Help and settings button, replaced by Navigate up.

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
