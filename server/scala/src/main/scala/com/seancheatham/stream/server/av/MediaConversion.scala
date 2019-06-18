package com.seancheatham.stream.server.av

import java.util.function.Supplier

import org.bytedeco.javacv.{Frame, OpenCVFrameConverter}
import org.opencv.core._
import org.opencv.imgproc

object MediaConversion {

  private val frameToMatConverter = ThreadLocal.withInitial(new Supplier[OpenCVFrameConverter.ToOrgOpenCvCoreMat] {
    def get(): OpenCVFrameConverter.ToOrgOpenCvCoreMat = new OpenCVFrameConverter.ToOrgOpenCvCoreMat
  })

  private val clahe = ThreadLocal.withInitial(new Supplier[imgproc.CLAHE] {
    def get(): imgproc.CLAHE = imgproc.Imgproc.createCLAHE()
  })

  /**
    * Returns an OpenCV Mat for a given JavaCV frame
    */
  def toMat(frame: Frame): Mat = frameToMatConverter.get().convert(frame)

  /**
    * Returns a JavaCV Frame for a given OpenCV Mat
    */
  def toFrame(mat: Mat): Frame =
    frameToMatConverter.get().convert(mat)


  /**
    * Clone the given OpenCV matrix and return an equalised version (CLAHE (Contrast Limited Adaptive Histogram Equalization))
    * of the matrix
    */
  def equalise(mat: Mat): Mat = {
    val clone = mat.clone()
    clahe.get().apply(mat, clone)
    clone
  }

}