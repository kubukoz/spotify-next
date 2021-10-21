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

    forward)
      _arguments '::step:(10 20 50)'
      ;;

    f)
      _arguments '::step:(10 20 50)'
      ;;

    *)
      _arguments -C
      ;;

  esac
}

