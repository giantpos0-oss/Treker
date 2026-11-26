# Treker user guide

Treker is a GPS app for blind people on Android. It tells you where you are as you move, lets you point your phone to hear what's around you, lets you walk the streets virtually, and guides you turn by turn walking, driving or by public transit.

Treker works offline. Its maps come from OpenStreetMap and are downloaded to your phone, one region at a time, so live exploration, What's Around, map browsing, search and walking and driving guidance all work without an internet connection. Only public transit trips need the internet.

There are five tabs across the bottom of the screen, from left to right: Explore, Map browsing, Routes, Favorites, and Help and settings.

# Getting started

The first time you open Treker, it asks for permission to use your location and to show notifications. Allow both.

Next, download the map for your area. Go to the Help and settings tab, find Offline maps, and choose the map that says it covers where you are. Maps are large, so use Wi-Fi. You can keep using your phone while it downloads, and Treker says when the map is ready. If there's no map for where you are, the Explore tab says so and has a Get the offline map button.

When Treker starts, it says where you are once, as soon as it knows: your street, address, city, zip code and so on, whatever your announcement switches are set to. After that, the switches decide what's announced as things change.

Live exploration starts straight away and keeps running when the screen is off or you're in another app. While it runs, Android shows a Treker notification with two buttons: Pause or resume, which does the same as Pause at the top of the screen, and Stop Treker, which stops GPS, guidance and live exploration until you open Treker again. Both work from the notification shade without opening Treker.

# The top of every screen

Across the top of every screen is a bar. It names the screen you're on and, at the top right, has Pause. Pause pauses live exploration and route guidance together, and Treker stops talking straight away. The button then says Resume. Treker always starts unpaused, even if it was paused when you closed it. GPS keeps running while paused, so the other features still work and guidance keeps following you; when you resume during guidance, Treker says the current instruction.

On a settings page, the top bar also has Navigate up at the top left, which takes you back to Help and settings. Your phone's back gesture does the same.

# Explore tab

What's Around is the first thing on the Explore tab. Switch it on, then point the top of your phone in any direction, holding it flat or upright. Treker says the place or intersection in that direction and how far away it is. Turn slowly to hear everything around you. Switch it off when you're done, because it uses the compass all the time it's on.

Live exploration announces information as you move, all the time, unless you've pressed Pause.

Below that is a line saying how good the GPS signal is.

- Nearest intersection says the closest intersection, its direction and distance.
- Places nearby lists the places around you, nearest first. Each place has actions, described under Place actions below.

# Announce while moving

On the Explore tab, under Announce while moving, each piece of information live exploration can announce has its own switch:

- Country
- State or province
- County
- City or town
- Neighborhood
- Zip code
- Street name
- Nearest street address
- Approaching intersections
- Places you pass

Each switch that's on also shows the latest information for it, so you can check it any time by moving through the switches with your screen reader, for example City: Austin, switch on; or Approaching intersections: Main Street and Oak Avenue, 100 feet, switch on. A switch that's off shows only its name.

Country, state, county, city, neighborhood, zip code and street address are announced when they change. Street name is announced when you move onto a different street. Approaching intersections are announced when an intersection is ahead of you in the direction you're walking. Places you pass are announced when you come within about 35 meters of them. Everything is looked up in the offline map, so live exploration works without an internet connection.

Recent announcements, at the bottom of the Explore tab, lists the last things Treker said, newest first.

# Map browsing tab

Map browsing lets you walk the streets virtually, one intersection at a time. It starts from where you are, or from a place you chose with the Virtual map exploration action.

Touch the large map browsing area, then:

- Swipe up to move forward to the next intersection.
- Swipe down to turn around.
- Swipe left to turn onto the street to your left.
- Swipe right to turn onto the street to your right.
- Tap to hear where you are.

After each move Treker says where you are, the street you're on, and what the next intersection is and how far.

On Android 14 and later, Treker asks the screen reader to let your swipes go straight through to the map browsing area. If your screen reader doesn't do that, the same moves are in the screen reader's actions menu on the map browsing area: Move forward, Turn around, Turn left, Turn right and Where am I.

Start from my location moves you back to where you really are.

# Routes tab

Type an address or a place name in the single Address or place name field, then press Search, or the search key on the keyboard. A whole address goes in the one field, for example 123 Main Street, Springfield, Illinois. You can also type a business name or a kind of place, like coffee. Results are listed with their distance from you. Each result has the place actions below.

While guidance is running, the top of the Routes tab shows the current instruction and has Repeat instruction, Pause, Stop guidance and Report a route problem buttons. Pause is the same as on the Explore tab; Repeat instruction still speaks while paused.

Below them is the list of steps.

What's announced while you're being guided, such as street names and intersections, is set separately from the Explore tab, in Help and settings, under Announcements and verbosity, then Announce while guiding. So you can, for example, hear every intersection while exploring but only street names while following a route.

# Place actions

Every place in Treker, whether a search result, a place nearby or a favorite, has the same actions. With a screen reader, they're in the actions menu on the place. You can also double tap the place to show them as buttons.

