/*
 * Copyright (C) 2011-2014 Scalable minds UG (haftungsbeschränkt) & Co. KG. <http://scm.io>
 */
package com.scalableminds.braingames.binary

import scala.collection.JavaConversions._
import java.io.{ByteArrayInputStream, File, FileInputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.{SuffixFileFilter, TrueFileFilter}
import com.scalableminds.util.io.{NamedFileStream, ZipIO}
import com.scalableminds.braingames.binary.models.DataLayer
import com.typesafe.scalalogging.LazyLogging

trait DataDownloadHelper extends LazyLogging{

  def downloadDataLayer(dataLayer: DataLayer, outputStream: OutputStream): Unit = {
    try {
      val basePath = Paths.get(dataLayer.baseDir)
      val files = FileUtils.listFiles(new File(dataLayer.baseDir), new SuffixFileFilter(".raw"), TrueFileFilter.INSTANCE)


      ZipIO.zip(
        files.toList.map {
          file =>
            new NamedFileStream(
              () => new FileInputStream(file),
              basePath.relativize(Paths.get(file.getAbsolutePath)).toString)
        },
        outputStream)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to zip datalayer ${dataLayer.name} for download. Error: " + e)
    }
  }

}