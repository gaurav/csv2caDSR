package org.renci.ccdh.csv2cadsr.output

import java.io.{File, FileWriter}
import java.time.format.DateTimeFormatter
import java.util.Calendar

import com.github.tototoshi.csv.CSVReader
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL.WithBigDecimal._
import org.json4s.native.JsonMethods.{pretty, render}
import org.json4s.{JArray, JField, JObject}

/**
  * Converts a harmonized CSV file to CEDAR instance data.
  *
  * Since CEDAR manages CEDAR templates and CEDAR instance data separately, we will generate two files:
  *   - ${cedarFilename}.template.json defines a CEDAR template for the harmonized data. This will include schema
  *     information for the fields as well as information on the CDEs that are being used.
  *   - ${cedarFilename}.instance[N].json provides information on *one* CEDAR instance. It includes a link to the
  *     template (by URI) as well as the data in JSON.
  *
  * TODO: To be able to quickly evaluate these formats, we'll start with some hard-coded strings. But eventually,
  *   we'll want to make the base URIs configurable.
  */
object ToCEDAR {
  // These constants should be made configurable in the future.
  val pavCreatedBy = "https://metadatacenter.org/users/ebca7bcb-4e1a-495b-919e-31884aa89461"

  def writeCEDAR(inputFile: File, reader: CSVReader, properties: Map[String, JValue], cedarBasename: File): Unit = {
    val (headerRow, dataWithHeaders) = reader.allWithOrderedHeaders()

    val pavCreatedOn = DateTimeFormatter.ISO_INSTANT.format(Calendar.getInstance().getTime.toInstant)

    // Step 1. Create a CEDAR Template for the harmonization information.
    // TODO none of this quite makes sense, which I suspect is bugs in the way CEDAR generates these files.
    // Once we have a working file, we should simplify this file as much as possible to see what is minimally required.
    val baseCEDARTemplate =
      ("@context" ->
        // Prefixes.
        ("oslc" -> "http://open-services.net/ns/core#") ~
        ("schema" -> "http://schema.org/") ~
        ("xsd" -> "http://www.w3.org/2001/XMLSchema#") ~
        ("bibo" -> "http://purl.org/ontology/bibo/") ~

        // Standard properties.
        ("pav:lastUpdatedOn" -> ("@type" -> "xsd:dateTime"))
      ) ~
      ("type" -> "object") ~
      ("additionalProperties" -> "false")

    val cedarTemplateBaseProperties =
      ("schema:isBasedOn" ->
        ("type" -> "string") ~
        ("format" -> "uri")
      ) ~
      ("schema:name" ->
        ("type" -> "string") ~
        ("minLength" -> 1)
      ) ~
      ("pav:createdBy" ->
        ("type" -> List("string", "null")) ~
        ("format" -> "uri")
      )

    val cedarTemplatePropertiesForCols = headerRow flatMap { colName => {
        properties.get(colName) match {
          case Some(prop: JObject) => {
            // Convert property from our format to that used by CEDAR templates.
            val property = {
              ("$schema" -> "http://json-schema.org/draft-04/schema#") ~
              ("@context" ->
                ("schema" -> "http://schema.org/") ~
                ("pav" -> "http://purl.org/pav/") ~
                ("xsd" -> "http://www.w3.org/2001/XMLSchema#") ~
                ("bibo" -> "http://purl.org/ontology/bibo/") ~
                ("skos" -> "http://www.w3.org/2004/02/skos/core#") ~
                ("oslc" -> "http://open-services.net/ns/core#") ~

                ("schema:name" ->  ("@type" -> "xsd:string")) ~
                ("pav:createdOn" -> ("@type" -> "xsd:dateTime")) ~
                ("oslc:modifiedBy" -> ("@type" -> "@id")) ~
                ("pav:lastUpdatedOn" -> ("@type" -> "xsd:dateTime")) ~
                ("skos:prefLabel" -> ("@type" -> "xsd:string")) ~
                ("skos:altLabel" -> ("@type" -> "xsd:string")) ~
                ("schema:description" -> ("@type" -> "xsd:string")) ~
                ("pav:createdBy" -> ("@type" -> "@id"))
              ) ~
              ("@type" -> "https://schema.metadatacenter.org/core/TemplateField") ~
              ("schema:schemaVersion" -> "1.6.0") ~
              ("additionalProperties" -> false) ~
              ("type" -> "object") ~
              ("properties" -> (
                "@value" -> (
                  "type" -> List("string", "null")
                ) ~
                ("@type" -> (
                  "oneOf" -> JArray(List(
                    ("type" -> "string") ~
                    ("format" -> "uri"),
                    ("minItems" -> 1) ~
                    ("type" -> "array") ~
                    ("items" ->
                      ("type" -> "string") ~
                      ("format" -> "uri")
                    ) ~
                    ("uniqueItems" -> true)
                  ))
                )) ~
                ("rdfs:label" ->
                  ("type" -> List("string", "null"))
                )
              )) ~
              // Field information.
              ("_ui" -> ("inputType" -> "textfield")) ~
              ("schema:name" -> colName) ~
              ("schema:description" -> prop \ "description") ~
              ("required" -> JArray(List("@value"))) ~
              ("skos:prefLabel" -> "colName") ~
              ("title" -> s"$colName field schema") ~
              ("description" -> s"$colName field schema generated by csv2caDSR") ~
              // Value information.
              ("_valueConstraints" -> (
                "requiredValue" -> false
              )) ~
              // Field metadata.
              ("pav:createdBy" -> pavCreatedBy) ~
              ("pav:createdOn" -> pavCreatedOn) ~
              ("oslc:modifiedBy" -> pavCreatedBy) ~
              ("pav:lastUpdatedOn" -> pavCreatedOn)
            }

            Some(JField(colName, property))
          }
          case Some(unk) => throw new RuntimeException(s"Unknown object in property information: $unk.")
          case None => throw new RuntimeException(s"Property $colName not present in JSON mapping file.")
        }
      }
    }

    val cedarTemplateProperties = cedarTemplateBaseProperties ~ cedarTemplatePropertiesForCols
    // scribe.info(s"cedarTemplateBaseProperties = ${pretty(render(cedarTemplateBaseProperties))}")
    // scribe.info(s"cedarTemplatePropertiesForCols = ${pretty(render(cedarTemplatePropertiesForCols))}")
    // scribe.info(s"cedarTemplateProperties ${pretty(render(cedarTemplateProperties))}")


            /*
              val fieldType: String = property.values.get("type").flatMap({
                case str: JString => Some(str.s)
                case _ => None
              }) match {
                case Some("integer") => "long" // We could also use "int" here if we want 32 bit ints.
                case Some("number") => "string" // We'll store it as a string and let it be reparsed (hopefully into BigDecimal) at the other end.
                case Some(str) => str
                case None => "string"
              }

              // Look for permissible values on this property.
              if (property.values.contains("permissibleValues")) {
                // scribe.info(s"property \\ permissibleValues: ${(property \ "permissibleValues").toOption}")
                val enumValues = (property \ "enumValues")
                val permissibleValues: Seq[String] = enumValues match {
                  case JArray(arr) => arr.map({
                    case obj: JObject => obj.values.getOrElse("caDSRValue", "").asInstanceOf[String]
                    case unk => throw new RuntimeException(s"Cannot read value in array in enumValues in property '$rowName': $unk")
                  }).filter(_.nonEmpty).map(mapFieldNameToPFB)
                  case unk => throw new RuntimeException(s"Cannot read value in enumValues in property '$rowName': $unk")
                }
                scribe.info(s"enumValues '$enumValues' gives us permissibleValues: $permissibleValues")

                if (permissibleValues.isEmpty) {
                  // We have failed to build an enum -- let's just fallback to using a string.
                  fieldsBuilder = fieldsBuilder
                    .name(mapFieldNameToPFB(rowName))
                    .`type`(Schema.createUnion(
                      Schema.create(Schema.Type.NULL),
                      Schema.create(Schema.Type.STRING)
                    ))
                    .noDefault()
                } else {
                  // We have permissible values we can work with!
                  harmonizationMappings.put(rowName, property \ "enumValues")

                  fieldsBuilder = fieldsBuilder
                    .name(mapFieldNameToPFB(rowName))
                    .`type`(Schema.createUnion(
                      Schema.create(Schema.Type.NULL),
                      Schema.createEnum(
                        mapFieldNameToPFB(rowName) + "_t",
                        s"Enumeration of field '${rowName}'",
                        "",
                        CollectionConverters.asJava(permissibleValues)
                      )
                    ))
                    .noDefault()
                }
              } else {
                fieldsBuilder = fieldsBuilder
                  .name(mapFieldNameToPFB(rowName))
                  .`type`(Schema.createUnion(
                    Schema.create(Schema.Type.NULL),
                    Schema.create(Schema.Type.valueOf(fieldType.toUpperCase))
                  ))
                  .noDefault()
              }
            case value: JValue => new RuntimeException(s"Expected JObject but obtained $value")
          }
        }*/

          val cedarTemplate = baseCEDARTemplate ~
            ("$schema" -> "http://json-schema.org/draft-04/schema#") ~
            ("@type" -> "https://schema.metadatacenter.org/core/Template") ~
            ("title" -> s"csv2caDSR CEDAR Template Export ($pavCreatedOn)") ~ // This appears to be ignored.
            ("description" -> s"csv2caDSR CEDAR Template Export from ${inputFile}") ~
            ("schema:name" -> s"csv2caDSR CEDAR Template Export ($pavCreatedOn)") ~
            ("schema:description" -> s"CEDAR Template Export based on harmonized data from ${inputFile}") ~
            ("pav:createdOn" -> pavCreatedOn) ~
            ("pav:createdBy" -> pavCreatedBy) ~
            ("pav:lastUpdatedOn" -> pavCreatedOn) ~
            ("bibo:status" -> "bibo:draft") ~
            ("pav:version" -> "0.0.1") ~
            ("schema:schemaVersion" -> "1.6.0") ~
            cedarTemplateProperties

          // Write out "$cedarBasename.template.json".
          val templateWriter = new FileWriter(new File(cedarBasename.getAbsolutePath + ".template.json"))
          templateWriter.append(pretty(render(cedarTemplate)))
          templateWriter.close()

          // Step 2. Create a CEDAR Instance for each row in the input data.
          val baseCEDARInstance =
            ("@context" ->
              // Prefixes
              ("skos" -> "http://www.w3.org/2004/02/skos/core#") ~
                ("pav" -> "http://purl.org/pav/") ~
                ("rdfs" -> "http://www.w3.org/2000/01/rdf-schema#") ~
                ("schema" -> "http://schema.org/") ~
                ("oslc" -> "http://open-services.net/ns/core#") ~
                ("xsd" -> "http://www.w3.org/2001/XMLSchema#") ~

                // CEDAR Template metadata
                ("skos:notation" -> ("@type" -> "xsd:string")) ~
                ("pav:derivedFrom" -> ("@type" -> "@id")) ~
                ("pav:createdOn" -> ("@type" -> "xsd:dateTime")) ~
                ("pav:lastUpdatedOn" -> ("@type" -> "xsd:dateTime")) ~
                ("oslc:modifiedBy" -> ("@type" -> "@id")) ~
                ("schema:isBasedOn" -> ("@type" -> "@id")) ~
                ("schema:description" -> ("@type" -> "xsd:string")) ~

                // Commonly used fields.
                ("id" -> "http://purl.org/dc/terms/identifier") ~
                ("rdfs:label" -> ("@type" -> "xsd:string"))
              )

          val cedarInstance = baseCEDARInstance ~
            ("schema:name" -> s"csv2caDSR CEDAR Instance Export ($pavCreatedOn)") ~
            ("pav:createdBy" -> pavCreatedBy) ~
            ("pav:createdOn" -> pavCreatedOn) ~
            ("pav:lastUpdatedOn" -> pavCreatedOn) ~
            ("oslc:modifiedBy" -> pavCreatedBy)
      }

