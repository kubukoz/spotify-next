#compdef spotify-next

function _sn() {
  local -a cmds

  cmds=(
    'login:Log into Spotify'
    'skip:Skip to next track without any changes'
    'drop:Drop current track from the current playlist and skip to the next track'
    'forward:Fast forward the current track by a percentage of its length (10% by default)'
    'switch:Switch device (Spotify/Sonos)'
    'move:Move song to playlist A'
    's:alias for `skip`'
    'd:alias for `drop`'
    'f:alias for `forward`'
    'w:alias for `switch`'
    'm:alias for `move`'
    'repl:Run application in interactive mode'
  )

  _arguments "1: :{_describe 'command' cmds}" '*:: :->args'

  local help=( '--help[Display this help text.]' )
  local version=( {--version,-v}'[Print the version number and exit.]' )

  case $words[1] in
    login)
      _arguments -C $help
      ;;

    drop)
      _arguments -C $help
      ;;

    skip)
      _arguments -C $help
      ;;

    forward)
      _arguments -C $help
      ;;

    move)
      _arguments -C $help
      ;;

    switch)
      _arguments -C $help
      ;;

    s)
      _arguments -C $help
      ;;

    d)
      _arguments -C $help
      ;;

    f)
      _arguments -C $help
      ;;

    j)
      _arguments -C $help
      ;;

    w)
      _arguments -C $help
      ;;

    m)
      _arguments -C $help
      ;;

    repl)
      _arguments -C $help
      ;;

    *)
      _arguments -C $help $version
      ;;

  esac

}

_sn "$@"
