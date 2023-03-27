ThisBuild / organization := "aesakamar"
ThisBuild / scalaVersion := "2.13.10"

lazy val root = (project in file(".")).settings(
  name := "distcount",
  libraryDependencies ++= Seq(
//
    "org.typelevel" %% "cats-core"          % "2.9.0",
    "org.typelevel" %% "cats-effect"        % "3.4.8",
    "org.typelevel" %% "cats-effect-kernel" % "3.4.7",
    "org.typelevel" %% "cats-effect-std"    % "3.4.8",
//
    "org.typelevel" %% "discipline-core"  % "1.5.1" % Test,
    "org.typelevel" %% "cats-laws"        % "2.0.0" % Test,
    "org.typelevel" %% "discipline-munit" % "1.0.9" % Test,

//
    "co.fs2"      %% "fs2-core"      % "3.6.1",
    "co.fs2"      %% "fs2-scodec"    % "3.6.1",
    "co.fs2"      %% "fs2-io"        % "3.6.1",
    "dev.kovstas" %% "fs2-throttler" % "1.0.6",
//
    "com.avast.cloud" %% "datadog4s-statsd"    % "0.31.2",
    "org.http4s"      %% "http4s-ember-client" % "1.0.0-M39",

    //
    "com.lihaoyi"   %% "pprint"              % "0.8.1",
    "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test,
    "org.typelevel" %% "discipline-core"     % "1.5.1" % Test,
    "org.typelevel" %% "discipline-munit"    % "1.0.9" % Test
  ),
  tpolecatExcludeOptions ++= ScalacOptions.warnUnusedOptions
)
