val zioVersion = "1.0.0-RC10-1"
val undertowVersion = "2.0.22.Final"

lazy val IntegrationTest = config("it") extend Test

lazy val commonSettings = Seq(
  scalaVersion := "2.12.8",
  crossScalaVersions := Seq("2.11.12", scalaVersion.value, "2.13.0"),

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
    //"-Xfatal-warnings",
  ),
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, minor)) if minor < 12 => Seq(
      "-Ypartial-unification",
    )
    case Some((2, minor)) if minor == 12 => Seq(
      "-Xfuture",
      "-Xlint",
      "-Yno-adapted-args",
      "-Ypartial-unification",
      "-Ywarn-dead-code",
      "-Ywarn-extra-implicit",
      "-Ywarn-inaccessible",
      "-Ywarn-infer-any",
      "-Ywarn-nullary-override",
      "-Ywarn-nullary-unit",
      "-Ywarn-numeric-widen",
      "-Ywarn-unused",
      "-Ywarn-value-discard",
    )
    case _ => Seq(
      "-Wdead-code",
      "-Wextra-implicit",
      "-Wnumeric-widen",
      "-Wunused:implicits",
      "-Wunused:imports",
      "-Wunused:locals",
      "-Wunused:params",
      "-Wunused:patvars",
      "-Wunused:privates",
      "-Wvalue-discard"
    )
  }),

  scalacOptions in (Compile, console) --= Seq(
    "-Xfatal-warnings",
    "-Ywarn-unused"
  ),

  dependencyOverrides += scalaOrganization.value % "scala-library" % scalaVersion.value,
  dependencyOverrides += scalaOrganization.value % "scala-reflect" % scalaVersion.value,

  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  addCompilerPlugin("org.typelevel" % "kind-projector" % "0.10.3" cross CrossVersion.binary),
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

      "dev.zio" %% "zio" % zioVersion % Optional,
      "dev.zio" %% "zio-streams" % zioVersion % Optional,
    ),
  ))

lazy val zio = project.in(file("zio"))
  .settings(moduleName := "fundertow-zio")
  .settings(commonSettings)
  .settings(Seq(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
    ),
  ))
  .dependsOn(core)

lazy val example = project.in(file("example"))
  .settings(moduleName := "fundertow-example")
  .settings(commonSettings)
  .settings(skip in publish := true)
  .settings(
    libraryDependencies ++= Seq(
      "tech.sparse" %% "trail" % "0.2.1", // TODO
      "ch.qos.logback" % "logback-classic" % "1.2.3",
    ),
  )
  .dependsOn(zio)
