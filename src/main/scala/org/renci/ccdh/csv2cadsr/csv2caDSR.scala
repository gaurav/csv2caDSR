package org.renci.ccdh.csv2cadsr

import java.io.{BufferedWriter, FileWriter, OutputStreamWriter}

import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import org.json4s.{DefaultFormats, JObject, JValue, StringInput}

import scala.io.Source
import org.json4s.native.Serialization.writePretty
import org.json4s.native.JsonMethods._
import caseapp._

import scala.collection.immutable.HashMap

@AppName("MyApp")
@AppVersion("0.1.0")
@ProgName("my-app-cli")
case class CommandLineOptions(
  @ValueDescription("An optional file to write output to")
  @ExtraName("o")
  outputFilename: Option[String]
)

object csv2caDSR extends CaseApp[CommandLineOptions] {
  def run(options: CommandLineOptions, args: RemainingArgs) = {
    val csvFilename: String = args.remaining.head
    val jsonFilename: Option[String] = args.remaining.tail.headOption
    val bufferedWriter = options.outputFilename match {
      case Some(filename: String) => new BufferedWriter(new FileWriter(filename))
      case _ => new BufferedWriter(new OutputStreamWriter(System.out))
    }

    implicit val formats = DefaultFormats

    if (jsonFilename.isEmpty) {
      // No JSON file provided? Then generate the JSON file!
      val csvSource: Source = Source.fromFile(csvFilename)("UTF-8")

      val result = schema.MappingGenerator.generateFromCsv(csvSource)
      result.fold(
        throwable => scribe.error(s"Could not generate JSON Schema: ${throwable}"),
        result => bufferedWriter.write(writePretty(result.asJsonSchema))
      )
    } else if (csvFilename == "fill") {
      // This is a hack to fill in the JSON Schema with information from the caDSR system.
      val jsonSource: Source = Source.fromFile(jsonFilename.get)("UTF-8")

      val filledScheme = schema.Filler.fill(parse(StringInput(jsonSource.getLines().mkString("\n"))))
      bufferedWriter.write(writePretty(filledScheme))
    } else {
      // We have a JSON schema file and a CSV file. Generate the JSON!
      val csvSource: Source = Source.fromFile(csvFilename)("UTF-8")
      val jsonSource: Source = Source.fromFile(jsonFilename.get)("UTF-8")

      val jsonRoot = parse(StringInput(jsonSource.getLines().mkString("\n")))
      val properties: Map[String, JValue] = jsonRoot match {
        case obj: JObject =>
          obj.values.getOrElse("properties", HashMap()).asInstanceOf[HashMap[String, JValue]]
        case _ => throw new RuntimeException("JSON source is not a JSON object")
      }

      val reader = CSVReader.open(csvSource)
      val (headerRow, dataWithHeaders) = reader.allWithOrderedHeaders()

      // For now, we write to STDOUT.
      val writer = CSVWriter.open(bufferedWriter)
      writer.writeRow(headerRow flatMap { rowName =>
        val caDSR = {
          val property = properties.getOrElse(rowName, HashMap()).asInstanceOf[Map[String, String]]
          val caDSR = property.getOrElse("caDSR", "")
          val caDSRVersion = property.getOrElse("caDSRVersion", "")
          if (caDSR.nonEmpty && caDSRVersion.nonEmpty) s"${caDSR}v$caDSRVersion"
          else caDSR
        }
        Seq(rowName, s"${rowName}_caDSR_cde_${caDSR}_value", s"${rowName}_ncit_uri")
      })

      dataWithHeaders.foreach(row => {
        val rowValues: Seq[String] = headerRow flatMap { rowName =>
          val rowValue = row.getOrElse(rowName, "")

          val rowProp = properties.getOrElse(rowName, HashMap()).asInstanceOf[Map[String, _]]
          val enumValues =
            rowProp.getOrElse("enumValues", List()).asInstanceOf[List[Map[String, String]]]
          val mapping: Map[String, String] =
            enumValues.find(_.getOrElse("value", "") == rowValue).getOrElse(HashMap())
          val caDSRValue = mapping.getOrElse("caDSRValue", "")
          val conceptURI = mapping.getOrElse("conceptURI", "")

          Seq(rowValue, caDSRValue, conceptURI)
        }
        writer.writeRow(rowValues)
      })

      writer.close()
    }
  }
}
