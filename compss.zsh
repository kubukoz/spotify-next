#compdef spotify-next

function _spotify-next(){
  case $words[1] in

    *)
      _arguments -C ':tableName[foo]' '--rows[The amount of rows in the table]' '-r[The amount of rows in the table]' '--indexName[The name of the index]' '--immutable[Whether the table is immutable]' '-i[Whether the table is immutable]' '--mutable[Whether the table is mutable]' '-m[Whether the table is mutable]'
      ;;

  esac
}
