# Subscriptions-only mode

This opt-in mode uses a local, administrator-controlled subscription list.
Search runs only inside those channels. Every candidate video must have a matching
video ID and an approved channel ID in the resolved player response before any
stream opens, including videos supplied by links, queues, casting or autoplay.

Create `subscriptions-only.json` in the app's private `files/` directory or its
app-specific external files directory, then create `.subscriptions_only.lock`
in either location. The configuration has this shape:

```json
{"channels": [{"id": "UCAAAAAAAAAAAAAAAAAAAAAA"}]}
```

Replace the example ID with a canonical YouTube channel ID. An administrator can
import the approved subscriptions from another client; no Google sign-in is
needed for the local list. Channel names and images are resolved by ID using the
app's existing metadata lookup and cached for offline display. An optional `name`
provides a fallback before metadata is available. Display metadata never changes
which IDs are approved. Missing, malformed or empty configuration permits no
channels while the lock exists. An internal configuration takes precedence over
an external one. Either lock file enables the restriction. External activation
is recorded privately and paired with a generated storage identity marker before
the external configuration is used, so unavailable,
unmounted or unreadable external storage cannot unlock the app. If only the
external configuration exists, playback stays blocked until it becomes readable.

The subscriptions screen lists the approved channels. Search results retain each
channel's own continuation, with up to three channel queries in flight. Global
search suggestions and filters are unavailable in this mode. Subscription menus,
account selection and unfiltered discovery are hidden or blocked. App backup
restore is blocked while the lock exists, so it cannot replace the approved list.

For a debuggable build, use authorized ADB `run-as <package>` to write the private
files. A release build can use its app-specific external directory where the
Android shell has access. For external provisioning, open and verify the
approved channel list once while that storage is available and writable. An
unobserved external lock cannot be distinguished from an ordinary installation
when its storage is absent.
Force-stop and reopen the app after changing the list or lock, so existing
screens are rebuilt. Remove both lock files to restore ordinary mode. For external
activation, do this while the original storage is mounted and readable. The private
record is then cleared automatically. Keep the generated
`.subscriptions_only.storage` marker on that storage; a replacement volume or
missing identity marker does not count as removing the lock. Approved IDs are also
rechecked for new searches and playback.

This app restriction does not protect against an administrator, root access,
uninstalling the app or clearing its data. Configure Android profile restrictions
separately. No device rooting, microphone permission or player-engine modification
is required by this feature.
