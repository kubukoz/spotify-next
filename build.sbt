inThisBuild(
  List(
    organization := "com.kubukoz",
    homepage := Some(url("https://github.com/kubukoz/spotify-next")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "kubukoz",
        "Jakub Kozłowski",
        "kubukoz@gmail.com",
        url("https://kubukoz.com")
      )
    )
  )
)

def crossPlugin(x: sbt.librarymanagement.ModuleID) = compilerPlugin(x.cross(CrossVersion.full))

val compilerPlugins = List(
  crossPlugin("org.typelevel" % "kind-projector" % "0.11.0"),
  crossPlugin("com.github.cb372" % "scala-typed-holes" % "0.1.5"),
  crossPlugin("com.kubukoz" % "better-tostring" % "0.2.4"),
  compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
)

val commonSettings = Seq(
  scalaVersion := "2.13.3",
  scalacOptions -= "-Xfatal-warnings",
  scalacOptions ++= Seq(
    "-Ymacro-annotations",
    "-Yimports:" ++ List(
      "scala",
      "scala.Predef",
      "cats",
      "cats.implicits",
      "cats.effect",
      "cats.effect.implicits",
      "cats.effect.concurrent"
    ).mkString(",")
  ),
  name := "spotify-next",
  updateOptions := updateOptions.value.withGigahorse(false),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "simulacrum" % "1.0.1",
    "dev.profunktor" %% "console4cats" % "0.8.1",
    "com.monovore" %% "decline-effect" % "1.3.0",
    "org.typelevel" %% "cats-effect" % "2.2.0",
    "org.http4s" %% "http4s-dsl" % "0.21.9",
    "org.http4s" %% "http4s-blaze-server" % "0.21.9",
    "org.http4s" %% "http4s-blaze-client" % "0.21.9",
    "org.http4s" %% "http4s-circe" % "0.21.9",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "io.chrisdavenport" %% "log4cats-slf4j" % "1.1.1",
    "org.typelevel" %% "kittens" % "2.1.0",
    "org.typelevel" %% "cats-tagless-macros" % "0.11",
    "io.circe" %% "circe-fs2" % "0.13.0",
    "io.circe" %% "circe-literal" % "0.13.0",
    "io.circe" %% "circe-generic-extras" % "0.13.0",
    "com.olegpy" %% "meow-mtl-core" % "0.4.1",
    "io.estatico" %% "newtype" % "0.4.4",
    "org.scalatest" %% "scalatest" % "3.2.3" % Test
  ) ++ compilerPlugins
)

val next =
  project.in(file(".")).settings(commonSettings).enablePlugins(JavaAppPackaging).enablePlugins(GraalVMNativeImagePlugin)
