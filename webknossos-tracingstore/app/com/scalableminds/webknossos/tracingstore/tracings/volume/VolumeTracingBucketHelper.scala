package com.scalableminds.webknossos.tracingstore.tracings.volume

import com.scalableminds.util.geometry.Point3D
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import com.scalableminds.webknossos.datastore.models.BucketPosition
import com.scalableminds.webknossos.datastore.models.datasource.{DataLayer, ElementClass}

import com.scalableminds.webknossos.tracingstore.tracings.{
  FossilDBClient,
  KeyValueStoreImplicits,
  VersionedKeyValuePair
}
import com.scalableminds.webknossos.wrap.WKWMortonHelper
import com.typesafe.scalalogging.LazyLogging
import net.jpountz.lz4.{LZ4Compressor, LZ4Factory, LZ4FastDecompressor}

import scala.concurrent.ExecutionContext.Implicits.global

trait VolumeBucketCompression extends LazyLogging {

  private val lz4factory = LZ4Factory.fastestInstance
  val compressor: LZ4Compressor = lz4factory.fastCompressor
  val decompressor: LZ4FastDecompressor = lz4factory.fastDecompressor

  def compressVolumeBucket(data: Array[Byte]): Array[Byte] = {
    val compressedData = compressor.compress(data)

    logger.info(s"${data.length} -> ${compressedData.length}, ${Math.round(data.length / compressedData.length)}x")
    if (compressedData.length < data.length) {
      compressedData
    } else data
  }

  def decompressIfNeeded(key: String, data: Array[Byte], expectedUncompressedBucketSize: Int): Array[Byte] =
    if (key.endsWith("/lz4")) {
      decompressor.decompress(data, expectedUncompressedBucketSize)
    } else {
      data
    }

  def expectedUncompressedBucketSizeFor(dataLayer: DataLayer): Int =
    ElementClass.bytesPerElement(dataLayer.elementClass) * scala.math.pow(DataLayer.bucketLength, 3).intValue
}

trait VolumeTracingBucketHelper
    extends WKWMortonHelper
    with KeyValueStoreImplicits
    with FoxImplicits
    with VolumeBucketCompression {

  implicit def volumeDataStore: FossilDBClient

  private def buildKeyPrefix(dataLayerName: String, resolution: Int): String =
    s"$dataLayerName/${resolution}/"

  private def buildBucketKey(dataLayerName: String, bucket: BucketPosition): String = {
    val mortonIndex = mortonEncode(bucket.x, bucket.y, bucket.z)
    s"$dataLayerName/${bucket.resolution.maxDim}/$mortonIndex-[${bucket.x},${bucket.y},${bucket.z}]/lz4"
  }

  def loadBucket(dataLayer: VolumeTracingLayer,
                 bucket: BucketPosition,
                 version: Option[Long] = None): Fox[Array[Byte]] = {
    val key = buildBucketKey(dataLayer.name, bucket)
    val bucketAndCompressedBucket = volumeDataStore.getMultipleKeys(key, Some(key), version)
    if (bucketAndCompressedBucket.isEmpty)
      Fox.empty
    else {
      val newestBucket: VersionedKeyValuePair[Array[Byte]] = bucketAndCompressedBucket.maxBy(_.version)
      if (newestBucket.value.sameElements(Array[Byte](0))) Fox.empty
      else
        Fox.successful(
          decompressIfNeeded(newestBucket.key,
                             bucketAndCompressedBucket.head.value,
                             expectedUncompressedBucketSizeFor(dataLayer)))
    }
  }

  def saveBucket(dataLayer: VolumeTracingLayer, bucket: BucketPosition, data: Array[Byte], version: Long): Fox[Unit] = {
    val key = buildBucketKey(dataLayer.name, bucket)
    volumeDataStore.put(key, version, compressVolumeBucket(data))
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
}

class VersionedBucketIterator(prefix: String,
                              volumeDataStore: FossilDBClient,
                              expectedUncompressedBucketSize: Int,
                              version: Option[Long] = None)
    extends Iterator[(BucketPosition, Array[Byte], Long)]
    with WKWMortonHelper
    with KeyValueStoreImplicits
    with VolumeBucketCompression
    with FoxImplicits {
  val batchSize = 64
  val batchPadding = 1
  val batchOverlap = 1

  private var batchCursor = 0
  private var currentStartKey = prefix
  private var currentBatchList: List[VersionedKeyValuePair[Array[Byte]]] = fetchNext

  def fetchNext: List[VersionedKeyValuePair[Array[Byte]]] =
    volumeDataStore.getMultipleKeys(currentStartKey, Some(prefix), version, Some(batchSize + batchPadding))

  private def fetchNextAndSave(): Unit = {
    currentBatchList = fetchNext.tail //in pagination, skip first entry because it was already the last entry of the previous batch
    batchCursor = 0
  }

  override def hasNext: Boolean = {
    logger.info(s"hasNext. cursor $batchCursor, batchListLength ${currentBatchList.length}, batch size $batchSize")
    if (batchCursor >= currentBatchList.length && batchCursor < batchSize)
      // this is the last batch and we are at its end
      false
    else {
      if (batchCursor >= batchSize) {
        fetchNextAndSave()
      }
      currentBatchList.nonEmpty
    }
  }

  override def next: (BucketPosition, Array[Byte], Long) = {
    logger.info(s"next. cursor $batchCursor, batchListLength ${currentBatchList.length}, batch size $batchSize")
    val nextBucket = currentBatchList(batchCursor)
    val nextBucketAfterOpt = currentBatchList.lift(batchCursor + 1)
    val nextBucketSelected =
      if (nextBucketAfterOpt.isDefined) {
        val nextBucketAfter = nextBucketAfterOpt.get
        val nextKey = parseBucketKey(nextBucket.key).get
        val nextAfterKey = parseBucketKey(nextBucketAfter.key).get
        if (nextKey == nextAfterKey) {
          batchCursor += 1
          List(nextBucket, nextBucketAfter).maxBy(_.version)
        } else nextBucket
      } else nextBucket
    batchCursor += 1
    currentStartKey = nextBucket.key.replaceAll("/lz4", "")
    parseBucketKey(nextBucketSelected.key)
      .map(
        key =>
          (key._2,
           decompressIfNeeded(nextBucketSelected.key, nextBucketSelected.value, expectedUncompressedBucketSize),
           nextBucketSelected.version))
      .get
  }

  private def parseBucketKey(key: String): Option[(String, BucketPosition)] = {
    val keyRx = "([0-9a-z-]+)/(\\d+)/-?\\d+-\\[(\\d+),(\\d+),(\\d+)\\]*".r

    key.replaceAll("/lz4", "") match {
      case keyRx(name, resolutionStr, xStr, yStr, zStr) =>
        val resolution = resolutionStr.toInt
        val x = xStr.toInt
        val y = yStr.toInt
        val z = zStr.toInt
        val bucket = new BucketPosition(x * resolution * DataLayer.bucketLength,
                                        y * resolution * DataLayer.bucketLength,
                                        z * resolution * DataLayer.bucketLength,
                                        Point3D(resolution, resolution, resolution))
        Some((name, bucket))
      case _ =>
        logger.warn(s"Failed to parse bucket key $key")
        None
    }
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
