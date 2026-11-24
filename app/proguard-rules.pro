# The Navigation SDK ships its own consumer rules. Keep our nav-update service by name,
# because the SDK starts it from the class name we hand it.
-keep class com.willwalsh.treker.nav.NavUpdatesService { *; }
