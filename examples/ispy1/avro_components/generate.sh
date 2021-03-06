#!/bin/bash

# 
# Extract all the data from the ISPY1 Avro file.
# 
# Requirements: 
#   - PyPFB (provides an `pfb` script): https://github.com/uc-cdis/pypfb
#   - jq (for pretty printing JSON): https://stedolan.github.io/jq/
#

# Extract all the data rows from the PFB file.
pfb show -i ../ispy1-patient-clinical-subset.avro | jq . > ispy1-patient-clinical-subset_data.jsonl

# Extract the schema from the PFB file.
pfb show -i ../ispy1-patient-clinical-subset.avro schema | jq . > ispy1-patient-clinical-subset_schema.json

# Extract the schema from the PFB file.
pfb show -i ../ispy1-patient-clinical-subset.avro metadata | jq . > ispy1-patient-clinical-subset_metadata.json

# Extract all the node names in the PFB file.
pfb show -i ../ispy1-patient-clinical-subset.avro nodes > ispy1-patient-clinical-subset_nodes.txt
