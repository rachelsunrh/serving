package org.apache.spark.ml.feature

import org.apache.spark.ml.attribute.BinaryAttribute
import org.apache.spark.ml.data.{SCol, SDFrame, UDF}
import org.apache.spark.ml.linalg._
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.transformer.ServingTrans
import org.apache.spark.sql.types.{DoubleType, StructField, StructType}

import scala.collection.mutable.ArrayBuilder

class BinarizerServing(stage: Binarizer) extends ServingTrans{

  override def transform(dataset: SDFrame): SDFrame = {
    val outputSchema = transformSchema(dataset.schema, logging = true)
    val schema = dataset.schema
    val inputType = schema(stage.getInputCol).dataType
    val td = stage.getThreshold

    val binarizerDouble = UDF.make[Double, Double]{ in => if (in > td) 1.0 else 0.0 }
    val binarizerVector = UDF.make[Vector, Vector](data => {
      val indices = ArrayBuilder.make[Int]
      val values = ArrayBuilder.make[Double]

      data.foreachActive { (index, value) =>
        if (value > td) {
          indices += index
          values +=  1.0
        }
      }

      Vectors.sparse(data.size, indices.result(), values.result()).compressed
    })

    val metadata = outputSchema(stage.getOutputCol).metadata
    inputType match {
      case DoubleType =>
        dataset.select(SCol(), binarizerDouble.apply(stage.getOutputCol, SCol(stage.getInputCol))
          .setSchema(stage.getOutputCol, metadata))
      case _: VectorUDT =>
        dataset.select(SCol(), binarizerVector.apply(stage.getOutputCol, SCol(stage.getInputCol))
          .setSchema(stage.getOutputCol, metadata))
    }
  }

  override def copy(extra: ParamMap): BinarizerServing = {
    new BinarizerServing(stage.copy(extra))
  }

  override def transformSchema(schema: StructType): StructType = {
    val inputType = schema(stage.getInputCol).dataType
    val outputColName = stage.getOutputCol

    val outCol: StructField = inputType match {
      case DoubleType =>
        BinaryAttribute.defaultAttr.withName(outputColName).toStructField()
      case _: VectorUDT =>
        StructField(outputColName, new VectorUDT)
      case _ =>
        throw new IllegalArgumentException(s"Data type $inputType is not supported.")
    }

    if (schema.fieldNames.contains(outputColName)) {
      throw new IllegalArgumentException(s"Output column $outputColName already exists.")
    }
    StructType(schema.fields :+ outCol)
  }

  override val uid: String = stage.uid
}

object BinarizerServing {
  def apply(stage: Binarizer): BinarizerServing = new BinarizerServing(stage)
}