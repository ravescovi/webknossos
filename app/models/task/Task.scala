package models.task

import models.basics._
import java.util.Date
import braingames.geometry.Point3D

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Logger
import models.user.{User, Experience}
import models.annotation._
import play.api.libs.json.{Json, JsObject}
import braingames.format.Formatter
import scala.concurrent.duration._
import braingames.util.{Fox, FoxImplicits}
import braingames.reactivemongo.{DefaultAccessDefinitions, GlobalAccessContext, DBAccessContext}
import reactivemongo.bson.BSONObjectID
import play.modules.reactivemongo.json.BSONFormats._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import java.text.SimpleDateFormat
import play.api.libs.json.JsObject
import scala.async.Async._
import akka.actor.Props
import akka.routing.RoundRobinRouter
import reactivemongo.api.indexes.{IndexType, Index}
import reactivemongo.core.commands.LastError
import braingames.reactivemongo.AccessRestrictions.{AllowIf, DenyEveryone}

case class Task(
                 _taskType: BSONObjectID,
                 team: String,
                 neededExperience: Experience = Experience.empty,
                 priority: Int = 100,
                 instances: Int = 1,
                 assignedInstances: Int = 0,
                 tracingTime: Option[Long] = None,
                 created: DateTime = DateTime.now(),
                 _project: Option[String] = None,
                 _id: BSONObjectID = BSONObjectID.generate
               ) extends FoxImplicits {

  lazy val id = _id.stringify

  def taskType(implicit ctx: DBAccessContext) = TaskTypeDAO.findOneById(_taskType)(GlobalAccessContext).toFox

  def project(implicit ctx: DBAccessContext) =
    _project.toFox.flatMap(name => ProjectDAO.findOneByName(name))

  def isFullyAssigned =
    instances <= assignedInstances

  def annotations(implicit ctx: DBAccessContext) =
    AnnotationService.annotationsFor(this)

  def settings(implicit ctx: DBAccessContext) =
    taskType.map(_.settings) getOrElse AnnotationSettings.skeletonDefault

  def annotationBase(implicit ctx: DBAccessContext) =
    AnnotationService.baseFor(this)

  def unassigneOnce = this.copy(assignedInstances = assignedInstances - 1)

  def status(implicit ctx: DBAccessContext) = async{
    val inProgress = await(annotations).filter(!_.state.isFinished).size
    CompletionStatus(
      open = instances - assignedInstances,
      inProgress = inProgress,
      completed = assignedInstances - inProgress)
  }

  def hasEnoughExperience(user: User) = {
    if (this.neededExperience.isEmpty) {
      true
    } else {
      user.experiences
      .get(this.neededExperience.domain)
      .map(_ >= this.neededExperience.value)
      .getOrElse(false)
    }
  }
}

object Task extends FoxImplicits {
  implicit val taskFormat = Json.format[Task]

  def transformToJson(task: Task)(implicit ctx: DBAccessContext): Future[JsObject] = {
    for {
      dataSetName <- task.annotationBase.flatMap(_.dataSetName) getOrElse ""
      editPosition <- task.annotationBase.flatMap(_.content.map(_.editPosition)) getOrElse Point3D(1, 1, 1)
      boundingBox <- task.annotationBase.flatMap(_.content.map(_.boundingBox)) getOrElse None
      status <- task.status
      taskType <- task.taskType.futureBox
      projectName = task._project.getOrElse("")
    } yield {
      Json.obj(
        "id" -> task.id,
        "team" -> task.team,
        "formattedHash" -> Formatter.formatHash(task.id),
        "projectName" -> projectName,
        "type" -> taskType.toOption.map(TaskType.publicTaskTypeWrites.writes),
        "dataSet" -> dataSetName,
        "editPosition" -> editPosition,
        "boundingBox" -> boundingBox,
        "neededExperience" -> task.neededExperience,
        "priority" -> task.priority,
        "created" -> DateTimeFormat.forPattern("yyyy-MM-dd HH:mm").print(task.created),
        "status" -> status,
        "tracingTime" -> task.tracingTime
      )
    }
  }
}

object TaskDAO extends SecuredBaseDAO[Task] with FoxImplicits {

  val collectionName = "tasks"

  val formatter = Task.taskFormat

  underlying.indexesManager.ensure(Index(Seq("_project" -> IndexType.Ascending)))
  underlying.indexesManager.ensure(Index(Seq("_taskType" -> IndexType.Ascending)))

  override val AccessDefinitions = new DefaultAccessDefinitions{

    override def findQueryFilter(implicit ctx: DBAccessContext) = {
      ctx.data match{
        case Some(user: User) =>
          AllowIf(Json.obj("team" -> Json.obj("$in" -> user.teamNames)))
        case _ =>
          DenyEveryone()
      }
    }

    override def removeQueryFilter(implicit ctx: DBAccessContext) = {
      ctx.data match{
        case Some(user: User) =>
          AllowIf(Json.obj("team" -> Json.obj("$in" -> user.adminTeamNames)))
        case _ =>
          DenyEveryone()
      }
    }
  }

  @deprecated(message = "This method shouldn't be used. Use TaskService.remove instead", "2.0")
  override def removeById(bson: BSONObjectID)(implicit ctx: DBAccessContext) = {
    super.removeById(bson)
  }

  def findAllAdministratable(user: User)(implicit ctx: DBAccessContext) = withExceptionCatcher{
    find(Json.obj(
      "team" -> Json.obj("$in" -> user.adminTeamNames))).cursor[Task].collect[List]()
  }

  def removeAllWithProject(project: Project)(implicit ctx: DBAccessContext) = {
    project.tasks.map(_.map(task => {
      removeById(task._id)
    }))
  }

  def findAllByTaskType(taskType: TaskType)(implicit ctx: DBAccessContext) = withExceptionCatcher{
    find("_taskType", taskType._id).collect[List]()
  }

  def findAllByProject(project: String)(implicit ctx: DBAccessContext) = withExceptionCatcher{
    find("_project", project).collect[List]()
  }

  def findAllAssignable(implicit ctx: DBAccessContext) =
    findAll.map(_.filter(!_.isFullyAssigned))

  def logTime(time: Long, _task: BSONObjectID)(implicit ctx: DBAccessContext) =
    update(Json.obj("_id" -> _task), Json.obj("$inc" -> Json.obj("tracingTime" -> time)))

  def changeAssignedInstances(_task: BSONObjectID, by: Int)(implicit ctx: DBAccessContext) =
    update(Json.obj("_id" -> _task), Json.obj("$inc" -> Json.obj("assignedInstances" -> by)))

  def assignOnce(_task: BSONObjectID)(implicit ctx: DBAccessContext) =
    changeAssignedInstances(_task, 1)

  def unassignOnce(_task: BSONObjectID)(implicit ctx: DBAccessContext) =
    changeAssignedInstances(_task, -1)

  def update(_task: BSONObjectID, _taskType: BSONObjectID,
             neededExperience: Experience,
             priority: Int,
             instances: Int,
             team: String,
             _project: Option[String]
            )(implicit ctx: DBAccessContext): Fox[LastError] =
    update(
      Json.obj("_id" -> _task),
      Json.obj("$set" ->
        Json.obj(
          "neededExperience" -> neededExperience,
          "priority" -> priority,
          "instances" -> instances,
          "team" -> team,
          "_project" -> _project)))
}