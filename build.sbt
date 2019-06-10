val zioVersion = "1.0-RC5"
val undertowVersion = "2.0.21.Final"

lazy val IntegrationTest = config("it") extend Test

lazy val commonSettings = Seq(
  scalaVersion := "2.12.8",
  crossScalaVersions := Seq("2.11.12", scalaVersion.value),

  // https://tpolecat.github.io/2017/04/25/scalac-flags.html
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-explaintypes",
    "-feature",
    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-unchecked",
    "-Xcheckinit",
    //    "-Xfatal-warnings",
    "-Xfuture",
    "-Xlint",
    "-Yno-adapted-args",
    "-Ypartial-unification",
    "-Ywarn-dead-code",
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused",
    "-Ywarn-value-discard"
  ),
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, minor)) if minor >= 12 => Seq("-Ywarn-extra-implicit")
    case _ => Seq.empty
  }),

  scalacOptions in (Compile, console) --= Seq(
    "-Xfatal-warnings",
    "-Ywarn-unused"
  ),

  dependencyOverrides += "org.scala-lang" % "scala-library" % scalaVersion.value,
  dependencyOverrides += "org.scala-lang" % "scala-reflect" % scalaVersion.value,

  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.0"),
  addCompilerPlugin("org.typelevel" % "kind-projector" % "0.10.2" cross CrossVersion.binary),
)

lazy val fundertow = project.in(file("."))
  .settings(name := "fundertow")
  .settings(commonSettings)
  .settings(skip in publish := true)
  .aggregate(core, zio, example)

lazy val core = project.in(file("core"))
  .settings(moduleName := "fundertow-core")
  .settings(commonSettings)
  .settings(Seq(
    libraryDependencies ++= Seq(
      "io.undertow" % "undertow-core" % undertowVersion,

      "org.scalaz" %% "scalaz-zio" % zioVersion % Optional,
      "org.scalaz" %% "scalaz-zio-streams" % zioVersion % Optional,
    ),
  ))

lazy val zio = project.in(file("zio"))
  .settings(moduleName := "fundertow-zio")
  .settings(commonSettings)
  .settings(Seq(
    libraryDependencies ++= Seq(
      "org.scalaz" %% "scalaz-zio" % zioVersion,
      "org.scalaz" %% "scalaz-zio-streams" % zioVersion,
    ),
  ))
  .dependsOn(core)

lazy val example = project.in(file("example"))
  .settings(moduleName := "fundertow-example")
  .settings(commonSettings)
  .settings(skip in publish := true)
  .settings(
    libraryDependencies ++= Seq(
      "tech.sparse" %% "trail" % "0.2.0", // TODO
      "ch.qos.logback" % "logback-classic" % "1.2.3",
    ),
  )
  .dependsOn(zio)
