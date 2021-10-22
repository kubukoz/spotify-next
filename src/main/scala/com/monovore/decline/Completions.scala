package com.monovore.decline

import com.monovore.decline.Opts
import cats.implicits.*
import cats.arrow.Choice
import cats.Alternative
import cats.data.NonEmptyList
import cats.Id
import cats.catsInstancesForId

extension (s: String)

  def indent(levels: Int): String = s
    .linesWithSeparators
    .map {
      case l if l.trim.isEmpty => l
      case l                   => " " * levels * 2 + l
    }
    .mkString

  def surround(char: Char): String = s"$char$s$char"

extension [A](a: A) def asId: Id[A] = a

enum OptionsTree {
  case Union(left: OptionsTree, right: OptionsTree)
  case Both(left: OptionsTree, right: OptionsTree)
  case Command(name: String, header: String, options: OptionsTree)
  case Option(opt: Opt[?], repeated: Boolean)
  case HelpFlag(flag: OptionsTree)
  case Unit

  def render: String = this match {
    case Union(left, right)             =>
      s"""Union(
      |${("left = " + left.render).indent(1)},
      |${("right = " + right.render).indent(1)}
      |)""".stripMargin
    case Both(left, right)              =>
      s"""Both(
      |${("left = " + left.render).indent(1)},
      |${("right = " + right.render).indent(1)}
      |)""".stripMargin
    case Command(name, header, options) =>
      s"""Command(
      |  name = $name,
      |  header = $header,
      |${("options = " + options.render).indent(1)}
      |)""".stripMargin
    case Option(opt, repeated)          =>
      s"""Option(
      |  opt = $opt,
      |  repeated = $repeated
      |)""".stripMargin
    case HelpFlag(flag)                 =>
      s"""HelpFlag(
      |${("flag = " + flag.render).indent(1)}
      |)""".stripMargin
    case Unit                           =>
      "Unit"
  }

}

object Completions extends App {

  val spotifyNext = {

    object Choice {
      val ffOpts = Opts.argument[Int]("step").withDefault(10).map(_ => ())

      val opts: Opts[Unit] =
        NonEmptyList
          .of[Opts[Unit]](
            Opts.subcommand("login", "Log into Spotify")(Opts(())),
            Opts.subcommand("skip", "Skip to next track without any changes")(Opts(())),
            Opts.subcommand("drop", "Drop current track from the current playlist and skip to the next track")(
              Opts(())
            ),
            Opts.subcommand("forward", "Fast forward the current track by a percentage of its length (10% by default)")(
              ffOpts
            ),
            Opts.subcommand("jump", "Fast forward the current track to the next section")(Opts(())),
            Opts.subcommand("s", "Alias for `skip`")(Opts(())),
            Opts.subcommand("d", "Alias for `drop`")(Opts(())),
            Opts.subcommand("f", "Alias for `forward`")(ffOpts),
            Opts.subcommand("j", "Alias for `jump`")(Opts(())),
            Opts.subcommand("repl", "Run application in interactive mode")(
              (
                Opts.argument[String]("command").map(_ => ()) <+>
                  Opts.option[String]("user", "The user running the command", "u", "u") <+>
                  Opts.flag("quiet", "Whether to run the command without output", "q")
              ).void
            ),
            (
              Opts.option[Int]("rows", "The amount of rows in the table", "r", "rows") <+>
                Opts.option[String]("indexName", "The name of the index") <+>
                Opts.flag("immutable", "Whether the table is immutable", "i") <+>
                Opts.flag("mutable", "Whether the table is mutable", "m")
            ).void
          )
          .reduceK
    }

    Choice.opts
  }

  val singleCommand =
    Opts.argument[String]("tableName") <+>
      Opts.option[Int]("rows", "The amount of rows in the table", "r", "rows") <+>
      Opts.option[String]("indexName", "The name of the index") <+> (
        Opts.flag("immutable", "Whether the table is immutable", "i") <+>
          Opts.flag("mutable", "Whether the table is mutable", "m")
      )

  val example = spotifyNext
  println(genCompletions("spotify-next", example).render)
  // println(convert(example).render)

  def convert(opts: Opts[Any]): OptionsTree = {
    import Opts.*

    opts match {
      case OrElse(a, b)                     => OptionsTree.Union(convert(a), convert(b))
      case Subcommand(a)                    =>
        OptionsTree.Command(
          a.name,
          a.header,
          convert(a.options)
        )
      case Opts.Repeated(opt)               => OptionsTree.Option(opt, true)
      case Opts.Single(opt)                 => OptionsTree.Option(opt, false)
      case Opts.Validate(opt, _)            => convert(opt)
      case Opts.App(a, b)                   => OptionsTree.Both(convert(a), convert(b))
      case HelpFlag(flag)                   => OptionsTree.HelpFlag(convert(flag))
      case Pure(_) | Env(_, _, _) | Missing => OptionsTree.Unit
    }
  }

