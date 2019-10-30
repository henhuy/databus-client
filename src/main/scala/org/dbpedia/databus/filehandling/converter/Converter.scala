package org.dbpedia.databus.filehandling.converter

import java.io._
import java.nio.file.NoSuchFileException

import better.files.File
import net.sansa_stack.rdf.spark.io.RDFWriter
import org.apache.commons.compress.archivers.dump.InvalidFormatException
import org.apache.commons.compress.compressors.{CompressorException, CompressorStreamFactory}
import org.apache.jena.graph.Triple
import org.apache.spark.SparkException
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.dbpedia.databus.filehandling.FileUtil
import org.dbpedia.databus.filehandling.FileUtil.copyStream
import org.dbpedia.databus.filehandling.converter.rdf_reader.{JSONL_Reader, NTriple_Reader, RDF_Reader, TTL_Reader}
import org.dbpedia.databus.filehandling.converter.rdf_writer.{JSONLD_Writer, RDFXML_Writer, TSV_Writer, TTL_Writer}
import org.dbpedia.databus.sparql.QueryHandler
import org.slf4j.LoggerFactory

import scala.io.Source
import scala.util.control.Breaks.{break, breakable}

object Converter {

  def convertFile(inputFile: File, src_dir: File, dest_dir: File, outputFormat: String, outputCompression: String): Unit = {
    println(s"input file:\t\t${inputFile.pathAsString}")
    val bufferedInputStream = new BufferedInputStream(new FileInputStream(inputFile.toJava))

    val compressionInputFile = getCompressionType(bufferedInputStream)
    val formatInputFile = getFormatType(inputFile, compressionInputFile)

    if (outputCompression == compressionInputFile && (outputFormat == formatInputFile || outputFormat == "same")) {
      val outputStream = new FileOutputStream(getOutputFile(inputFile, formatInputFile, compressionInputFile, src_dir, dest_dir).toJava)
      copyStream(new FileInputStream(inputFile.toJava), outputStream)
    }
    else if (outputCompression != compressionInputFile && (outputFormat == formatInputFile || outputFormat == "same")) {
      val decompressedInStream = decompress(bufferedInputStream)
      val compressedFile = getOutputFile(inputFile, formatInputFile, outputCompression, src_dir, dest_dir)
      val compressedOutStream = compress(outputCompression, compressedFile)
      copyStream(decompressedInStream, compressedOutStream)
    }

    //  With FILEFORMAT CONVERSION
    else {

      formatInputFile match {
        case "rdf" | "ttl" | "nt" | "jsonld" =>
        case _ => {
          LoggerFactory.getLogger("File Format Logger").error(s"Input file format $formatInputFile not supported.")
          println(s"Input file format $formatInputFile not supported.")
        }
      }

      val targetFile = getOutputFile(inputFile, outputFormat, outputCompression, src_dir, dest_dir)
      var typeConvertedFile = File("")

      if (!(compressionInputFile == "")) {
        val decompressedInStream = decompress(bufferedInputStream)
        val decompressedFile = inputFile.parent / inputFile.nameWithoutExtension(true).concat(s".$formatInputFile")
        copyStream(decompressedInStream, new FileOutputStream(decompressedFile.toJava))
        typeConvertedFile = convertFormat(decompressedFile, formatInputFile, outputFormat)
        decompressedFile.delete()
      }
      else {
        typeConvertedFile = convertFormat(inputFile, formatInputFile, outputFormat)
      }

      val compressedOutStream = compress(outputCompression, targetFile)
      //file is written here
      copyStream(new FileInputStream(typeConvertedFile.toJava), compressedOutStream)

      try {
        typeConvertedFile.parent.delete()
      }
      catch {
        case noSuchFileException: NoSuchFileException => LoggerFactory.getLogger("ConverterLogger").error(s"Parent of Converted File ${typeConvertedFile.pathAsString} didn't exist")
      }
    }

  }

  def convertFormat(inputFile: File, inputFormat: String, outputFormat: String): File = {

    val spark = SparkSession.builder()
      .appName(s"Triple reader  ${inputFile.name}")
      .master("local[*]")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .getOrCreate()

    val sparkContext = spark.sparkContext
    sparkContext.setLogLevel("WARN")

    val data = readTriples(inputFile, inputFormat, spark: SparkSession)

    val convertedFile = writeTriples(inputFile, data, outputFormat, spark)
    //    spark.close()

    convertedFile
  }

