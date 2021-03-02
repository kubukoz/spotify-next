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

val addCompilerPlugins = libraryDependencies ++= {
  if (scalaVersion.value.startsWith("2"))
    List(
      crossPlugin("org.typelevel" % "kind-projector" % "0.11.3"),
      crossPlugin("com.github.cb372" % "scala-typed-holes" % "0.1.7"),
      //gonna regret this one huh
      compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
    )
  else Nil
}

val addVersionSpecificScalacSettings = scalacOptions ++= {
  if (scalaVersion.value.startsWith("2")) Nil
  else List("-Ykind-projector")
}

val commonSettings = Seq(
  scalaVersion := "2.13.4",
  scalacOptions -= "-Xfatal-warnings",
  scalacOptions ++= Seq("-Ymacro-annotations"),
  libraryDependencies ++= Seq(
    "org.typelevel" %%% "cats-effect" % "2.3.1"
  ),
  addCompilerPlugins,
  addVersionSpecificScalacSettings
)

val core = project
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)

val front = project
  .enablePlugins(ScalaJSPlugin)
  .enablePlugins(ScalaJSBundlerPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "me.shadaj" %%% "slinky-core" % "0.6.7",
      "me.shadaj" %%% "slinky-web" % "0.6.7",
      "me.shadaj" %%% "slinky-hot" % "0.6.7",
      "me.shadaj" %%% "slinky-styled-components" % "0.1.0+9-38941d55"
    ),
    //
    fastOptJS / webpackConfigFile := Some(baseDirectory.value / "webpack" / "webpack-fastopt.config.js"),
    fullOptJS / webpackConfigFile := Some(baseDirectory.value / "webpack" / "webpack-opt.config.js"),
    //
    webpackResources := baseDirectory.value / "webpack" * "*",
    fastOptJS / webpackDevServerExtraArgs := Seq("--inline", "--hot"),
    fastOptJS / webpackBundlingMode := BundlingMode.LibraryOnly(),
    webpack / version := "4.29.6",
    startWebpackDevServer / version := "3.3.0",
    Compile / npmDependencies ++= Seq(
      "react" -> "16.11.0",
      "react-dom" -> "16.11.0",
      "react-proxy" -> "1.1.8",
      "source-map-support" -> "0.5.19",
      "styled-components" -> "3.4.10"
    ),
    Compile / npmDevDependencies ++= Seq(
      "file-loader" -> "3.0.1",
      "style-loader" -> "0.23.1",
      "css-loader" -> "2.1.1",
      "html-webpack-plugin" -> "3.2.0",
      "copy-webpack-plugin" -> "5.0.2",
      "webpack-merge" -> "4.2.1"
    ),
    addCommandAlias("dev", ";front/fastOptJS::startWebpackDevServer;~front/fastOptJS"),
    addCommandAlias("build", "front/fullOptJS::webpack")
  )
  .dependsOn(core)

val next =
  project
    .in(file("."))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= Seq(
        // no macros
        "org.typelevel" %% "cats-mtl" % "1.1.2",
        "dev.profunktor" %% "console4cats" % "0.8.1",
        "com.monovore" %% "decline-effect" % "1.3.0",
        "org.http4s" %% "http4s-dsl" % "0.21.20",
        "org.http4s" %% "http4s-blaze-server" % "0.21.20",
        "org.http4s" %% "http4s-blaze-client" % "0.21.20",
        "org.http4s" %% "http4s-circe" % "0.21.20",
        "ch.qos.logback" % "logback-classic" % "1.2.3",
        "io.circe" %% "circe-parser" % "0.13.0",
        "io.circe" %% "circe-literal" % "0.13.0",
        // yes macros
        "com.github.julien-truffaut" %% "monocle-macro" % "3.0.0-M2"
      )
    )
    .settings(name := "spotify-next")
    .enablePlugins(JavaAppPackaging)
    .enablePlugins(GraalVMNativeImagePlugin)
    .dependsOn(core)
    .aggregate(core, front)