- Start in pedestrian mode: walking turn-by-turn guidance.
- Start in motorized mode: driving turn-by-turn guidance, for when you're a passenger or for trips by car.
- Start in public transit mode: walking to the stop, which bus or train to take, and a warning before your stop.
- Virtual map exploration: opens Map browsing starting at that place.
- Add to favorites, or Remove from favorites if it's already saved.

# Guidance

Pedestrian and motorized guidance work offline. Treker says how far it is and about how long it takes, then tells you each turn twice: a heads-up before you reach it, and again when you're there. If you leave the route, Treker says Off route, recalculating, and makes a new route from where you are.

Public transit guidance needs the internet. It tells you each step: walk to the stop, which line to take and which way it's headed, when it leaves, how many stops to ride, and where to get off. Walks to and from stops are guided turn by turn. On the ride, Treker names each stop as you pass it, says when yours is next, and tells you when to get off.

# Favorites tab

Save my current location saves where you are, named by its street address. Places you add from search results or places nearby also appear here, nearest first, with the same place actions.

# Accuracy

Treker is built so it doesn't tell you something wrong with confidence.

- Treker only names streets, intersections, addresses and places when your GPS position is precise to about 30 feet, or the accuracy you choose in Help and settings, under GPS accuracy. When it isn't, Treker waits. If the signal stays weak for 20 seconds, Treker says GPS signal weak; once it's been good again for 15 seconds, GPS signal good again. It says these at most once every 3 minutes, and you can switch them off with Announce when the GPS signal turns weak or good again, in Announcements and verbosity. The Explore tab always shows how accurate your position is.
- Treker asks your phone for its most precise location mode and also listens to the GPS chip directly. On phones with dual-frequency GPS, such as recent Pixels, that's accurate to a few feet out in the open. Positions are smoothed so one wild fix can't throw you across the street, but the accuracy Treker reports is always the GPS's own.
- At the end of a walk, Treker doesn't stop at the end of the street. In the last 150 feet it counts down the distance to the destination itself and says which way it is, like Corner Cafe, 40 feet, ahead and slightly to the left. It only says you've arrived when you're within about 30 feet, or the arrival distance you choose, and GPS is precise enough to be sure. If it isn't, Treker says so instead of announcing an arrival it can't stand behind.
- When Treker starts, it names nothing until GPS has given 5 precise readings in a row, so a rough first position, which can be hundreds of feet off, is never used.
- A new street is only announced once 3 readings in a row agree on it. Indoors, or wherever GPS is a little rough, up to about 100 feet off, a street is still named if it's clearly the nearest one; if two streets are too close to call, Treker says nothing.
- To save battery, when you've been still for 2 minutes and aren't being guided or using What's Around, Treker checks your position every 15 seconds at low power. The moment the phone senses you moving, precise GPS comes straight back. While this is happening, the GPS line on the Explore tab says Saving battery while you're still.
- At a corner, Treker names the street you're walking along, not the cross street that happens to be a little closer.
- A trail or footpath, such as one behind your house, is only named when you're on it; otherwise the street nearby wins.
- A divided road, with separate lanes each way, is announced as one intersection, not two.
- Zip codes come from the U.S. Census Bureau's zip code areas, so they're right even where no one has mapped addresses.
- The nearest street address is taken from the street you're on. Where the map has no house mapped close by, Treker works out your house number from the Census Bureau's house number ranges for your street and which side of it you're on, and says about in front of it, for example about 1303 Emily Morgan Circle, because it's an estimate.
- Outside city limits, as in many neighborhoods, Treker gives the city your mail goes to, like Richmond.
- Every new version of Treker is checked by automatic tests of distances, directions, which street you're on, intersections, turn announcements and noticing when you leave a route. Every map is checked before it's published, so a broken map never reaches your phone.

The map itself is OpenStreetMap, kept up to date by volunteers, so it can still be wrong or out of date in places. When it is, please report it.

# Reporting a map problem

You can report a problem from wherever you notice it:

- On the Explore tab, Report a map problem reports something about where you are.
- On any place, the Report a problem with this place action. It's in the screen reader's actions menu, and in the buttons that appear when you double tap the place.
- In map browsing, the Report a problem here button, or the same action on the map browsing area.
- During guidance, on the Routes tab, Report a route problem.

Choose what's wrong from the list, for example a place is missing, a street name is wrong, or a sidewalk or crossing is missing. If you're correcting a name, type the correct name. You can add more detail if you want. Then choose Send report.

Your report becomes a public note on OpenStreetMap at that spot, including what Treker said there, so local volunteer mappers can see exactly what's wrong and fix it. It doesn't include your name. Once it's fixed, the fix reaches Treker with the next monthly map update. If you're offline, or OpenStreetMap doesn't accept the report right away, Treker keeps it and sends it later.

# Fixing it on your phone straight away

For some problems you don't have to wait. When you report one of these, Treker offers to fix it on your phone straight away, and that's switched on unless you switch it off:

