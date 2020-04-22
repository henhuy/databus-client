package org.dbpedia.databus.client.filehandling

import java.io.{InputStream, OutputStream}
import java.nio.file.Files
import java.security.MessageDigest

import better.files.File
import org.apache.commons.io.IOUtils

import scala.io.Source
import scala.language.postfixOps
import scala.sys.process._

object FileUtil {

  /**
   * union part files of spark to one file
   *
   * @param dir directory that contains the part* files
   * @param targetFile target file
   */
  def unionFiles(dir: File, targetFile: File): Unit = {
    //union all part files of Apache Spark
    val findTripleFiles = s"find ${dir.pathAsString}/ -name part* -not -empty" !!
    val concatFiles = s"cat $findTripleFiles" #> targetFile.toJava !

    if (!(concatFiles == 0)) {
      System.err.println(s"[WARN] failed to merge ${dir.pathAsString}/*")
    }
  }


  /**
   * copy inputstream to outputstream
   *
   * @param in input-stream
   * @param out output-stream
   */
  def copyStream(in: InputStream, out: OutputStream): Unit = {
    try {
      IOUtils.copy(in, out)
    }
    finally if (out != null) {
      out.close()
    }
  }

  /**
   * checks if file is in cache directory
   *
   * @param cache_dir cache directory
   * @param fileSHA256 sha of file
   * @return
   */
  def checkIfFileInCache(cache_dir: File, fileSHA256: String): Boolean = {

    var exists = false
    val shaTxt = cache_dir / "shas.txt"

    if (shaTxt.exists){
      val bufferedSource = Source.fromFile(shaTxt.pathAsString)
      for (line <- bufferedSource.getLines) {
        val split = line.split(s"\t")
        if (split.head == fileSHA256) {
          exists = true
        }
      }
      bufferedSource.close()
    }

    exists
  }

  /**
   * calculate sha256-sum of file
   *
   * @param file file to calculate sha-sum from
   * @return sha256 string
   */
  def getSha256(file:File) : String = {
    MessageDigest.getInstance("SHA-256")
      .digest(Files.readAllBytes(file.path))
      .map("%02x".format(_)).mkString
  }

  /**
   * checks if a string matches the sh256-sum of a file
   *
   * @param file file to check
   * @param sha sha256-string
   * @return
   */
  def checkSum(file: File, sha: String): Boolean = {
    if (getSha256(file)== sha) true
    else false
  }

  /**
   * get file from cache with the sha256-sum
   *
   * @param sha sh256-sum
   * @param shaTxt file that contains all paths of files in the cache together with their sha256-sum
   * @return
   */
  def getFileInCacheWithSHA256(sha: String, shaTxt: File): File = {
    var fileOfSha = File("")

    if (shaTxt.exists){
      val bufferedSource = Source.fromFile(shaTxt.pathAsString)
      for (line <- bufferedSource.getLines) {
        val split = line.split(s"\t")
        if (split.head == sha) {
          fileOfSha = File(split(1))
        }
      }
      bufferedSource.close()
    }

    fileOfSha
  }

  /**
   * get sha-sum of a file in the cache
   *
   * @param file file in the cache
   * @param shaFile file that contains information about all files in the cache
   * @return
   */
  def getShaOfFileInCache(file:File, shaFile:File):String={
    var sha = ""

    if (shaFile.exists) {
      val bufferedSource = Source.fromFile(shaFile.pathAsString)
      for (line <- bufferedSource.getLines) {
        val split = line.split(s"\t")
        if (split(1) == file.pathAsString) {
          sha = split.head
        }
      }
      bufferedSource.close()
    }

    sha
  }

  /**
   * read a query file as string
   *
   * @param file query file
   * @return query string
   */
  def readQueryFile(file: File): String = {
    var queryString: String = ""
    for (line <- file.lineIterator) {
      queryString = queryString.concat(line).concat("\n")
    }
    queryString
  }


  //  def copyUnchangedFile(inputFile: File, src_dir: File, dest_dir: File): Unit = {
  //    val name = inputFile.name
  //
  //    val dataIdFile = inputFile.parent / "dataid.ttl"
  //
  //    val outputFile = {
  //      if (dataIdFile.exists) QueryHandler.getTargetDir(dataIdFile, dest_dir) / name
  //      else File(inputFile.pathAsString.replaceAll(src_dir.pathAsString, dest_dir.pathAsString.concat("/NoDataID/")))
  //    }
  //
  //    //    println(s"Copy unchanged File to: ${outputFile.pathAsString}")
  //    outputFile.parent.createDirectoryIfNotExists(createParents = true)
  //
  //    val outputStream = new FileOutputStream(outputFile.toJava)
  //    copyStream(new FileInputStream(inputFile.toJava), outputStream)
  //  }
}
