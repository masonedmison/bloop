package bloop.integrations.maven

import bloop.config.utils.BaseConfigSuite
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.io.InputStreamReader
import java.io.File
import org.junit.Assert._
import org.junit.Test
import org.apache.maven.shared.invoker.DefaultInvocationRequest
import org.apache.maven.shared.invoker.DefaultInvoker
import org.apache.maven.shared.invoker.InvocationResult
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal
import scala.util.Try
import scala.sys.process.ProcessLogger
import bloop.config.Config
import bloop.config.Tag
import java.util.stream.Collector
import java.util.stream.Collectors

class MavenConfigGenerationSuite extends BaseConfigSuite {

  @Test
  def basicScala3() = {
    check("basic_scala3/pom.xml") { (configFile, projectName, subprojects) =>
      assert(subprojects.isEmpty)
      assert(configFile.project.`scala`.isDefined)
      assertEquals("3.0.0", configFile.project.`scala`.get.version)
      assertEquals("org.scala-lang", configFile.project.`scala`.get.organization)
      assert(configFile.project.`scala`.get.jars.exists(_.toString.contains("scala3-compiler_3")))
      assert(hasCompileClasspathEntryName(configFile, "scala3-library_3"))
      assert(hasCompileClasspathEntryName(configFile, "scala-library"))

      val idxDottyLib = idxOfClasspathEntryName(configFile, "scala3-library_3")
      val idxScalaLib = idxOfClasspathEntryName(configFile, "scala-library")

      assert(idxDottyLib < idxScalaLib)

      assert(hasTag(configFile, Tag.Library))

      assertNoConfigsHaveAnyJars(List(configFile), List(s"$projectName", s"$projectName-test"))
      assertAllConfigsMatchJarNames(List(configFile), List("scala3-library_3"))
    }
  }

  @Test
  def basicScala() = {
    check("basic_scala/pom.xml") { (configFile, projectName, subprojects) =>
      assert(subprojects.isEmpty)
      assert(configFile.project.`scala`.isDefined)
      assertEquals("2.13.6", configFile.project.`scala`.get.version)
      assertEquals("org.scala-lang", configFile.project.`scala`.get.organization)
      assert(
        !configFile.project.`scala`.get.jars.exists(_.toString.contains("scala3-compiler_3")),
        "No Scala 3 jar should be present."
      )
      assert(!hasCompileClasspathEntryName(configFile, "scala3-library_3"))
      assert(hasCompileClasspathEntryName(configFile, "scala-library"))

      assert(hasTag(configFile, Tag.Library))

      assertNoConfigsHaveAnyJars(List(configFile), List(s"$projectName", s"$projectName-test"))
      assertAllConfigsMatchJarNames(List(configFile), List("scala-library", "munit"))
    }
  }

  @Test
  def multiProject() = {
    check(
      "multi_scala/pom.xml",
      submodules = List("multi_scala/module1/pom.xml", "multi_scala/module2/pom.xml")
    ) {
      case (configFile, projectName, List(module1, module2)) =>
        assert(configFile.project.`scala`.isEmpty)
        assert(module1.project.`scala`.isEmpty)
        assert(module2.project.`scala`.isDefined)

        assertEquals("2.13.6", module2.project.`scala`.get.version)
        assertEquals("org.scala-lang", module2.project.`scala`.get.organization)
        assert(
          !module2.project.`scala`.get.jars.exists(_.toString.contains("scala3-compiler_3")),
          "No Scala 3 jar should be present."
        )
        assert(hasCompileClasspathEntryName(module2, "scala-library"))

        assert(hasTag(module1, Tag.Library))
        assert(hasTag(module2, Tag.Library))

        assertNoConfigsHaveAnyJars(List(configFile), List(s"$projectName", s"$projectName-test"))
        assertNoConfigsHaveAnyJars(List(module1), List("module1", "module1-test"))
        assertNoConfigsHaveAnyJars(List(module2), List("module2", "module2-test"))

        assertAllConfigsMatchJarNames(List(module1), List("scala-library", "munit"))
      case _ =>
        assert(false, "Multi project should have two submodules")
    }
  }

  private def check(testProject: String, submodules: List[String] = Nil)(
      checking: (Config.File, String, List[Config.File]) => Unit
  ): Unit = {
    def nameFromDirectory(projectString: String) =
      Paths.get(projectString).getParent().getFileName().toString()
    val tempDir = Files.createTempDirectory("mavenBloop")
    val outFile = copyFromResource(tempDir, testProject)
    submodules.foreach(copyFromResource(tempDir, _))
    val wrapperJar = copyFromResource(tempDir, s"maven-wrapper.jar")
    val wrapperPropertiesFile = copyFromResource(tempDir, s"maven-wrapper.properties")

    //    val all = Files.list(tempDir).collect(Collectors.toList())
    import sys.process._

    val javaHome = Paths.get(System.getProperty("java.home"))
    val javaArgs = List[String](
      javaHome.resolve("bin/java").toString(),
      "-Dfile.encoding=UTF-8",
      s"-Dmaven.multiModuleProjectDirectory=$tempDir",
      s"-Dmaven.home=$tempDir"
    )

    val jarArgs = List(
      "-jar",
      wrapperJar.toString()
    )
    val version = bloop.BuildInfo.version
    val command =
      List(s"ch.epfl.scala:maven-bloop_2.13:$version:bloopInstall", "-DdownloadSources=true")
    val allArgs = List(
      javaArgs,
      jarArgs,
      command
    ).flatten

    val result = exec(allArgs, outFile.getParent().toFile())
    try {
      val projectPath = outFile.getParent()
      val projectName = projectPath.toFile().getName()
      val bloopDir = projectPath.resolve(".bloop")
      val projectFile = bloopDir.resolve(s"${projectName}.json")
      val configFile = readValidBloopConfig(projectFile.toFile())

      val subProjects = submodules.map { mod =>
        val subProjectName = tempDir.resolve(mod).getParent().toFile().getName()
        val subProjectFile = bloopDir.resolve(s"${subProjectName}.json")
        readValidBloopConfig(subProjectFile.toFile())
      }
      checking(configFile, projectName, subProjects)
      tempDir.toFile().delete()
    } catch {
      case NonFatal(e) =>
        println("Maven output:\n" + result)
        throw e
    }
  }

  private def copyFromResource(
      tempDir: Path,
      filePath: String
  ): Path = {
    val embeddedFile =
      this.getClass.getResourceAsStream(s"/$filePath")
    val outFile = tempDir.resolve(filePath)
    Files.createDirectories(outFile.getParent)
    Files.copy(embeddedFile, outFile, StandardCopyOption.REPLACE_EXISTING)
    outFile
  }

  private def exec(cmd: Seq[String], cwd: File): Try[String] = {
    Try {
      val lastError = new StringBuilder
      val swallowStderr = ProcessLogger(_ => (), err => lastError.append(err))
      val processBuilder = new ProcessBuilder()
      val out = new StringBuilder()
      processBuilder.directory(cwd)
      processBuilder.command(cmd: _*);
      var process = processBuilder.start()

      val reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()))

      var line = reader.readLine()
      while (line != null) {
        out.append(line + "\n")
        line = reader.readLine()
      }
      out.toString()
    }
  }

}
