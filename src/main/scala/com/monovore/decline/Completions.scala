package com.monovore.decline

import com.monovore.decline.Opts
import cats.implicits.*
import cats.arrow.Choice

extension (s: String)

  def indent(levels: Int): String = s
    .linesWithSeparators
    .map {
      case l if l.trim.isEmpty => l
      case l                   => " " * levels * 2 + l
    }
    .mkString

  def surround(char: Char): String = s"$char$s$char"

object Completions extends App {
  println(genCompletions("spotify-next", com.kubukoz.next.Choice.opts <+> com.kubukoz.next.Choice.repl))

  def genCompletions[A](programName: String, opts: Opts[A]): String = {
    val alias = s"_$programName"
    import Opts.*

    def findAlts(o: Opts[Any]): List[Command[Any]] = o match {
      case OrElse(a, b)    => findAlts(a) ++ findAlts(b)
      case Subcommand(opt) => List(opt)
      case _               => Nil
    }

    def findArgs(o: Opts[Any]): List[Opt[?]] = o match {
      case OrElse(a, b)   => findArgs(a) ++ findArgs(b)
      case Validate(v, _) => findArgs(v)
      case Single(a)      => List(a)
      case x              =>
        // println(s"ignoring ${x.getClass.getName}")
        Nil
    }

    val alternatives = findAlts(opts)

    def collectArgs(o: Opt[?]): List[MatchArgsArg] = o match {
      case Opt.Argument(arg) => List(MatchArgsArg(arg))
    }

    ScriptNode
      .Template(
        List(
          ScriptNode.CompdefHeader(programName),
          ScriptNode
            .FunctionDef(
              alias,
              ScriptNode.Template(
                List(
                  ScriptNode
                    .DescribeCommands(
                      alternatives
                        .map { cmd =>
                          DescribeCommandsElem(cmd.name, cmd.header)
                        }
                    ),
                  ScriptNode
                    .MatchArgs(
                      alternatives.map { cmd =>
                        MatchArgsElem(cmd.name, findArgs(cmd.options).flatMap(collectArgs))
                      }
                    )
                )
              )
            )
        )
      )
      .render
  }

}

enum ScriptNode {
  case CompdefHeader(programName: String)
  case DescribeCommands(commands: List[DescribeCommandsElem])
  case MatchArgs(argLists: List[MatchArgsElem])
  case Template(stats: List[ScriptNode])
  case FunctionDef(name: String, body: Template)

  def render: String = this match {
    case CompdefHeader(programName) =>
      s"#compdef $programName"

    case ScriptNode.DescribeCommands(opts) =>
      opts
        .map(_.render.indent(1))
        .mkString_(
          """local -a cmds
            |
            |cmds=(
            |""".stripMargin,
          "\n",
          """
          |)
          |
          |_arguments "1: :{_describe 'command' cmds}" '*:: :->args'""".stripMargin
        )
    case ScriptNode.MatchArgs(lists)       =>
      lists
        .collect {
          case list if list.command == "forward" || list.command == "f" =>
            // |  _arguments -C ${list.args.map(a => (a.text + "[foo]").surround('\"')).mkString(" ")}

            // todo: examples aren't always going to be available. Can we still give a name hint?
            s"""${list.command})
           |  _arguments '::step:(10 20 50)'
           |  ;;""".stripMargin.indent(1)
        }
        .mkString(
          "case $words[1] in\n\n",
          "\n\n",
          // todo: common opts here (help, version)
          """
            |
            |  *)
            |    _arguments -C
            |    ;;
            |
            |esac""".stripMargin
        )

    case FunctionDef(name, template) =>
      s"function $name(){\n${template.render.indent(1)}\n}\n"

    case Template(stats) =>
      stats
        .map(_.render)
        .mkString_("\n\n")
  }

}

final case class DescribeCommandsElem(command: String, description: String) {
  def render: String = s"'$command:$description'"
}

final case class MatchArgsElem(command: String, args: List[MatchArgsArg])

final case class MatchArgsArg(text: String)
