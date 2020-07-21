package com.scalableminds.webknossos.tracingstore.tracings.volume

import com.scalableminds.util.geometry.Point3D
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import com.scalableminds.webknossos.datastore.models.BucketPosition
import com.scalableminds.webknossos.datastore.models.datasource.{DataLayer, ElementClass}
import com.scalableminds.webknossos.tracingstore.tracings.{
  FossilDBClient,
  KeyValueStoreImplicits,
  TemporaryVolumeDataStore,
  VersionedKey,
  VersionedKeyValuePair
}
import com.scalableminds.webknossos.wrap.WKWMortonHelper
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.duration._
import net.jpountz.lz4.{LZ4Compressor, LZ4Factory, LZ4FastDecompressor}

import scala.concurrent.ExecutionContext.Implicits.global

trait VolumeBucketCompression extends LazyLogging {

  private val lz4factory = LZ4Factory.fastestInstance
  val compressor: LZ4Compressor = lz4factory.fastCompressor
  val decompressor: LZ4FastDecompressor = lz4factory.fastDecompressor

  def compressVolumeBucket(data: Array[Byte], expectedUncompressedBucketSize: Int): Array[Byte] =
    if (data.length == expectedUncompressedBucketSize) {
      val compressedData = compressor.compress(data)
      if (compressedData.length < data.length) {
        compressedData
      } else data
    } else {
      // assume already compressed
      data
    }

  def decompressIfNeeded(data: Array[Byte], expectedUncompressedBucketSize: Int): Array[Byte] =
    if (data.length == expectedUncompressedBucketSize) {
      data
    } else {
      decompressor.decompress(data, expectedUncompressedBucketSize)
    }

  def expectedUncompressedBucketSizeFor(dataLayer: DataLayer): Int = {
    // frontend treats 8-byte segmentations as 4-byte segmentations,
    // truncating high IDs. Volume buckets will have at most 4-byte voxels
    val bytesPerVoxel = scala.math.min(ElementClass.bytesPerElement(dataLayer.elementClass), 4)
    bytesPerVoxel * scala.math.pow(DataLayer.bucketLength, 3).intValue
  }
}

trait VolumeTracingBucketHelper
    extends KeyValueStoreImplicits
    with FoxImplicits
    with VolumeBucketKeys
    with VolumeBucketCompression {

  implicit def volumeDataStore: FossilDBClient
  implicit def volumeDataCache: TemporaryVolumeDataStore

  private def loadBucketFromCache(key: String) =
    volumeDataCache.find(key).map(VersionedKeyValuePair(VersionedKey(key, 0), _))

  def loadBucket(dataLayer: VolumeTracingLayer,
                 bucket: BucketPosition,
                 version: Option[Long] = None): Fox[Array[Byte]] = {
    val key = buildBucketKey(dataLayer.name, bucket)
    val dataFox = loadBucketFromCache(key) match {
      case Some(data) => Fox.successful(data)
      case None       => volumeDataStore.get(key, version, mayBeEmpty = Some(true))
    }
    dataFox.futureBox
      .map(
        _.toOption.map { versionedVolumeBucket =>
          if (versionedVolumeBucket.value sameElements Array[Byte](0)) Fox.empty
          else
            Fox.successful(
              decompressIfNeeded(versionedVolumeBucket.value, expectedUncompressedBucketSizeFor(dataLayer)))
        }
      )
      .toFox
      .flatten
  }

  def loadBucketFromCache(dataLayer: VolumeTracingLayer, bucket: BucketPosition) = {}

  def saveBucket(dataLayer: VolumeTracingLayer,
                 bucket: BucketPosition,
                 data: Array[Byte],
                 version: Long,
                 toCache: Boolean = false): Fox[Unit] = {
    val key = buildBucketKey(dataLayer.name, bucket)
    val compressedBucket = compressVolumeBucket(data, expectedUncompressedBucketSizeFor(dataLayer))
    if (toCache) {
      // Note that this cache is for temporary volumes only (e.g. compound projects)
      // and cannot be used for download or versioning
      Fox.successful(volumeDataCache.insert(key, compressedBucket, Some(20 minutes)))
    } else {
      volumeDataStore.put(key, version, compressedBucket)
    }
  }

  def bucketStream(dataLayer: VolumeTracingLayer,
                   resolution: Int,
                   version: Option[Long]): Iterator[(BucketPosition, Array[Byte])] = {
    val key = buildKeyPrefix(dataLayer.name, resolution)
    new BucketIterator(key, volumeDataStore, expectedUncompressedBucketSizeFor(dataLayer), version)
  }

  def bucketStreamWithVersion(dataLayer: VolumeTracingLayer,
                              resolution: Int,
                              version: Option[Long]): Iterator[(BucketPosition, Array[Byte], Long)] = {
    val key = buildKeyPrefix(dataLayer.name, resolution)
    new VersionedBucketIterator(key, volumeDataStore, expectedUncompressedBucketSizeFor(dataLayer), version)
  }

  def bucketStreamFromCache(dataLayer: VolumeTracingLayer, resolution: Int): Iterator[(BucketPosition, Array[Byte])] = {
    val keyPrefix = buildKeyPrefix(dataLayer.name, resolution)
    val keyValuePairs = volumeDataCache.findAllConditionalWithKey(key => key.startsWith(keyPrefix))
    keyValuePairs.flatMap {
      case (bucketKey, data) =>
        parseBucketKey(bucketKey).map(tuple => (tuple._2, data))
    }.toIterator
  }
}

