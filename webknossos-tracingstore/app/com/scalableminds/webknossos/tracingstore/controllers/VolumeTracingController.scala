package com.scalableminds.webknossos.tracingstore.controllers

import java.nio.{ByteBuffer, ByteOrder}

import akka.stream.scaladsl.Source
import com.google.inject.Inject
import com.scalableminds.util.geometry.{Scale, Vector3I}
import com.scalableminds.webknossos.datastore.models.datasource._
import com.scalableminds.webknossos.datastore.models.{WebKnossosDataRequest, WebKnossosIsosurfaceRequest}
import com.scalableminds.webknossos.datastore.services.{IsosurfaceRequest, UserAccessRequest}
import com.scalableminds.webknossos.tracingstore.VolumeTracing.{VolumeTracing, VolumeTracingOpt, VolumeTracings}
import com.scalableminds.webknossos.tracingstore.slacknotification.SlackNotificationService
import com.scalableminds.webknossos.tracingstore.tracings._
import com.scalableminds.webknossos.tracingstore.tracings.volume.{IsosurfaceService, VolumeTracingService}
import com.scalableminds.webknossos.tracingstore.{
  TracingStoreAccessTokenService,
  TracingStoreConfig,
  TracingStoreWkRpcClient
}
import play.api.i18n.Messages
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.streams.IterateeStreams
import play.api.libs.json.Json
import play.api.mvc.PlayBodyParsers

import scala.concurrent.ExecutionContext

