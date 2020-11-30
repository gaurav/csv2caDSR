package org.renci.ccdh.csv2cadsr.schema

import java.io.File
import java.net.URI

import com.github.tototoshi.csv.CSVReader
import org.json4s._

import scala.io.Source
import scala.util.{Success, Try}

import org.json4s.JsonDSL.WithBigDecimal._

/**
  * A MappingSchema records all the information needed to map an input CSV file into the Portable Format
  * for Biomedical Data (PFB) and/or CEDAR element formats. It consists of a sequence of MappingFields
  * (described below).
  *
  * There are three types of data we want to capture in this JSON Schema:
  *  - Basic metadata: field name, description field, and so on.
  *  - Semantic mapping: what does this field mean? Can it be represented by a caDSR value?
  *  - JSON Schema typing information: is it a string, number, enum? What are the enumerated values?
  *  - Value mapping: how can the enumerated values be mapped to concepts?
  */
case class MappingSchema(fields: Seq[MappingField]) {
  def asJsonSchema: JObject = {
    val fieldEntries = fields map { field => (field.name, field.asJsonSchema) }

    JObject(
      // List of all the properties.
      "properties" -> JObject(fieldEntries.toList),
      // Properties named in this list are required.
      "required" -> JArray(fields.filter(_.required).map(_.name).map(JString).toList)
    )
  }
}

/**
  * Some static methods for MappingSchema.
  */
object MappingSchema {
  val empty = MappingSchema(Seq())

  /**
    * Try to generate a mapping schema from a CSV file.
    *
    * @param csvFile A CSV file to load.
    * @return Either Success(aMappingSchema) or Failure(Exception).
    */
  def generateFromCsv(csvFile: File): Try[MappingSchema] = {
    // Step 1. Load data from CSV file.
    val csvSource = Source.fromFile(csvFile, "UTF-8")
    val reader = CSVReader.open(csvSource)
    val (headerRow, dataWithHeaders) = reader.allWithOrderedHeaders()

    // Headers are in the first row.
    val fields = headerRow map { fieldName =>
      MappingField.createFromValues(fieldName, dataWithHeaders.flatMap(_.get(fieldName)))
    }

    Success(MappingSchema(fields))
  }
}

/**
  * A MappingField contains information on each field in a mapping.
  */
abstract class MappingField(
  /** The name of this field */
  val name: String,
  /** The set of unique values that we observe in this field. */
  val uniqueValues: Set[String],
  /** Whether this field appears to be required. */
  val required: Boolean = false
) {
  override def toString: String = {
    s"${getClass.getSimpleName}(${name} with ${uniqueValues.size} unique values)"
  }

  abstract def asJsonSchema: JObject
}

/**
  * Some static methods for generating mapping fields.
  */
object MappingField {
  /**
    * If the number of unique values are less than this proportion of all values,
    * we consider this field to be an enum.
    */
  final val enumProportion = 0.1

  /**
    * Create a field that represents a column name and a particular sequence of values.
    */
  def createFromValues(name: String, values: Seq[String]): MappingField = {
    // Which unique values are found in this field?
    val uniqueValues = values.toSet

    // Mark this property as required if we have no blanks in the values.
    val isRequired = !(values exists { v => v.isBlank })

    uniqueValues match {
      // Is it an empty field?
      case _ if (uniqueValues.isEmpty) => EmptyField(name, isRequired)

      // Is it an enum field?
      case _ if (uniqueValues.size < values.size * enumProportion) => {
        val uniqueValuesByCount = values
          .groupBy(identity)
          .transform((_, v) => v.size)
          .toSeq
          .sortBy(_._2)(Ordering[Int].reverse)
        EnumField(
          name,
          uniqueValues,
          uniqueValuesByCount.map(_._1).map(EnumValue.fromString),
          isRequired
        )
      }

      // Is it an integer field?
      case _ if (uniqueValues forall { str => str forall Character.isDigit }) => {
        val intValues = values flatMap (_.toIntOption)
        IntField(name, uniqueValues, isRequired, Range.inclusive(intValues.min, intValues.max))
      }

      // If we can't figure this out, assume that it is a string field.
      case _ => StringField(name, uniqueValues, isRequired)
    }
  }
}

/**
  * String fields are the most basic kind of field: they include string values, and don't put any constraint on them.
  */
case class StringField(
  override val name: String,
  override val uniqueValues: Set[String],
  override val required: Boolean = false
) extends MappingField(name, uniqueValues) {
  override def asJsonSchema: JObject =
    ("type" -> "string") ~
    ("description" -> "") ~
    ("caDSR" -> "") ~
    ("caDSRVersion" -> "1.0")
}

/**
  * Enum fields can have one of a set of values (modeled by EnumValues).
  */
case class EnumField(
  override val name: String,
  override val uniqueValues: Set[String],
  val enumValues: Seq[EnumValue] = Seq(),
  override val required: Boolean = false
) extends MappingField(name, uniqueValues, required) {
  override def toString: String = {
    s"${getClass.getSimpleName}(${name} with ${uniqueValues.size} unique values: ${uniqueValues.mkString(", ")})"
  }

  override def asJsonSchema: JObject =
    ("type" -> "string") ~
    ("description" -> "") ~
    ("caDSR" -> "") ~
    ("caDSRVersion" -> "1.0") ~
    ("permissibleValues" -> JArray(List())) ~
    ("enum" -> JArray(enumValues.map(_.value).map(JString).toList)) ~
    ("enumValues" -> JArray(enumValues.map(_.asMapping).toList))
}

/** An EnumValue represents one  */
case class EnumValue(val value: String, val conceptURI: Option[URI] = None) {
  def asMapping: JObject =
    JObject(
      "value" -> JString(value),
      "description" -> JString(""),
      "caDSRValue" -> JString(""),
      "conceptURI" -> JString(conceptURI map (_.toString) getOrElse (""))
    )
}
object EnumValue {
  def fromString(value: String): EnumValue = {
    // Does the value contain a URI?
    val uriRegex = "(https?://\\S+)".r
    val result =
      uriRegex findAllIn (value) map (m => Try { new URI(m) }) filter (_.isSuccess) map (_.get)
    // If so, use the last URI as the conceptURI.
    EnumValue(value, result.toSeq.lastOption)
  }
}
case class IntField(
  override val name: String,
  override val uniqueValues: Set[String],
  override val required: Boolean,
  range: Range
) extends MappingField(name, uniqueValues) {
  override def toString: String = {
    s"${getClass.getSimpleName}(${name} with ${uniqueValues.size} unique values in ${range})"
  }

  override def asJsonSchema: JObject =
    JObject(
      "type" -> JString("string"),
      "description" -> JString(""),
      "caDSR" -> JString(""),
      "caDSRVersion" -> JString("1.0")
    )
}
case class EmptyField(override val name: String, override val required: Boolean)
    extends MappingField(name, Set()) {
  override def asJsonSchema: JObject =
    JObject(
      "type" -> JString("string"),
      "description" -> JString(""),
      "caDSR" -> JString(""),
      "caDSRVersion" -> JString("1.0")
    )
}
