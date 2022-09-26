import sbt._

import Keys._

object NoPublishPlugin extends AutoPlugin {
  override def trigger = noTrigger

  override def projectSettings = Seq(
    publish := {},
    publishLocal := {},
    publishArtifact := false,
    publish / skip := true
  )

}
