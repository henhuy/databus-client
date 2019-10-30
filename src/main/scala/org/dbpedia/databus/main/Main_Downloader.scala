package org.dbpedia.databus.main

import better.files.File
import org.dbpedia.databus.filehandling.FileUtil
import org.dbpedia.databus.filehandling.downloader.Downloader
import org.dbpedia.databus.main.cli.CLIConf

object Main_Downloader {

  def main(args: Array[String]) {

    println("Welcome to DBPedia - Download tool")

    val conf = new CLIConf(args)
    val cache_dir = File("./cache_dir/").createDirectoryIfNotExists()
    val dataId_string = "dataid.ttl"

    //Test if query is a File or a Query
    val queryString: String = File(conf.query()).exists() match {
      case true => Downloader.readQueryFile(File(conf.query()))
      case _ => conf.query()
    }

    val allSHAs = Downloader.downloadWithQuery(queryString, cache_dir)

    allSHAs.foreach(
      sha => FileUtil.copyUnchangedFile(FileUtil.getFileWithSHA256(sha, cache_dir), cache_dir, File(conf.destination()))
    )
    //    if(checkIfFileInCache(targetdir, fileSHA))
    //    val files = cache_dir.listRecursively.toSeq
    //    for (file <- files) {
    //      if (!file.isDirectory) {
    //        if (!file.name.equals(dataId_string)) {
    //          FileUtil.copyUnchangedFile(file, cache_dir, File(conf.destination()))
    //        }
    //      }
    //      else if (file.name == "temp") { //Delete temp dir of previous failed run
    //        file.delete()
    //      }
    //    }

    println("\n--------------------------------------------------------\n")
    println(s"Files have been downloaded to ${conf.destination()}")
    //    download_temp.delete()
  }

}
