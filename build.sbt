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
    "co.fs2"      %% "fs2-core"      % "3.6.1",
    "co.fs2"      %% "fs2-scodec"    % "3.6.1",
    "co.fs2"      %% "fs2-io"        % "3.6.1",
    "dev.kovstas" %% "fs2-throttler" % "1.0.6",
//
    "com.avast.cloud" %% "datadog4s-statsd" % "0.31.2",
//
    "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test,
    "com.lihaoyi"   %% "pprint"              % "0.8.1"
  )
)
