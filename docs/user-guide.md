# Treker user guide

Treker is a GPS app for blind people on Android. It tells you where you are as you move, lets you point your phone to hear what's around you, lets you walk the streets virtually, and guides you turn by turn walking, driving or by public transit.

Treker works offline. Its maps come from OpenStreetMap and are downloaded to your phone, one region at a time, so live exploration, What's Around, map browsing, search and walking and driving guidance all work without an internet connection. Only public transit trips need the internet.

There are five tabs across the bottom of the screen, from left to right: Explore, Map browsing, Routes, Favorites, and Help and settings.

# Getting started

The first time you open Treker, it asks for permission to use your location and to show notifications. Allow both.

Next, download the map for your area. Go to the Help and settings tab, find Offline maps, and choose the map that says it covers where you are. Maps are large, so use Wi-Fi. You can keep using your phone while it downloads, and Treker says when the map is ready. If there's no map for where you are, the Explore tab says so and has a Get the offline map button.

Live exploration starts straight away and keeps running when the screen is off or you're in another app. While it runs, Android shows a Treker notification. The notification has a Stop Treker button that stops GPS and live exploration until you open Treker again.

# Explore tab

What's Around is the first thing on the Explore tab. Switch it on, then point the top of your phone in any direction, holding it flat or upright. Treker says the place or intersection in that direction and how far away it is. Turn slowly to hear everything around you. Switch it off when you're done, because it uses the compass all the time it's on.

Live exploration announces information as you move. Switch it off to pause the announcements. GPS keeps running so the other features still work.

Below that is a line saying how good the GPS signal is.

- Where am I says everything Treker knows about where you are, whatever announcements you have switched on.
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
- Direction of travel
- Places you pass

Country, state, county, city, neighborhood, zip code and street address are announced when they change. Street name is announced when you move onto a different street. Approaching intersections are announced when an intersection is ahead of you in the direction you're walking. Places you pass are announced when you come within about 35 meters of them. Everything is looked up in the offline map, so live exploration works without an internet connection.

Recent announcements, at the bottom of the Explore tab, lists the last things Treker said, newest first.

# Map browsing tab

Map browsing lets you walk the streets virtually, one intersection at a time. It starts from where you are, or from a place you chose with the Virtual map exploration action.

Touch the large map browsing area, then:

- Swipe up to move forward to the next intersection.
- Swipe down to turn around.
- Swipe left to turn onto the street to your left.
- Swipe right to turn onto the street to your right.
- Tap to hear where you are and which way you're facing.

After each move Treker says where you are, which way you're facing, and what the next intersection is and how far.

On Android 14 and later, Treker asks the screen reader to let your swipes go straight through to the map browsing area. If your screen reader doesn't do that, the same moves are in the screen reader's actions menu on the map browsing area: Move forward, Turn around, Turn left, Turn right and Where am I.

Start from my location moves you back to where you really are.

# Routes tab

Type an address or a place name in the single Address or place name field, then press Search, or the search key on the keyboard. A whole address goes in the one field, for example 123 Main Street, Springfield, Illinois. You can also type a business name or a kind of place, like coffee. Results are listed with their distance from you. Each result has the place actions below.

While guidance is running, the top of the Routes tab shows the current instruction and has Repeat instruction and Stop guidance buttons, followed by the list of steps.

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

- When the GPS signal is weak, worse than about 25 meters, Treker says GPS signal weak and pauses street, intersection, address and place announcements until the signal is good again. It says GPS signal good again when it's back. The Explore tab always shows how accurate your position is.
- At a corner, Treker names the street you're walking along, not the cross street that happens to be a little closer.
- A divided road, with separate lanes each way, is announced as one intersection, not two.
- Zip codes come from the U.S. Census Bureau's zip code areas, so they're right even where no one has mapped addresses.
- The nearest street address is taken from the street you're on.
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

# Help and settings tab

At the top are Open user guide in browser and Open release notes in browser.

Next is Offline maps, then Map reports and fixes, described above.

Offline maps: Maps you've downloaded each have a Delete button. Below them is every map you can download, with the one covering where you are first. Each says how big it is. A map you already have says Update instead, with the date it was built; updating gets the newest OpenStreetMap data.

Then the settings:

- Use meters instead of feet.
- Directions as clock face. On, directions are like 2 o'clock, relative to the way you're walking or pointing. Off, directions are compass directions like northeast.
- Vibrate in What's Around: a short vibration each time What's Around finds something new.
- Practice mode: walking and driving guidance move along the route by themselves, so you can try guidance at home.
- Speech speed.
- What's Around distance: how far What's Around looks, from 50 to 500 meters.

This user guide is also shown on this tab, below the settings.

# Where the information comes from

Map data © OpenStreetMap contributors, available under the Open Database License. Streets, intersections, places, addresses and the areas you're in all come from OpenStreetMap. Routes are made on your phone by the Valhalla routing engine. Public transit trips come from Transitous, a free community service using open timetable data; its data sources are listed at transitous.org/sources, and the Help and settings tab has a button that opens that page.

Because the information comes from OpenStreetMap, which volunteers keep up to date, some places or addresses may be missing, especially outside cities.
