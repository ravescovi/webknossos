package models.knowledge

import models.basics.DAOCaseClass
import models.basics.BasicDAO
import braingames.geometry.Point3D
import org.bson.types.ObjectId
import com.mongodb.casbah.commons.MongoDBObject
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.novus.salat._
import models.context._
import scala.util.Random

case class ContextFreeMission(missionId: Int, start: StartSegment, errorCenter: Point3D, end: SimpleSegment, possibleEnds: List[EndSegment], difficulty: Double) {
  def addContext(dataSetName: String, batchId: Int) = Mission(dataSetName, missionId, batchId, start, errorCenter, end, possibleEnds, difficulty)
}

object ContextFreeMission extends Function6[Int, StartSegment, Point3D, SimpleSegment, List[EndSegment], Double, ContextFreeMission] {
  implicit val ContextFreeMissionReader: Reads[ContextFreeMission] = Json.reads[ContextFreeMission]
}

case class Mission(dataSetName: String,
                   missionId: Int,
                   batchId: Int,
                   start: StartSegment,
                   errorCenter: Point3D,
                   end: SimpleSegment,
                   possibleEnds: List[EndSegment],
                   difficulty: Double,
                   _id: ObjectId = new ObjectId) extends DAOCaseClass[Mission] {

  val key: String = dataSetName.toString + "_" + missionId.toString

  val dao = Mission
  lazy val id = _id.toString

  def withDataSetName(newDataSetName: String) = copy(dataSetName = newDataSetName)

  def batchId(newBatchId: Int) = copy(batchId = newBatchId)
}

trait MissionFormats extends CommonFormats with Function9[String, Int, Int, StartSegment, Point3D, SimpleSegment, List[EndSegment], Double, ObjectId, Mission]{
  implicit val missionFormat: Format[Mission] = Json.format[Mission]
}

object Mission extends BasicDAO[Mission]("missions") with MissionFormats with Function9[String, Int, Int, StartSegment, Point3D, SimpleSegment, List[EndSegment], Double, ObjectId, Mission] {

  def findByDataSetName(dataSetName: String) = find(MongoDBObject("dataSetName" -> dataSetName)).toList

  def findOneByMissionId(missionId: Int) = findOne(MongoDBObject("missionId" -> missionId))

  def randomByDataSetName(dataSetName: String) = {
    val missions = findByDataSetName(dataSetName)
    if (!missions.isEmpty)
      Some(missions(Random.nextInt(missions.size)))
    else None
  }

  def updateOrCreate(m: Mission) =
    findOne(MongoDBObject(
      "dataSetName" -> m.dataSetName,
      "missionId" -> m.missionId)) match {
      case Some(stored) =>
        stored.update(_ => m.copy(_id = stored._id))
        stored._id
      case _ =>
        insertOne(m)
        m._id
    }

  def deleteAllForDataSetExcept(dataSetName: String, missions: List[Mission]) = {
    val obsoleteMissions =
      findByDataSetName(dataSetName)
        .filterNot(m =>
          missions.exists(_.missionId == m.missionId))

    removeByIds(obsoleteMissions.map(_._id))
    obsoleteMissions.map(_.id)
  }

}