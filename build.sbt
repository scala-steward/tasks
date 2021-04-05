import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

inThisBuild(
  List(
    organization := "io.github.pityka",
    homepage := Some(url("https://pityka.github.io/tasks/")),
    licenses := List(
      ("Apache-2.0", url("https://opensource.org/licenses/Apache-2.0"))
    ),
    developers := List(
      Developer(
        "pityka",
        "Istvan Bartha",
        "bartha.pityu@gmail.com",
        url("https://github.com/pityka/tasks")
      )
    )
  )
)

lazy val commonSettings = Seq(
  scalaVersion := "2.13.5",
  crossScalaVersions := Seq("2.12.13", "2.13.5"),
  parallelExecution in Test := false,
  scalacOptions ++= Seq(
    "-deprecation", // Emit warning and location for usages of deprecated APIs.
    "-encoding",
    "utf-8", // Specify character encoding used by source files.
    "-feature", // Emit warning and location for usages of features that should be imported explicitly.
    "-language:postfixOps",
    "-unchecked", // Enable additional warnings where generated code depends on assumptions.
    "-Xfatal-warnings", // Fail the compilation if there are any warnings.
    "-Xlint:adapted-args", // Warn if an argument list is modified to match the receiver.
    "-Xlint:constant", // Evaluation of a constant arithmetic expression results in an error.
    "-Xlint:delayedinit-select", // Selecting member of DelayedInit.
    "-Xlint:doc-detached", // A Scaladoc comment appears to be detached from its element.
    "-Xlint:inaccessible", // Warn about inaccessible types in method signatures.
    "-Xlint:infer-any", // Warn when a type argument is inferred to be `Any`.
    "-Xlint:missing-interpolator", // A string literal appears to be missing an interpolator id.
    "-Xlint:nullary-unit", // Warn when nullary methods return Unit.
    "-Xlint:option-implicit", // Option.apply used implicit view.
    "-Xlint:poly-implicit-overload", // Parameterized overloaded implicit methods are not visible as view bounds.
    "-Xlint:private-shadow", // A private field (or class parameter) shadows a superclass field.
    "-Xlint:stars-align", // Pattern sequence wildcard must align with sequence component.
    "-Xlint:type-parameter-shadow", // A local type parameter shadows a type already in scope.
    "-Ywarn-extra-implicit", // Warn when more than one implicit parameter section is defined.
    "-Ywarn-numeric-widen", // Warn when numerics are widened.
    "-Ywarn-unused:implicits", // Warn if an implicit parameter is unused.
    "-Ywarn-unused:imports", // Warn if an import selector is not referenced.
    "-Ywarn-unused:locals", // Warn if a local definition is unused.
    "-Ywarn-unused:params", // Warn if a value parameter is unused.
    "-Ywarn-unused:patvars", // Warn if a variable bound in a pattern is unused.
    "-Ywarn-unused:privates" // Warn if a private member is unused.
  )
) ++ Seq(
  fork := true,
  cancelable in Global := true
)

lazy val circeVersion = "0.13.0"
lazy val jsoniterVersion = "2.6.3"
lazy val akkaVersion = "2.6.13"

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(sbtcrossproject.CrossPlugin.autoImport.CrossType.Pure)
  .in(file("shared"))
  .settings(
    name := "tasks-shared",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion
    )
  )
  .settings(commonSettings: _*)
  .jsSettings(fork := false)

lazy val sharedJVM = shared.jvm

lazy val sharedJS = shared.js

resolvers += Resolver.jcenterRepo

lazy val spores = project
  .in(file("spores"))
  .settings(commonSettings: _*)
  .settings(
    name := "tasks-spores",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion
    )
  )
lazy val core = project
  .in(file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := "tasks-core",
    resolvers += Resolver.bintrayRepo("beyondthelines", "maven"),
    PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value
    ),
    libraryDependencies ++= Seq(
      "com.google.guava" % "guava" % "22.0",
      "com.typesafe.akka" %% "akka-actor" % akkaVersion,
      "com.typesafe.akka" %% "akka-remote" % akkaVersion,
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
      "com.typesafe.akka" %% "akka-http-core" % "10.1.11",
      "com.typesafe" % "config" % "1.4.0",
      "io.github.pityka" %% "selfpackage" % "1.2.5",
      "io.github.pityka" %% "s3-stream-fork" % "0.0.8",
      "org.scalatest" %% "scalatest" % "3.2.5" % "test",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
      "org.scala-lang" % "scala-reflect" % scalaVersion.value
    )
  )
  .dependsOn(sharedJVM, spores)