- A place that's closed or gone: Treker stops announcing it.
- A place with the wrong name: Treker uses the name you typed.
- A missing place: Treker adds it where you're standing, with the name you typed, and announces it like any other place.
- A street with the wrong name: Treker uses the name you typed for that street around here.

Your fixes are listed on the Help and settings tab, under Map reports and fixes, each with an Undo button. That section also lists the reports you've sent, each of which opens its OpenStreetMap note so you can see whether it's been fixed, and a button to send any reports still waiting.

# Shake for Where am I

Shake your phone firmly, twice, to hear Where am I: your street, nearest address, city and zip code. You can also find each of these, at any time, by moving through the switches under Announce while moving on the Explore tab. It works with the screen off and while you're in another app, as long as live exploration is running. You can switch it off, or change how hard you have to shake, in Help and settings, under Shake and What's Around.

# Help and settings tab

At the top are Open user guide in browser and Open release notes in browser. Below them, each group of settings has its own button. Each group opens on its own page; Navigate up, at the top left of the screen, or your phone's back gesture, takes you back.

Inside a group, every setting with several choices has its own button, which says the setting and its current choice, for example Speech engine: Phone's default engine. Pressing it opens the choices, as radio buttons, on their own screen; Navigate up takes you back to the group. On-off switches and sliders, such as Avoid stairs or Speech rate, are right on the group's page.

Speech: Treker's own voice, which speaks live exploration, What's Around and guidance. It's separate from your screen reader's voice.

- Speech engine: any speech engine installed on your phone, or the phone's default.
- Voice: the voices in that engine, in your language. On the Voice screen, switch on Show voices for all languages to see the rest.
- Speech rate, from half speed to 8 times normal speed, in steps of a half. How fast it actually goes depends on your speech engine; some engines stop getting faster before 8.
- Pitch and volume.
- Test speech plays a sample announcement.

Announcements and verbosity: how much live exploration says, set separately for streets, intersections, places you pass, and the country, state, county, city and zip code. Each has three levels, and each option says what you'll hear, for example: Brief, for example: Main Street. Detailed intersections say what kind of intersection it is, like a T intersection or a 4-way intersection, and where it is, like ahead. Detailed streets say what kind of street it is, like a residential street or a major road. Announce intersections ahead sets how early intersections are announced, from about 50 feet to about 200 feet. Announce while guiding is here too, as its own button saying how many are on: the same switches as the Explore tab's Announce while moving, used instead of them while route guidance is running, in every travel mode. The switches for while you're exploring are on the Explore tab.

Routes:

- Route type: fastest or shortest distance.
- Walking: avoid stairs, prefer sidewalks and footpaths, avoid alleys, and prefer lit streets. Avoid stairs, prefer sidewalks and avoid alleys are on to start with.
- Driving: avoid highways, avoid toll roads, and avoid ferries.
- Practice mode: walking and driving guidance move along the route by themselves, so you can try guidance at home.

Route settings apply to the next route you start.

GPS accuracy: shows how accurate your position is right now, and has two settings.

- Required GPS accuracy: about 30 feet, which is the default and the most accurate; about 50 feet; or about 80 feet, which works in more places. Between tall buildings, under trees or indoors, GPS often can't reach 30 feet. When it can't, Treker tells you the signal is weak rather than guess.
- Say you've arrived when walking within: about 15, 30 or 50 feet.

Units and directions:

- Distances: feet and miles, yards and miles, or meters and kilometers.
- Directions: ahead, behind, left and right, which is the default, or clock face, like 2 o'clock. Ahead means within about 15 degrees of straight ahead; ahead and slightly to the left or right, up to about 60 degrees; to the left or right, up to about 120 degrees; behind you to the left or right, up to about 165 degrees; and behind you beyond that. Both are relative to the way you're walking. When you've stopped, they're relative to the way the top of your phone points if What's Around is on, otherwise the way you were last walking. If Treker can't tell which way you're facing, it gives the distance without a direction rather than guess. Treker doesn't use compass directions, except that a route's first instruction can say which way to head, like Head southeast on Jane Doe Avenue.

Shake and What's Around: shake for Where am I on or off, how hard to shake (light, medium or firm), vibration, and how far What's Around looks, from 50 to 500 meters.

Offline maps: download, update and delete maps. Maps you've downloaded each have a Delete button. Below them is every map you can download, with the one covering where you are first, and how big it is. A map you already have says Update instead, with the date it was built; updating gets the newest OpenStreetMap data.

Map reports and fixes: described above.

User guide: this guide.

# Where the information comes from

Map data © OpenStreetMap contributors, available under the Open Database License. Streets, intersections, places and addresses come from OpenStreetMap. State, county, city and zip code areas, and the house number ranges used to work out addresses, come from the U.S. Census Bureau. Routes are made on your phone by the Valhalla routing engine. Public transit trips come from Transitous, a free community service using open timetable data; its data sources are listed at transitous.org/sources, and the Help and settings tab has a button that opens that page.

Because the information comes from OpenStreetMap, which volunteers keep up to date, some places or addresses may be missing, especially outside cities.
