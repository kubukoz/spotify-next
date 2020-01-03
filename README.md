# spotify-next

[![License](http://img.shields.io/:license-Apache%202-green.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)

A small program making it easier to filter out music on Spotify.

[![asciicast](demo.gif)](https://asciinema.org/a/LuppXgCyKwvpRAtO14yTh8Y2A)

## Installation

1. Create an application in the Spotify API dashboard. Add a redirect URI pointing to `http://localhost:4321/login`.
The port `4321` is configured in the `.spotify-next.json` config file (see [usage](#usage)).

1. If you have [coursier](https://get-coursier.io), this will install the app in your current working directory:

```bash
coursier bootstrap com.kubukoz:spotify-next_2.13:1.0.1 -o spotify-next

# now you can run the app like this:
./spotify-next --help
```

## Build from source

Alternatively, if you want a fresh-out-of-the-oven version, you can build it from source:

1. `sbt stage`

## Usage

The application requires some configuration (e.g. the client ID for the Spotify Web API).
It's stored in a file at `~/.spotify-next.json`.
When you first run the application, or if that file is deleted, the application will ask and attempt to create one.

The configuration defines the port for the embedded HTTP server used for authentication. The server server will only start when the login flow is triggered, and stop afterwards.

```
$ ./spotify-next --help

Usage:
    spotify-next login
    spotify-next skip
    spotify-next drop
    spotify-next forward
    spotify-next s
    spotify-next d
    spotify-next f
    spotify-next repl

spotify-next: Gather great music.

Options and flags:
    --help
        Display this help text.

Subcommands:
    login
        Log into Spotify
    skip
        Skip to next track without any changes
    drop
        Drop current track from the current playlist and skip to the next track
    forward
        Fast forward the current track by a percentage of its length (10% by default)
    s
        Alias for `skip`
    d
        Alias for `drop`
    f
        Alias for `forward`
    repl
        Run application in interactive mode
```

You can run the login command to be prompted for authorization, or you can rely on the fallback mechanism of all API calls - when one fails with 401 Unauthorized, you'll see the response and be prompted to log in. The call will be retried once after you successfully log in.

The application automatically saves the token to the configuration file after successful logins.