lazy val ec2 = project
  .in(file("ec2"))
  .settings(commonSettings: _*)
  .settings(
    name := "tasks-ec2",
    libraryDependencies ++= Seq(
      "com.amazonaws" % "aws-java-sdk-ec2" % "1.11.24"
    )
  )
  .dependsOn(core)

lazy val ssh = project
  .in(file("ssh"))
  .settings(commonSettings: _*)
  .settings(
    name := "tasks-ssh",
    libraryDependencies ++= Seq(
      "ch.ethz.ganymed" % "ganymed-ssh2" % "261"
    )
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val kubernetes = project
  .in(file("kubernetes"))
  .settings(commonSettings: _*)
  .settings(
    name := "tasks-kubernetes",
    libraryDependencies ++= Seq(
      "io.fabric8" % "kubernetes-client" % "4.0.7"
    )
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val tracker = project
  .in(file("tracker"))
  .settings(commonSettings: _*)
  .settings(
    name := "tasks-tracker",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.5" % "test"
    ),
    resources in Compile += (fastOptJS in Compile in uifrontend).value.data
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val uibackend = project
  .in(file("uibackend"))
  .settings(commonSettings: _*)
  .settings(
    name := "tasks-ui-backend",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.5" % "test"
    ),
    resources in Compile += (fastOptJS in Compile in uifrontend).value.data
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val uifrontend = project
  .in(file("uifrontend"))
  .settings(commonSettings: _*)
  .settings(
    name := "tasks-ui-frontend",
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "1.0.0",
      "io.circe" %%% "circe-core" % circeVersion,
      "io.circe" %%% "circe-parser" % circeVersion,
      "io.circe" %%% "circe-generic" % circeVersion,
      "com.raquo" %%% "laminar" % "0.11.0"
    ),
    fork := false
  )
  .dependsOn(sharedJS)
  .enablePlugins(ScalaJSPlugin)

lazy val example = project
  .in(file("example"))
  .settings(commonSettings: _*)
  .dependsOn(core, ecoll, ssh)
  .enablePlugins(JavaAppPackaging)
  .settings(
    executableScriptName := "entrypoint",
    topLevelDirectory := None,
    publishArtifact := false,
    crossScalaVersions := Nil
  )

lazy val upicklesupport = project
  .in(file("upickle"))
  .settings(commonSettings: _*)
  .settings(
    name := "tasks-upickle",
    libraryDependencies += "com.lihaoyi" %% "upickle" % "1.2.3"
  )
  .dependsOn(core)

lazy val jsoniter = project
  .in(file("jsoniter"))
  .settings(commonSettings: _*)
  .settings(
    name := "tasks-jsoniter",
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion % Compile,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion % Provided,
      "org.scalatest" %% "scalatest" % "3.2.5" % "test"
    )
  )
  .dependsOn(core)

lazy val ecoll = project
  .in(file("collection"))
  .settings(commonSettings: _*)
  .settings(
    name := "tasks-ecoll",
    libraryDependencies ++= Seq(
      "io.github.pityka" %% "flatjoin-akka-stream" % "0.0.17",
      "io.github.pityka" %% "lame-bgzip-index" % "0.0.4",
      "org.scalatest" %% "scalatest" % "3.2.5" % "test"
    )
  )
  .dependsOn(core)

lazy val root = (project in file("."))
  .settings(commonSettings: _*)
  .settings(
    publishArtifact := false,
    crossScalaVersions := Nil
  )
  .aggregate(
    spores,
    core,
    ecoll,
    upicklesupport,
    jsoniter,
    sharedJVM,
    sharedJS,
    ec2,
    ssh,
    uibackend,
    uifrontend,
    kubernetes,
    tracker,
    example
  )

lazy val testables = (project in file("testables"))
  .settings(commonSettings: _*)
  .settings(
    publishArtifact := false
  )
  .aggregate(
    spores,
    core,
    ecoll,
    upicklesupport,
    jsoniter,
    sharedJVM,
    ssh
  )
