package build

import java.io.File

import sbt.{Def, Keys, MessageOnlyException}
import sbt.io.syntax.fileToRichFile
import sbt.io.IO
import sbt.util.FileFunction

/** Utilities that are useful for releasing Bloop */
object ReleaseUtils {

  /** The path to our installation script */
  private val installScript = Def.setting { BuildKeys.buildBase.value / "bin" / "install.py" }

  /**
   * Creates a new installation script (based on the normal installation script) that has default
   * values for the nailgun commit and version of Bloop to install.
   *
   * This lets us create an installation script that doesn't need any additional input to install
   * the version of Bloop that we're releasing.
   */
  val versionedInstallScript = Def.task {
    val nailgun = Dependencies.nailgunVersion
    val coursier = Dependencies.coursierVersion
    val version = Keys.version.value
    val target = Keys.target.value
    val log = Keys.streams.value.log
    val cacheDirectory = Keys.streams.value.cacheDirectory
    val cachedWrite =
      FileFunction.cached(cacheDirectory) { scripts =>
        scripts.map { script =>
          val lines = IO.readLines(script)
          val marker = "# INSERT_INSTALL_VARIABLES"
          lines.span(_ != marker) match {
            case (before, _ :: after) =>
              val customizedVariables =
                List(
                  s"""NAILGUN_COMMIT = "$nailgun"""",
                  s"""BLOOP_VERSION = "$version"""",
                  s"""COURSIER_VERSION = "$coursier""""
                )
              val newContent = before ::: customizedVariables ::: after
              val scriptTarget = target / script.getName
              IO.writeLines(scriptTarget, newContent)
              scriptTarget

            case _ =>
              sys.error(s"Couldn't find '$marker' in '$script'.")
          }
        }
      }
    cachedWrite(Set(installScript.value)).head
  }

  /**
   * The content of the Homebrew Formula to install the version of Bloop that we're releasing.
   *
   * @param version The version of Bloop that we're releasing (usually `Keys.version.value`)
   * @param tagName The name of the tag that we're releasing
   * @param installSha The SHA-256 of the versioned installation script.
   */
  def formulaContent(version: String, tagName: String, installSha: String): String = {
    s"""class Bloop < Formula
       |  desc "Bloop gives you fast edit/compile/test workflows for Scala."
       |  homepage "https://github.com/scalacenter/bloop"
       |  version "$version"
       |  url "https://github.com/scalacenter/bloop/releases/download/$tagName/install.py"
       |  sha256 "$installSha"
       |  bottle :unneeded
       |
       |  depends_on "python3"
       |  depends_on :java => "1.8+"
       |
       |  def install
       |      mkdir "bin"
       |      system "python3", "install.py", "--dest", "bin", "--version", version
       |      zsh_completion.install "bin/zsh/_bloop"
       |      bash_completion.install "bin/bash/bloop"
       |      File.delete("bin/blp-coursier")
       |      FileUtils.mkdir_p("log/bloop/")
       |      FileUtils.chmod_R 0777, "log"
       |
       |      prefix.install "bin"
       |      prefix.install "log"
       |  end
       |
       |  def plist; <<~EOS
       |<?xml version="1.0" encoding="UTF-8"?>
       |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
       |<plist version="1.0">
       |<dict>
       |    <key>Label</key>
       |    <string>#{plist_name}</string>
       |    <key>ProgramArguments</key>
       |    <array>
       |        <string>#{bin}/blp-server</string>
       |    </array>
       |    <key>KeepAlive</key>
       |    <true/>
       |    <key>StandardOutPath</key>
       |    <string>#{var}/log/bloop/bloop.out.log</string>
       |    <key>StandardErrorPath</key>
       |    <string>#{var}/log/bloop/bloop.err.log</string>
       |</dict>
       |</plist>
       |          EOS
       |      end
       |
       |  test do
       |  end
       |end""".stripMargin
  }

  /** Generate the new Homebrew formula, a new tag and push all that in our Homebrew tap */
  val updateHomebrewFormula = Def.task {
    val buildBase = BuildKeys.buildBase.value
    val installSha = sha256(versionedInstallScript.value)
    val version = Keys.version.value
    val bloopoidName = "Bloopoid"
    val bloopoidEmail = "bloop@trashmail.ws"
    val token = sys.env.get("BLOOPOID_GITHUB_TOKEN").getOrElse {
      throw new MessageOnlyException("Couldn't find Github oauth token in `BLOOPOID_GITHUB_TOKEN`")
    }
    val tagName = GitUtils.withGit(buildBase)(GitUtils.latestTagIn(_)).getOrElse {
      throw new MessageOnlyException("No tag found in this repository.")
    }

    IO.withTemporaryDirectory { homebrewBase =>
      GitUtils.clone("https://github.com/scalacenter/homebrew-bloop.git", homebrewBase, token) {
        homebrewRepo =>
          val formulaFileName = "bloop.rb"
          val commitMessage = s"Updating to Bloop $tagName"
          val content = formulaContent(version, tagName, installSha)
          IO.write(homebrewBase / formulaFileName, content)
          val changed = formulaFileName :: Nil
          GitUtils.commitChangesIn(homebrewRepo,
                                   changed,
                                   commitMessage,
                                   bloopoidName,
                                   bloopoidEmail)
          GitUtils.tag(homebrewRepo, tagName, commitMessage)
          GitUtils.push(homebrewRepo, "origin", Seq("master", tagName), token)
      }
    }
  }

  def sha256(file: sbt.File): String = {
    import org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_256
    import org.apache.commons.codec.digest.DigestUtils
    new DigestUtils(SHA_256).digestAsHex(file)
  }
}