trait VolumeBucketKeys extends WKWMortonHelper {

  protected def buildKeyPrefix(dataLayerName: String, resolution: Int): String =
    s"$dataLayerName/${resolution}/"

  protected def buildBucketKey(dataLayerName: String, bucket: BucketPosition): String = {
    val mortonIndex = mortonEncode(bucket.x, bucket.y, bucket.z)
    s"$dataLayerName/${bucket.resolution.maxDim}/$mortonIndex-[${bucket.x},${bucket.y},${bucket.z}]"
  }

  protected def parseBucketKey(key: String): Option[(String, BucketPosition)] = {
    val keyRx = "([0-9a-z-]+)/(\\d+)/-?\\d+-\\[(\\d+),(\\d+),(\\d+)\\]".r

    key match {
      case keyRx(name, resolutionStr, xStr, yStr, zStr) =>
        val resolution = resolutionStr.toInt
        val x = xStr.toInt
        val y = yStr.toInt
        val z = zStr.toInt
        val bucket = BucketPosition(x * resolution * DataLayer.bucketLength,
                                    y * resolution * DataLayer.bucketLength,
                                    z * resolution * DataLayer.bucketLength,
                                    Point3D(resolution, resolution, resolution))
        Some((name, bucket))
      case _ =>
        None
    }
  }
}

class VersionedBucketIterator(prefix: String,
                              volumeDataStore: FossilDBClient,
                              expectedUncompressedBucketSize: Int,
                              version: Option[Long] = None)
    extends Iterator[(BucketPosition, Array[Byte], Long)]
    with WKWMortonHelper
    with KeyValueStoreImplicits
    with VolumeBucketCompression
    with VolumeBucketKeys
    with FoxImplicits {
  val batchSize = 64

  var currentStartKey = prefix
  var currentBatchIterator: Iterator[VersionedKeyValuePair[Array[Byte]]] = fetchNext

  def fetchNext =
    volumeDataStore.getMultipleKeys(currentStartKey, Some(prefix), version, Some(batchSize)).toIterator

  def fetchNextAndSave = {
    currentBatchIterator = fetchNext
    if (currentBatchIterator.hasNext) currentBatchIterator.next //in pagination, skip first entry because it was already the last entry of the previous batch
    currentBatchIterator
  }

  override def hasNext: Boolean =
    if (currentBatchIterator.hasNext) true
    else fetchNextAndSave.hasNext

  override def next: (BucketPosition, Array[Byte], Long) = {
    val nextRes = currentBatchIterator.next
    currentStartKey = nextRes.key
    parseBucketKey(nextRes.key)
      .map(key => (key._2, decompressIfNeeded(nextRes.value, expectedUncompressedBucketSize), nextRes.version))
      .get
  }

}

class BucketIterator(prefix: String,
                     volumeDataStore: FossilDBClient,
                     expectedUncompressedBucketSize: Int,
                     version: Option[Long] = None)
    extends Iterator[(BucketPosition, Array[Byte])] {
  val versionedBucketIterator =
    new VersionedBucketIterator(prefix, volumeDataStore, expectedUncompressedBucketSize, version)

  override def next: (BucketPosition, Array[Byte]) = {
    val tuple = versionedBucketIterator.next
    (tuple._1, tuple._2)
  }

  override def hasNext: Boolean = versionedBucketIterator.hasNext
}
