#compdef spotify-next

function _spotify-next(){
  local -a cmds

  cmds=(
    'login:Log into Spotify'
    'skip:Skip to next track without any changes'
    'drop:Drop current track from the current playlist and skip to the next track'
    'forward:Fast forward the current track by a percentage of its length (10% by default)'
    'jump:Fast forward the current track to the next section'
    's:Alias for `skip`'
    'd:Alias for `drop`'
    'f:Alias for `forward`'
    'j:Alias for `jump`'
    'repl:Run application in interactive mode'
  )

  _arguments "1: :{_describe 'command' cmds}" '*:: :->args'

  case $words[1] in

    login)
      _arguments -C
      ;;

    skip)
      _arguments -C
      ;;

    drop)
      _arguments -C
      ;;

    forward)
      _arguments -C ':step'
      ;;

    jump)
      _arguments -C
      ;;

    s)
      _arguments -C
      ;;

    d)
      _arguments -C
      ;;

    f)
      _arguments -C ':step'
      ;;

    j)
      _arguments -C
      ;;

    repl)
      _arguments -C ':command' '--user[The user running the command]' '-u[The user running the command]' '--quiet[Whether to run the command without output]' '-q[Whether to run the command without output]'
      ;;

    *)
      _arguments -C
      ;;

  esac
}
