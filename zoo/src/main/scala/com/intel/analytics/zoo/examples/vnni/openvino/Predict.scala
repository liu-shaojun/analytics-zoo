/*
 * Copyright 2018 Analytics Zoo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.zoo.examples.vnni.openvino

import com.intel.analytics.bigdl.dataset.SampleToMiniBatch
import com.intel.analytics.bigdl.nn.abstractnn.Activity
import com.intel.analytics.bigdl.numeric.NumericFloat
import com.intel.analytics.bigdl.transform.vision.image.ImageFeature
import com.intel.analytics.zoo.common.NNContext
import com.intel.analytics.zoo.feature.image.{ImageCenterCrop, ImageMatToTensor, ImageResize, ImageSet, ImageSetToSample}
import com.intel.analytics.zoo.models.image.imageclassification.{LabelOutput, LabelReader}
import com.intel.analytics.zoo.pipeline.inference.InferenceModel
import org.apache.logging.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.config.Configurator
import org.opencv.imgcodecs.Imgcodecs
import scopt.OptionParser

case class PredictParams(folder: String = "./",
                         model: String = "",
                         weight: String = "",
                         batchSize: Int = 4,
                         topN: Int = 5,
                         partitionNum: Int = 4)

object Predict {
  Configurator.setLevel("org", Level.ERROR)
  Configurator.setLevel("akka", Level.ERROR)
  Configurator.setLevel("breeze", Level.ERROR)
  Configurator.setLevel("com.intel.analytics.zoo.feature.image", Level.ERROR)
  Configurator.setLevel("com.intel.analytics.zoo", Level.INFO)

  val logger = LogManager.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val parser = new OptionParser[PredictParams]("ResNet50 Int8 Inference Example") {
      opt[String]('f', "folder")
        .text("The path to the image data")
        .action((x, c) => c.copy(folder = x))
        .required()
      opt[String]('m', "model")
        .text("The path to the int8 ResNet50 model")
        .action((x, c) => c.copy(model = x))
        .required()
      opt[String]('w', "weight")
        .text("The path to the int8 ResNet50 model weight")
        .action((x, c) => c.copy(weight = x))
      opt[Int]("topN")
        .text("top N number")
        .action((x, c) => c.copy(topN = x))
      opt[Int]("partitionNum")
        .text("The number of partitions to cut the dataset into")
        .action((x, c) => c.copy(partitionNum = x))
      opt[Int]('b', "batchSize")
        .text("batch size")
        .action((x, c) => c.copy(batchSize = x))
    }
    parser.parse(args, PredictParams()).map(param => {
      val sc = NNContext.initNNContext("Predict Image with OpenVINO Int8 Example")

      val model = new InferenceModel(1)

      model.doLoadOpenVINO(param.model, param.weight, param.batchSize)

      // Read ImageNet val
      val images = ImageSet.read(param.folder,
        imageCodec = Imgcodecs.CV_LOAD_IMAGE_COLOR).toLocal()

      val start = System.nanoTime()
      // Pre-processing
      val inputs = images ->
        ImageResize(256, 256) ->
        ImageCenterCrop(224, 224) ->
        ImageMatToTensor(shareBuffer = false) ->
        ImageSetToSample()
      val batched = inputs.toDataSet() -> SampleToMiniBatch(param.batchSize)

      val preProcessTime = System.nanoTime() - start
      val imageNum = images.array.length
      val batchNum = batched.toLocal().data(false).size

      // Predict
      logger.debug("Begin Prediction")
      val predicts = batched.toLocal().data(false).flatMap(miniBatch => {
        val predict = model.doPredict(miniBatch
          .getInput.toTensor.addSingletonDimension())
        predict.toTensor.squeeze.split(1).asInstanceOf[Array[Activity]]
      })
      // Add prediction into imageset
      images.array.zip(predicts.toIterable).foreach(tuple => {
        tuple._1(ImageFeature.predict) = tuple._2
      })
      // Transform prediction into Labels and probs
      val labelOutput = LabelOutput(LabelReader.apply("IMAGENET"))
      val results = labelOutput(images).toLocal().array

      // Output results
      results.foreach(imageFeature => {
        logger.info(s"image: ${imageFeature.uri}, top ${param.topN}")
        val classes = imageFeature("classes").asInstanceOf[Array[String]]
        val probs = imageFeature("probs").asInstanceOf[Array[Float]]
        for (i <- 0 until param.topN) {
          logger.info(s"\t class: ${classes(i)}, credit: ${probs(i)}")
        }
      })
      logger.info(s"Prediction finished.")

      val timeUsed = System.nanoTime() - start
      // Post-processing
      val throughput = "%.2f"
        .format(imageNum / (timeUsed / 1e9))
      val predictThroughput = "%.2f"
        .format(imageNum / ((timeUsed - preProcessTime)/ 1e9))
      val batchPredictLatency = "%.2f"
        .format((timeUsed - preProcessTime) / 1e6 / batchNum)
      val batchLatency = "%.2f".format(timeUsed / 1e6 / batchNum)
      logger.info(s"Pre-Processing: Takes ${preProcessTime / 1e6} ms")
      logger.info(s"Predict: Takes ${(timeUsed - preProcessTime) / 1e6} ms, " +
        s"throughput is ${predictThroughput} FPS (imgs/sec)")
      logger.info(s"Average Batch Predict latency is $batchPredictLatency ms")
      logger.info(s"End to End: Takes ${timeUsed / 1e6} ms, " +
        s"throughput is $throughput FPS (imgs/sec)")
      logger.info(s"Average End to End Batch Predict latency is $batchLatency ms")
      // Evaluation
      // Compare labels and output results
      sc.stop()
    })
  }
}
