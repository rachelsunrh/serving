package org.apache.spark.ml.feature

import org.apache.spark.ml.data.{SCol, SDFrame, UDF}
import org.apache.spark.ml.linalg.{Vector, VectorUDT, Vectors}
import org.apache.spark.ml.feature.PCAModel
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.transformer.ServingModel
import org.apache.spark.ml.util.SchemaUtils
import org.apache.spark.mllib.feature
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.mllib.linalg.{DenseMatrix => OldDenseMatrix, DenseVector => OldDenseVector,
  Matrices => OldMatrices, Vectors => OldVectors}

class PCAServingModel(stage: PCAModel) extends ServingModel[PCAServingModel] {

  override def copy(extra: ParamMap): PCAServingModel = {
    new PCAServingModel(stage.copy(extra))
  }

  override def transform(dataset: SDFrame): SDFrame = {
    transformSchema(dataset.schema)
    val pcaModel = new feature.PCAModel(stage.getK,
      OldMatrices.fromML(stage.pc).asInstanceOf[OldDenseMatrix],
      OldVectors.fromML(stage.explainedVariance).asInstanceOf[OldDenseVector])
    val pcaUDF = UDF.make[Vector, Vector](
      features => pcaModel.transform(OldVectors.fromML(features)).asML)
    dataset.withColum(pcaUDF.apply(stage.getOutputCol, SCol(stage.getInputCol)))
  }

  override def transformSchema(schema: StructType): StructType = {
    validateAndTransformSchemaImpl(schema)
  }

  /** Validates and transforms the input schema. */
  def validateAndTransformSchemaImpl(schema: StructType): StructType = {
    SchemaUtils.checkColumnType(schema, stage.getInputCol, new VectorUDT)
    require(!schema.fieldNames.contains(stage.getOutputCol),
      s"Output column ${stage.getOutputCol} already exists.")
    val outputFields = schema.fields :+ StructField(stage.getOutputCol, new VectorUDT, false)
    StructType(outputFields)
  }

  override val uid: String = stage.uid
}

object PCAServingModel {
  def apply(stage: PCAModel): PCAServingModel = new PCAServingModel(stage)
}