  def readTriples(inputFile: File, inputFormat: String, spark: SparkSession): RDD[Triple] = {


    inputFormat match {
      case "nt" => NTriple_Reader.readNTriples(spark, inputFile)
      case "rdf" => RDF_Reader.readRDF(spark, inputFile)
      case "ttl" => {
        if (NTriple_Reader.readNTriples(spark, inputFile).isEmpty()) TTL_Reader.readTTL(spark, inputFile)
        else NTriple_Reader.readNTriples(spark, inputFile)
      }
      case "jsonld" => RDF_Reader.readRDF(spark, inputFile) //Ein Objekt pro Datei
      case "jsonl" => try { //Mehrere Objekte pro Datei
        JSONL_Reader.readJSONL(spark, inputFile)
      } catch {
        case onlyOneJsonObject: SparkException => {
          println("Json Object ueber mehrere Zeilen")
          RDF_Reader.readRDF(spark, inputFile)
        }
      }
      case "tsv" => spark.sparkContext.emptyRDD[Triple] //mappings.TSV_Reader.tsv_nt_map(spark)
    }
  }

  def writeTriples(inputFile: File, data: RDD[Triple], outputFormat: String, spark: SparkSession): File = {

    val tempDir = inputFile.parent / "temp"
    val headerTempDir = inputFile.parent / "tempheader"
    val targetFile: File = tempDir / inputFile.nameWithoutExtension.concat(s".$outputFormat")

    //    //delete temp directory if exists
    //    try {
    //      tempDir.delete()
    //    } catch {
    //      case noFile: NoSuchFileException => ""
    //    }

    outputFormat match {
      case "nt" => data.saveAsNTriplesFile(tempDir.pathAsString)
      case "tsv" => {
        val solution = TSV_Writer.convertToTSV(data, spark)
        solution(1).write.option("delimiter", "\t").option("nullValue", "?").option("treatEmptyValuesAsNulls", "true").csv(tempDir.pathAsString)
        solution(0).write.option("delimiter", "\t").csv(headerTempDir.pathAsString)
      }
      case "ttl" => TTL_Writer.convertToTTL(data, spark).coalesce(1).saveAsTextFile(tempDir.pathAsString)
      case "jsonld" => JSONLD_Writer.convertToJSONLD(data, spark).saveAsTextFile(tempDir.pathAsString)
      case "rdfxml" => RDFXML_Writer.convertToRDFXML(data, spark).coalesce(1).saveAsTextFile(tempDir.pathAsString)
    }

    try {
      outputFormat match {
        case "tsv" => FileUtil.unionFilesWithHeaderFile(headerTempDir, tempDir, targetFile)
        case "jsonld" | "jsonl" | "nt" | "ttl" | "rdfxml" => FileUtil.unionFiles(tempDir, targetFile)
      }
    }
    catch {
      case fileAlreadyExists: RuntimeException => LoggerFactory.getLogger("UnionFilesLogger").error(s"File $targetFile already exists") //deleteAndRestart(inputFile, inputFormat, outputFormat, targetFile: File)
    }

    return targetFile
  }

  def getCompressionType(fileInputStream: BufferedInputStream): String = {
    try {
      var ctype = CompressorStreamFactory.detect(fileInputStream)
      if (ctype == "bzip2") {
        ctype = "bz2"
      }
      return ctype
    }
    catch {
      case noCompression: CompressorException => ""
      case inInitializerError: ExceptionInInitializerError => ""
      case noClassDefFoundError: NoClassDefFoundError => ""
    }
  }

  def getFormatType(inputFile: File, compressionInputFile: String) = {
    {
      try {
        if (!(getFormatTypeWithDataID(inputFile) == "")) {
          getFormatTypeWithDataID(inputFile)
        } else {
          getFormatTypeWithoutDataID(inputFile, compressionInputFile)
        }
      } catch {
        case fileNotFoundException: FileNotFoundException => getFormatTypeWithoutDataID(inputFile, compressionInputFile)
      }
    }
  }