  def genCompletions[A](programName: String, opts: Opts[A]): ScriptNode = {
    val alias = s"_$programName"
    import Opts.*

    def findAlts(o: Opts[Any]): List[Command[Any]] = o match {
      case OrElse(a, b)    => findAlts(a) ++ findAlts(b)
      case Subcommand(opt) => List(opt)
      case Pure(_) | Missing | Env(_, _, _) | Repeated(_) | Opts.App(_, _) | Opts.Validate(_, _) | Opts.HelpFlag(_) | Opts.Single(_) => Nil
    }

    def findArgs(o: Opts[Any]): List[Opt[?]] = o match {
      case OrElse(a, b)                                                               => findArgs(a) ++ findArgs(b)
      case Validate(v, _)                                                             => findArgs(v)
      case Single(a)                                                                  => List(a)
      case Repeated(o)                                                                => List(o)
      case App(a, b)                                                                  => findArgs(a) ++ findArgs(b)
      //nested subcommands not supported here yet
      case (Pure(_) | Missing | Env(_, _, _) | Opts.HelpFlag(_) | Opts.Subcommand(_)) =>
        Nil
    }

    val alternatives = findAlts(opts)

    def collectArgs(o: Opt[?]): List[MatchArgsArg] = o match {
      case Opt.Argument(name)       => List(MatchArgsArg.Argument(name))
      case Opt.Flag(names, help, _) =>
        List(MatchArgsArg.Flag(names, help))

      case Opt.Regular(names, _, help, _) => List(MatchArgsArg.Option(names, help))
    }

    val args = alternatives.map { cmd =>
      MatchArgsElem(cmd.name, findArgs(cmd.options).flatMap(collectArgs))
    } :+ {
      //it's a hack, but it works!
      if (alternatives.isEmpty)
        MatchArgsElem("*", findArgs(opts).flatMap(collectArgs))
      else
        MatchArgsElem("*", Nil)
    }

    ScriptNode
      .Template(
        List(
          ScriptNode.CompdefHeader(programName),
          ScriptNode
            .FunctionDef(
              alias,
              ScriptNode.Template(
                alternatives
                  .map { cmd =>
                    DescribeCommandsElem(cmd.name, cmd.header)
                  }
                  .toNel
                  .map(ScriptNode.DescribeCommands.apply)
                  .toList :+
                  ScriptNode.MatchArgs(args)
              )
            )
          // ScriptNode.FunctionCallPassArgs(alias)
        )
      )
  }

}

enum ScriptNode {
  case CompdefHeader(programName: String)
  case DescribeCommands(commands: NonEmptyList[DescribeCommandsElem])
  case MatchArgs(argLists: List[MatchArgsElem])
  case Template(stats: List[ScriptNode])
  case FunctionDef(name: String, body: Template)
  case FunctionCallPassArgs(name: String)

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
        .collect { list =>
          // todo: examples?

          val argStrings = list
            .args
            .flatMap { arg =>
              arg.render
            }
            .map(_.surround('\''))
            .toNel
            .foldMap(_.mkString_(" ", " ", ""))

          s"""${list.command})
           |  _arguments -C$argStrings
           |  ;;""".stripMargin.indent(1)
        }
        .mkString(
          "case $words[1] in\n\n",
          "\n\n",
          // todo: common opts here (help, version)
          "\n\nesac".stripMargin
        )

    case FunctionDef(name, template) =>
      s"function $name(){\n${template.render.indent(1)}\n}"

    case Template(stats) =>
      stats
        .map(_.render)
        .mkString_("\n\n")

    case FunctionCallPassArgs(name) => s"$name \"$$@\""
  }

}

final case class DescribeCommandsElem(command: String, description: String) {
  def render: String = s"'$command:$description'"
}

final case class MatchArgsElem(command: String, args: List[MatchArgsArg])

enum MatchArgsArg {
  case Argument(name: String)
  case Flag(names: List[Opts.Name], description: String)
  case Option(names: List[Opts.Name], description: String)

  def render: List[String] = this match {
    case MatchArgsArg.Argument(name)             => List(":" + name)
    case MatchArgsArg.Flag(names, description)   =>
      names.map(_.toString).map { name =>
        s"$name[$description]"
      }
    case MatchArgsArg.Option(names, description) =>
      names.map(_.toString).map { name =>
        s"$name[$description]"
      }
  }

}