    // TODO: add identifiers for the fields we are adding to the system here.

    /*

    val schemaBuilder = SchemaBuilder
      .record("export") // TODO: name this after the input filename, I guess?
    var fieldsBuilder = schemaBuilder.fields()

    // TODO: this should be colName, not rowName.
    val fieldInfo = mutable.Map[String, JObject]()
    val fieldTypes = mutable.Map[String, String]()
    val harmonizationMappings = mutable.Map[String, JValue]()
    headerRow foreach { rowName =>
      properties.get(rowName) foreach { case property: JObject =>
        val fieldType: String = property.values.get("type").flatMap({
          case str: JString => Some(str.s)
          case _ => None
        }) match {
          case Some("integer") => "long"  // We could also use "int" here if we want 32 bit ints.
          case Some("number") => "string" // We'll store it as a string and let it be reparsed (hopefully into BigDecimal) at the other end.
          case Some(str) => str
          case None => "string"
        }
        fieldTypes.put(rowName, fieldType)
        fieldInfo.put(rowName, property)

        // Look for permissible values on this property.
        if (property.values.contains("permissibleValues")) {
          // scribe.info(s"property \\ permissibleValues: ${(property \ "permissibleValues").toOption}")
          val enumValues = (property \ "enumValues")
          val permissibleValues: Seq[String] = enumValues match {
            case JArray(arr) => arr.map({
              case obj: JObject => obj.values.getOrElse("caDSRValue", "").asInstanceOf[String]
              case unk => throw new RuntimeException(s"Cannot read value in array in enumValues in property '$rowName': $unk")
            }).filter(_.nonEmpty).map(mapFieldNameToPFB)
            case unk => throw new RuntimeException(s"Cannot read value in enumValues in property '$rowName': $unk")
          }
          scribe.info(s"enumValues '$enumValues' gives us permissibleValues: $permissibleValues")

          if (permissibleValues.isEmpty) {
            // We have failed to build an enum -- let's just fallback to using a string.
            fieldsBuilder = fieldsBuilder
              .name(mapFieldNameToPFB(rowName))
              .`type`(Schema.createUnion(
                Schema.create(Schema.Type.NULL),
                Schema.create(Schema.Type.STRING)
              ))
              .noDefault()
          } else {
            // We have permissible values we can work with!
            harmonizationMappings.put(rowName, property \ "enumValues")

            fieldsBuilder = fieldsBuilder
              .name(mapFieldNameToPFB(rowName))
              .`type`(Schema.createUnion(
                Schema.create(Schema.Type.NULL),
                Schema.createEnum(
                  mapFieldNameToPFB(rowName) + "_t",
                  s"Enumeration of field '${rowName}'",
                  "",
                  CollectionConverters.asJava(permissibleValues)
                )
              ))
              .noDefault()
          }
        } else {
          fieldsBuilder = fieldsBuilder
            .name(mapFieldNameToPFB(rowName))
            .`type`(Schema.createUnion(
              Schema.create(Schema.Type.NULL),
              Schema.create(Schema.Type.valueOf(fieldType.toUpperCase))
            ))
            .noDefault()
        }
      case value: JValue => new RuntimeException(s"Expected JObject but obtained $value")
      }
    }

    val exportSchema = fieldsBuilder.endRecord()
    val schema = pfb.PFBSchemas.generatePFBForSchemas(Seq(exportSchema))
    // scribe.info(s"Created schema: ${schema.toString(true) }")

    val writer = new GenericDatumWriter[GenericRecord](schema)
    val dataFileWriter = new DataFileWriter[GenericRecord](writer)
    dataFileWriter.create(schema, pfbFilename)
    // TODO: It'd be nice to write this out to outputWriter like all the other CSVToOutputs,
    // but putting an OutputStreamWriter() around that messes up the output for some reason.

    // We need to create a Metadata entry for describing these fields.
    val metadataNode = new GenericData.Record(PFBSchemas.nodeSchema)
    metadataNode.put("name", "export")
    metadataNode.put("ontology_reference", "")
    metadataNode.put("values", Collections.EMPTY_MAP)
    metadataNode.put("links", Collections.EMPTY_LIST)
    metadataNode.put("properties", CollectionConverters.asJava(headerRow.flatMap({ rowName =>
      properties.get(rowName) map {
        case property: JObject =>
          val cdeIdOpt = property.values.get("caDSR")
          val cdeIdVersion = property.values.get("caDSRVersion")
          val description = property.values.get("description")

          val prop = new GenericData.Record(PFBSchemas.propertySchema)
          prop.put("name", rowName)
          prop.put("ontology_reference", description.getOrElse(""))

          cdeIdOpt match {
            case None | Some("") => // Nothing we can do.
              prop.put("values", Collections.EMPTY_MAP)
            case Some(cdeId) =>
              prop.put("values", CollectionConverters.asJava(Map(
                  "source" -> "caDSR",
                  "cde_id" -> cdeId,
                  "cde_version" -> cdeIdVersion.getOrElse(""),
                  "term_url" -> s"https://cdebrowser.nci.nih.gov/cdebrowserClient/cdeBrowser.html#/search?publicId=${cdeId}&version=${cdeIdVersion.getOrElse("1.0")}"
                )
              ))
          }

          prop
        case value: JValue => throw new RuntimeException(s"Unable to interpret property '$value': not a JObject")
      }
    })))

    val objectMeta = new GenericData.Record(PFBSchemas.metadataSchema)
    objectMeta.put("nodes", java.util.List.of(metadataNode))
    objectMeta.put("misc", Collections.EMPTY_MAP)

    val metadata = new GenericData.Record(schema)
    metadata.put("id", null)
    metadata.put("name", "Metadata")
    metadata.put("object", objectMeta)
    metadata.put("relations", Collections.EMPTY_LIST)

    dataFileWriter.append(metadata)

    dataWithHeaders.zipWithIndex.foreach({ case (row, index) =>
      val export = new GenericData.Record(exportSchema)
      row.keys.map({ colName =>
        val value = row.getOrElse(colName, "")
        val fieldType = fieldTypes.getOrElse(colName, "string")

        // If this is an enum field, we need to translate from the verbatim to the harmonized value.
        val mappedValue: Any = if (value == "") null else if(!harmonizationMappings.contains(colName)) {
          fieldType match {
            case "long" => value.toLong
            case _ => value
          }
        } else {
          val mappedValues: Seq[String] = harmonizationMappings.get(colName) match {
            case Some(JArray(list)) => list flatMap {
                case JObject(mappingValues) => {
                  // Is this mapping relevant?
                  val mapping = mappingValues.toMap
                  val inputValue = mapping.get("value")
                  if (inputValue.getOrElse("").equals(JString(value))) {
                    mapping.get("caDSRValue") match {
                      case Some(JString("")) => {
                        throw new RuntimeException(s"Could not map verbatim value '$inputValue' in $colName: no mapping in input JSON file")
                      }
                      case Some(JString(str)) => {
                        scribe.info(s"Mapping verbatim value '$inputValue' in $colName to: $str")
                        Some(mapFieldNameToPFB(str))
                      }
                      case unk => throw new RuntimeException(s"Unexpected value in caDSRValue field of harmonization mappings of ${colName}: ${unk}")
                    }
                  } else {
                    // Irrelevant mapping, ignore.
                    None
                  }
                }
                case unk => throw new RuntimeException(s"Unexpected value in harmonization mappings of ${colName}: ${unk}")
              }
            case unk => throw new RuntimeException(s"Unexpected value in harmonization mappings of ${colName}: ${unk}")
          }

          if (mappedValues.isEmpty) null
          else if(mappedValues.length > 1) throw new RuntimeException(s"Too many mapped values obtained for ${colName}: ${mappedValues.mkString("|")}")
          else {
            // We only have one mapped value! Return as an Enum symbol.
            // scribe.info(s"Looking up information on colName $colName (= ${mapFieldNameToPFB(colName)})")
            val fieldTypes = exportSchema.getField(mapFieldNameToPFB(colName)).schema().getTypes
            val enumTypes = CollectionConverters.asScala(fieldTypes).filter(_.getName != "null")

            scribe.info(s"Enum values for ${colName}: ${enumTypes.map(_.getEnumSymbols)}")

            if (enumTypes.length != 1) {
              throw new RuntimeException(s"Found multiple enumTypes, expected only one: ${enumTypes}")
            } else {
              GenericData.get().createEnum(mappedValues.head, enumTypes.head)
            }
          }
        }

        scribe.info(s"Output(${mapFieldNameToPFB(colName)}, ${if (mappedValue == null) "<null>" else mappedValue})")

        export.put(
          mapFieldNameToPFB(colName),
          mappedValue
        )
      })

      val datum = new GenericData.Record(schema)
      datum.put("id", "row" + index)
      datum.put("name", "export")
      datum.put("object", export)
      datum.put("relations", Collections.EMPTY_LIST)

      dataFileWriter.append(datum)
    })

    dataFileWriter.close()

     */

}