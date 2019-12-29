# spotify-next

[![License](http://img.shields.io/:license-Apache%202-green.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)

A small program making it easier to filter out music on Spotify.

## Usage

Create an application in the Spotify API dashboard. Add a redirect URI pointing to `http://localhost:port`, where `port` is a port of your choice. You'll need to configure the port in the config file.

## Installation

1. `sbt stage`

1. Create a file in your home directory, named `.spotify-next.json`:

  ```json
  {
    "clientId": "(your app's client id)",
    "clientSecret": "(your app's secret)",
    "loginPort": 4321,
    "token": ""
  }
  ```

The token can be empty for now, it'll be replaced with a real one later.

## How to use

```
$ ./target/universal/stage/bin/spotify-next --help

Usage:
    spotify-next login
    spotify-next next

Gather great music

Options and flags:
    --help
        Display this help text.

Subcommands:
    login
        Request login URL
    next
        Skip to next track without any changes
```

You can run the login command to be prompted for authorization, or you can rely on the fallback mechanism of all API calls - when one fails with 401 Unauthorized, you'll see the response and be prompted to log in. The call will be retried once after you successfully log in.

The application automatically saves the token to the configuration file after successful logins.