class VolumeTracingController @Inject()(
    val tracingService: VolumeTracingService,
    val webKnossosServer: TracingStoreWkRpcClient,
    val accessTokenService: TracingStoreAccessTokenService,
    config: TracingStoreConfig,
    tracingDataStore: TracingDataStore,
    val slackNotificationService: SlackNotificationService,
    val isosurfaceService: IsosurfaceService)(implicit val ec: ExecutionContext, val bodyParsers: PlayBodyParsers)
    extends TracingController[VolumeTracing, VolumeTracings] {

  implicit val tracingsCompanion = VolumeTracings

  implicit def packMultiple(tracings: List[VolumeTracing]): VolumeTracings =
    VolumeTracings(tracings.map(t => VolumeTracingOpt(Some(t))))

  implicit def packMultipleOpt(tracings: List[Option[VolumeTracing]]): VolumeTracings =
    VolumeTracings(tracings.map(t => VolumeTracingOpt(t)))

  implicit def unpackMultiple(tracings: VolumeTracings): List[Option[VolumeTracing]] =
    tracings.tracings.toList.map(_.tracing)

  def initialData(tracingId: String) = Action.async { implicit request =>
    log {
      logTime(slackNotificationService.reportUnusalRequest) {
        accessTokenService.validateAccess(UserAccessRequest.webknossos) {
          AllowRemoteOrigin {
            for {
              initialData <- request.body.asRaw.map(_.asFile) ?~> Messages("zipFile.notFound")
              tracing <- tracingService.find(tracingId) ?~> Messages("tracing.notFound")
              _ <- tracingService.initializeWithData(tracingId, tracing, initialData)
            } yield Ok(Json.toJson(tracingId))
          }
        }
      }
    }
  }

  def allData(tracingId: String, version: Option[Long]) = Action.async { implicit request =>
    log {
      accessTokenService.validateAccess(UserAccessRequest.readTracing(tracingId)) {
        AllowRemoteOrigin {
          for {
            tracing <- tracingService.find(tracingId, version) ?~> Messages("tracing.notFound")
          } yield {
            val enumerator: Enumerator[Array[Byte]] = tracingService.allDataEnumerator(tracingId, tracing)
            Ok.chunked(Source.fromPublisher(IterateeStreams.enumeratorToPublisher(enumerator)))
          }
        }
      }
    }
  }

  def allDataBlocking(tracingId: String, version: Option[Long]) = Action.async { implicit request =>
    log {
      accessTokenService.validateAccess(UserAccessRequest.readTracing(tracingId)) {
        AllowRemoteOrigin {
          for {
            tracing <- tracingService.find(tracingId, version) ?~> Messages("tracing.notFound")
            data <- tracingService.allDataFile(tracingId, tracing)
            _ = Thread.sleep(5)
          } yield {
            Ok.sendFile(data)
          }
        }
      }
    }
  }

  def data(tracingId: String) = Action.async(validateJson[List[WebKnossosDataRequest]]) { implicit request =>
    log {
      accessTokenService.validateAccess(UserAccessRequest.readTracing(tracingId)) {
        AllowRemoteOrigin {
          for {
            tracing <- tracingService.find(tracingId) ?~> Messages("tracing.notFound")
            (data, indices) <- tracingService.data(tracingId, tracing, request.body)
          } yield Ok(data).withHeaders(getMissingBucketsHeaders(indices): _*)
        }
      }
    }
  }

  private def getMissingBucketsHeaders(indices: List[Int]): Seq[(String, String)] =
    List(("MISSING-BUCKETS" -> formatMissingBucketList(indices)),
         ("Access-Control-Expose-Headers" -> "MISSING-BUCKETS"))

  private def formatMissingBucketList(indices: List[Int]): String =
    "[" + indices.mkString(", ") + "]"

  def duplicate(tracingId: String, version: Option[Long]) = Action.async { implicit request =>
    log {
      logTime(slackNotificationService.reportUnusalRequest) {
        accessTokenService.validateAccess(UserAccessRequest.webknossos) {
          AllowRemoteOrigin {
            for {
              tracing <- tracingService.find(tracingId) ?~> Messages("tracing.notFound")
              newId <- tracingService.duplicate(tracingId, tracing)
            } yield {
              Ok(Json.toJson(newId))
            }
          }
        }
      }
    }
  }

  def updateActionLog(tracingId: String) = Action.async { implicit request =>
    log {
      accessTokenService.validateAccess(UserAccessRequest.readTracing(tracingId)) {
        AllowRemoteOrigin {
          for {
            updateLog <- tracingService.updateActionLog(tracingId)
          } yield {
            Ok(updateLog)
          }
        }
      }
    }
  }

  def requestIsosurface(tracingId: String) =
    Action.async(validateJson[WebKnossosIsosurfaceRequest]) { implicit request =>
      accessTokenService.validateAccess(UserAccessRequest.readTracing(tracingId)) {
        AllowRemoteOrigin {
          for {
            tracing <- tracingService.find(tracingId) ?~> Messages("tracing.notFound")
            volumeLayer = tracingService.volumeTracingLayer(tracingId, tracing)
            datasource = GenericDataSource(DataSourceId("volumeDataSource", "volume"),
                                           List(volumeLayer),
                                           Scale(1, 1, 1))
            layerRequest = request.body.copy(zoomStep = 0, voxelDimensions = Vector3I(1, 1, 1), mapping = None)
            isosurfaceRequest = IsosurfaceRequest(null,
                                                  volumeLayer,
                                                  layerRequest.cuboid(volumeLayer),
                                                  layerRequest.segmentId,
                                                  layerRequest.voxelDimensions,
                                                  None)
            // The client expects the isosurface as a flat float-array. Three consecutive floats form a 3D point, three
            // consecutive 3D points (i.e., nine floats) form a triangle.
            // There are no shared vertices between triangles.
            (vertices, neighbors) <- isosurfaceService.requestIsosurfaceViaActor(isosurfaceRequest)
          } yield {
            // We need four bytes for each float
            val responseBuffer = ByteBuffer.allocate(vertices.length * 4).order(ByteOrder.LITTLE_ENDIAN)
            responseBuffer.asFloatBuffer().put(vertices)
            Ok(responseBuffer.array()).withHeaders(getNeighborIndices(neighbors): _*)
          }
        }
      }
    }

  private def getNeighborIndices(neighbors: List[Int]) =
    List(("NEIGHBORS" -> formatNeighborList(neighbors)), ("Access-Control-Expose-Headers" -> "NEIGHBORS"))

  private def formatNeighborList(neighbors: List[Int]): String =
    "[" + neighbors.mkString(", ") + "]"

}