  def getOutputFile(inputFile: File, outputFormat: String, outputCompression: String, src_dir: File, dest_dir: File): File = {

    val nameWithoutExtension = inputFile.nameWithoutExtension
    val name = inputFile.name
    var filepath_new = ""
    val dataIdFile = inputFile.parent / "dataid.ttl"

    val newOutputFormat = {
      if (outputFormat == "rdfxml") "rdf"
      else outputFormat
    }

    if (dataIdFile.exists) {
      val dir_structure: List[String] = QueryHandler.executeDataIdQuery(dataIdFile)
      filepath_new = dest_dir.pathAsString.concat("/")
      dir_structure.foreach(dir => filepath_new = filepath_new.concat(dir).concat("/"))
      filepath_new = filepath_new.concat(nameWithoutExtension)
    }
    else {
      // changeExtensionTo() funktioniert nicht bei noch nicht existierendem File, deswegen ausweichen über Stringmanipulation
      filepath_new = inputFile.pathAsString.replaceAll(src_dir.pathAsString, dest_dir.pathAsString.concat("/NoDataID"))
      filepath_new = filepath_new.replaceAll(name, nameWithoutExtension)
    }

    if (outputCompression.isEmpty) {
      filepath_new = filepath_new.concat(".").concat(newOutputFormat)
    }
    else {
      filepath_new = filepath_new.concat(".").concat(newOutputFormat).concat(".").concat(outputCompression)
    }

    val outputFile = File(filepath_new)
    //create necessary parent directories to write the outputfile there, later
    outputFile.parent.createDirectoryIfNotExists(createParents = true)

    println(s"converted file:\t${outputFile.pathAsString}\n")

    return outputFile
  }

  def getFormatTypeWithDataID(inputFile: File): String = {
    // Suche in Dataid.ttl nach allen Zeilen die den Namen der Datei enthalten
    val lines = Source.fromFile((inputFile.parent / "dataid.ttl").toJava, "UTF-8").getLines().filter(_ contains s"${inputFile.name}")

    val regex = s"<\\S*dataid.ttl#${inputFile.name}\\S*>".r
    var fileURL = ""

    for (line <- lines) {
      breakable {
        for (x <- regex.findAllMatchIn(line)) {
          fileURL = x.toString().replace(">", "").replace("<", "")
          break
        }
      }
    }

    val fileType = QueryHandler.getTypeOfFile(fileURL, inputFile.parent / "dataid.ttl")
    return fileType
  }

  def getFormatTypeWithoutDataID(inputFile: File, compression: String): String = {
    var split = inputFile.name.split("\\.")
    var fileType = ""

    if (compression == "") {
      fileType = split(split.size - 1)
    } else {
      fileType = split(split.size - 2)
    }

    return fileType
  }

  def deleteAndRestart(inputFile: File, inputFormat: String, outputFormat: String, file: File): Unit = {
    file.delete()
    convertFormat(inputFile, inputFormat, outputFormat)
  }

  def decompress(bufferedInputStream: BufferedInputStream): InputStream = {
    //Welche Funktion hat actualDecompressConcatenated?
    try {

      new CompressorStreamFactory().createCompressorInputStream(
        CompressorStreamFactory.detect(bufferedInputStream),
        bufferedInputStream,
        true
      )

    } catch {

      case ce: CompressorException =>
        System.err.println(s"[WARN] No compression found for input stream - raw input")
        bufferedInputStream

      case unknown: Throwable => println("[ERROR] Unknown exception: " + unknown)
        bufferedInputStream
    }
  }

  def compress(outputCompression: String, output: File): OutputStream = {
    try {
      // file is created here
      val myOutputStream = new FileOutputStream(output.toJava)
      val out: OutputStream = outputCompression match {
        case "bz2" => new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.BZIP2, myOutputStream)
        case "gz" => new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.GZIP, myOutputStream)
        case "deflate" => new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.DEFLATE, myOutputStream)
        //        case "lz4-block" => new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.LZ4_BLOCK, myOutputStream)
        //        case "lz4-framed" => new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.LZ4_FRAMED, myOutputStream)
        case "lzma" => new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.LZMA, myOutputStream)
        //        case "pack200" => new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.PACK200, myOutputStream)
        case "sz" => new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.SNAPPY_FRAMED, myOutputStream)
        //        case "snappy-raw" => new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.SNAPPY_RAW, myOutputStream)
        case "xz" => new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.XZ, myOutputStream)
        case "zstd" => new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.ZSTANDARD, myOutputStream)
        case "" => myOutputStream //if outputCompression is empty
      }
      return out
    } catch {
      case invalidFormat: InvalidFormatException => {
        LoggerFactory.getLogger("CompressorLogger").error(s"InvalidFormat $outputCompression")
        new FileOutputStream(output.toJava)
      }
    }
  }

}
